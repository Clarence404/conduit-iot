# EMQX 持久会话楔死：机制定位与对比实验

- 日期：2026-10-28（Asia/Shanghai）
- 被测版本：`8b98dde`
- 环境：独占压测机 192.168.126.129，Rocky Linux 10.2、8 核、7.5 GB 内存，standalone 栈 11 容器，EMQX 5.10.5
- 负载端：开发机 Python + paho-mqtt，300 台设备 × 10 条/秒 = 3000 条/秒，QoS 1、不带消息 id
- 结论：**磁盘持久会话是楔死的直接成因**；关闭后同样负载不再楔死，且失败模式从「静默整批丢 + 必须人工」变为「可见计数丢 + 自愈」

## 1. 故障现象

压测中 EMQX 会话投递完全停止，且**所有可观测信号都正常**：

| 观测点 | 状态 |
|---|---|
| transport `mqtt-delivery` 线程栈 | **空闲**，parking 在 `LinkedBlockingQueue.take()` |
| EMQX↔transport TCP socket | **sendQ=0 / recvQ=0**，完全空 |
| EMQX 会话 | `inflight=32` 冻结、`connected=true`、`dropped_msgs=0` |
| 容器 | `RestartCount=0`、无 OOM |
| 两侧日志 | **零错误** |
| 健康检查 | transport UP、`leader.connected=true` |

那 32 条「在途」消息既不在网络上、也不在 transport，只存在于 EMQX 会话进程的账本里。`max_inflight=32` 的窗口被占满后不再取下一批，投递永久停止。

## 2. 为什么重启救不回来

重启 transport 后：接管会话 → 投递正好 32 条（一个 inflight 窗口）→ 连接进程被杀 → 重连 → 再投 32 条 → 再被杀，死循环。

```
[error] maximum heap size reached
Max Heap Size: 50331648        # Erlang 字 ×8 = 384 MB
emqx_persistent_session_ds:replay_batch/4
```

384 MB 正好是配置的 `EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE=256MB` 的 1.5 倍，**说明该配置项确实在约束重放进程**（2026-10-27 报告称「不受该项约束」有误，当时只比了数值未换算倍数，此处更正）。

触发所需积压只有 **3.3 万条**，并非此前记录的 41 万。

## 3. 已排除的假设

### 3.1 `mqtt.retry_interval = infinity`（EMQX 5 默认）

推断是「永不重传 → 窗口永久占满」。两步验证，**均证伪**：

| 验证 | 结果 |
|---|---|
| 对**已楔死**的会话 `conf load` 改为 30s，观察 150 秒 | 零变化，32 条一条未重传 |
| **先改配置、再建新会话**，重跑压测，停压后观察 240 秒 | 照样在加压约 70 秒后冻结（`delivered` 停在 39,811），停压后 228 秒零变化 |

结论：`mqtt.retry_interval` 对持久会话不生效，`emqx_persistent_session_ds` 走独立投递路径。

附带确定一条：**对已楔死的会话，改配置救不回来**；运行中的会话进程不采纳新的重传设置。

### 3.2 transport 是瓶颈

证伪。冻结时 transport 投递线程空闲、socket 为空。微批 XADD 不能修复楔死（但见第 5 节——它对另一个问题有用）。

### 3.3 会话用久了损坏

证伪。第二轮是全新会话（`session_present=False`），同样在约 70 秒后冻结。

## 4. 对比实验：关闭磁盘持久会话

唯一变量 `durable_sessions.enable: true → false`。`max_mqueue_len` 由默认 1000 提到 100000（否则测到的只会是溢出丢弃而非楔死），`retry_interval` 复位 `infinity` 与基线一致，`max_inflight=32` 不变。同一套 3000 条/秒 × 180 秒负载。

| | 磁盘持久会话 | 内存持久会话 |
|---|---|---|
| `delivered_msgs` 最终 | **39,811（冻结）** | **307,263（排空）** |
| 投递是否停止 | 是，永久 | 否 |
| 停压后积压 | `enqueued=32,944` 不动 | **归零**，`inflight=0` |
| `dropped_msgs` | 0（但约 29 万条卡在 DS） | **53,552（可见）** |
| EMQX 超堆被杀 | 有，重连即触发死循环 | **0** |
| 恢复方式 | 人工 cleanStart，**丢整个积压** | **自愈** |

内存会话轮的账能对上：客户端确认 360,415 ≈ 投递 307,263 + 丢弃 53,552 = 360,815（差约 400 为采样时差）。

磁盘会话轮对不上：确认 362,916，而 `delivered + enqueued` 仅 72,755，**约 29 万条滞留在 DS 存储中**（`enqueued_msgs` 不统计这部分）——这些正是 cleanStart 恢复时会丢掉的数据。

## 5. 意外发现：投递速率上限约 1,230 条/秒

内存会话轮中，307,263 条在约 250 秒内投完，**持续投递速率约 1,230 条/秒**，低于 3000 条/秒的接入速率，所以 mqueue 触顶并丢弃 53,552 条。

瓶颈在 transport 的**单线程投递执行器**：每条消息一次 XADD。这修正了第 3.2 节的表述——微批 XADD 对**楔死**无用，但对**提高持续投递上限、避免 mqueue 溢出**直接有用。

## 6. 未执行与限制

- **Redis 流增量指标作废**：流已达 `MAXLEN` 100 万上限被裁剪，`XLEN` 恒在上限附近（本轮增量恒约 978），无法用于计量。应改用消费组 `entries-read` 或末条目 id，本次未重测。
- **积压阈值阶梯（10k/50k/100k）未完成**：中途改为优先验证 `retry_interval`，之后被对比实验取代。超堆阈值与积压量的定量关系仍未标定。
- **磁盘会话轮约 29 万条滞留 DS 的去向未直接验证**，为按计数差推断，未检查 DS 存储本身。
- **未验证内存会话在 EMQX 重启时的行为**（预期丢失未投递消息，这正是设备侧 `id` 重发要覆盖的场景）。
- 未跑 `conduit-e2e -Pe2e`，本次无代码变更。

## 7. 环境遗留状态

压测机 192.168.126.129 当前运行在**关闭磁盘持久会话**的配置下，改动在 `deploy/dev/docker-compose.yml`，原文件已备份为 `docker-compose.yml.durable-bak`。该改动**未进入仓库**。
