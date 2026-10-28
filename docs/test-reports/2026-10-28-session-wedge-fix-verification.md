# 2026-10-28 EMQX 会话楔死修复验证（e2e 与全量回归）

## 被测范围与代码状态

- 范围：关闭 EMQX 磁盘持久会话、改用内存持久会话（`max_mqueue_len=100000`、`max_inflight=32`）后，e2e 五个故障场景与全量回归。
- 基线提交：`2070936`（分支 `fix/emqx-session-wedge`），在其上调整 `E2eEnvironment`、`ReliabilityE2EIT`（提交见 git 历史 `test(e2e): 按内存持久会话调整重启 EMQX 场景`）。
- 本次 e2e 改动：Testcontainers 的 EMQX 环境变量改为 `EMQX_DURABLE_SESSIONS__ENABLE=false`、`EMQX_MQTT__MAX_MQUEUE_LEN=100000`、`EMQX_MQTT__MAX_INFLIGHT=32`；场景 4「重启 EMQX」改用带 id 的可靠上报设备（与场景 5 共用 `runReliableScenario`），断言不变（计数恰好、无死信、无待确认）；`TRANSPORT_MONITOR_ENABLED=false` 未改动。

## 环境

Windows 11 本机，JDK 21（liberica-21.0.8）；Docker 为虚拟机远程 API；EMQX 5.10.5 / Redis / PostgreSQL / InfluxDB 3 由 Testcontainers 启动，server、两个 transport 为本地 jar 进程。

## 实际命令与结果

```
mvn -B -DskipTests install                 # BUILD SUCCESS
E2E_DEV_HOST=<本机在虚拟网内的地址> mvn -B verify -Pe2e -pl conduit-e2e
mvn -B -o verify
```

### 无效尝试（如实记录）

第一次 e2e 把 `E2E_DEV_HOST` 设成了 Docker 虚拟机自身的地址。该变量的含义是「Docker 宿主机访问本机的地址」（EMQX 回调本机 server 的 `/internal/mqtt/auth`），设错导致 EMQX 回调不到 server、transport 持续 `NOT_AUTH`，`@BeforeAll` 等主实例超时，**0 个场景执行**（`ConditionTimeoutException`，总耗时 2:01）。这是环境变量配置错误，与代码无关。改为本机在虚拟网内的地址后重跑。

### e2e（第二次，有效）

| 项 | 结果 |
|---|---|
| EmqxConfigTest | 2 通过 |
| 场景 1 强杀 server | 通过 |
| 场景 2 强杀主 transport | 通过 |
| 场景 3 两个 transport 都结束约 20 秒 | 通过 |
| 场景 4 重启 EMQX（带 id 设备） | 通过 |
| 场景 5 强杀 EMQX（带 id 设备） | 通过 |

`ReliabilityE2EIT` 5 项 0 失败 0 跳过，耗时 203.8 s；整个命令 BUILD SUCCESS，3:28 min。未放宽任何断言或超时；未出现因 transport 5ms 攒批导致的超时。

### 全量回归 `mvn -B -o verify`

BUILD SUCCESS，3:50 min。各模块（单测 + 集成测试）：common 9+20、transport 81+16、server 255+268、simulator 82+4，合计 **735 项，0 失败 0 错误 0 跳过**。相对本次修复开始前的基线 699 项多 36 项（新增用例来自前序任务，本次提交只改 e2e 与报告）。

### 补充：场景 4/5 的设备侧重发条数（排除「碰巧通过」）

为确认场景 4 不是因重启时恰好无在途消息而空过，`ReliableE2eDevice` 新增重发计数（同一条消息第一次之外的发送），`runReliableScenario` 在发送结束时以 INFO 记录，不加断言。重跑 e2e（同命令，`E2E_DEV_HOST` 为本机虚拟网地址）：五个场景全部通过（`ReliabilityE2EIT` 5 项，205.7 s，BUILD SUCCESS 3:33）。

| 场景 | 设备侧重发条数 |
|---|---|
| 4 重启 EMQX（优雅重启） | 20 |
| 5 强杀 EMQX（SIGKILL） | 20 |

两个场景都真实触发了设备重发，场景 4 不是空过。两个数字恰好相同（10 台设备 × 每台 2 次），本次未进一步分析原因，也未多次运行评估波动，样本仅一次。

## 跳过或阻塞项

- 压测复验（3000 条/秒 × 180 秒）：见下节，不在本次执行范围。
- 前端 Vitest 不在 `mvn -B -o verify` 范围，未执行。

## 非阻塞警告

- e2e 模块打包时 `JAR will be empty - no content was marked for inclusion!`（既有，无影响）。

## 日志位置

`conduit-e2e/target/e2e-logs/<时间戳>/`（server、transport-a、transport-b）；失败的第一次为 `20261028-115656`。

## 压测复验

- 执行：控制方，2026-10-28
- 环境：压测机 192.168.126.129，Rocky Linux 10.2、8 核、7.5 GB 内存，standalone 栈 11 容器
- 被测版本：`2a54424`，经 `deploy/package.sh` 打包后以 `BUILD_FROM=prebuilt` 部署
- 负载：开发机 Python + paho-mqtt，300 台设备 × 10 条/秒 = 3000 条/秒，持续 180 秒，QoS 1、不带消息 id
- 指标来源：EMQX REST API `GET /api/v5/clients/conduit-transport`，使用本次新增的只读 API key（与 transport 会话监控同源）

### 部署前置

旧的 EMQX 数据卷 `conduit_emqx-data` 已丢弃——其中残留着旧配置下的磁盘持久会话，会污染本次验证。PostgreSQL / InfluxDB / Redis 数据卷保留。

`.env` 在原有基础上追加了本次新增的必填变量 `CONDUIT_EMQX_API_KEY` / `CONDUIT_EMQX_API_SECRET`，以及 `EMQX_MAX_MQUEUE_LEN=100000`、`EMQX_MAX_INFLIGHT=32`。

### 过程中发现并修正的部署错误（如实记录）

首次部署只执行了 `docker compose up -d`，**未加 `--build`**，于是容器沿用了 2026-10-27 的旧镜像，jar 中不含 `dev/conduit/transport/monitor/` 包。表现为健康检查里完全没有 `session` 明细。执行 `docker compose build` 后重新启动才生效。

教训：`BUILD_FROM=prebuilt` 时更新 `artifacts/` 不会自动重建镜像；且「容器已启动」不等于「部署了预期的版本」。

### 会话监控的真实环境验证

重建后两个 transport 的 `/actuator/health` 中 `components.leader.details`：

| 实例 | 内容 |
|---|---|
| 备实例 | `session: {monitoring: "备实例，不监控本会话"}` |
| 主实例 | `session: {sendMsg: 828, mqueueLen: 0, consecutiveFailures: 0, lastSuccessAt: "2026-10-28T04:17:09.350Z"}` |

主实例正在成功轮询 EMQX REST API（`consecutiveFailures=0` 且有 `lastSuccessAt`），备实例明确标示自身未监控。监控链路在真实部署中端到端可用。

### 加压过程

| t（秒） | send_msg | 投递/秒 | mqueue_len | inflight_cnt | mqueue_dropped | heap |
|---|---|---|---|---|---|---|
| 15 | 22,973 | 1,326 | 0 | 23 | 0 | 0.0 MB |
| 61 | 112,976 | 1,977 | 0 | 32 | 0 | 0.0 MB |
| 109 | 206,594 | 1,968 | **128** | 32 | 0 | 0.9 MB |
| 185 | 356,911 | 1,983 | 0 | 10 | 0 | 0.1 MB |
| 停压后 | 367,376 | — | **0** | **0** | **0** | 0.0 MB |

客户端：发送 546,075 条、确认 364,075 条。服务端 `send_msg` 自基线 2,901 增至 367,376，增量 364,475，与客户端确认数一致（差约 400 为采样时差）。

### 断言结果

| 断言 | 结果 |
|---|---|
| 投递不停滞（`send_msg` 持续增长） | **通过** |
| 停压后 `mqueue_len` 与 `inflight_cnt` 归零 | **通过**（均为 0） |
| EMQX 超堆被杀 0 次 | **通过**（加压前取基线，增量为 0） |
| `durable` 为 false | **通过** |
| 累计 `mqueue_dropped` | **0** |

### 与修复前的三方对比

| | 磁盘持久会话 | 内存会话（未微批） | 本次（内存 + 微批） |
|---|---|---|---|
| 投递总数 | 39,811（**永久冻结**） | 307,263 | **367,376** |
| `mqueue_dropped` | 0（另有约 29 万条滞留 DS） | **53,552** | **0** |
| mqueue 峰值 | — | 触顶 100,000 | **128** |
| 超堆被杀 | 有，重连即死循环 | 0 | 0 |
| 停压后 | 不排空，需人工丢弃会话 | 自愈排空 | 自愈排空 |

微批把持续投递速率由约 1,230 条/秒提高到**稳态约 1,980 条/秒**（峰值 2,006），恰好越过 3000 条/秒接入负载下的积压临界点：队列峰值由触顶 10 万降至 128 条，丢弃由 53,552 降至 0。

### 未达成的计划项（重要）

计划要求测量「**mqueue 满载时 `heap_size` 与 384MB 堆限的余量**」，**本次未能取得**：修复后队列峰值仅 128 条，根本没有满载，`heap_size` 峰值只有 0.9 MB。

因此「10 万条 mqueue 是否会撑爆 384MB 堆」这一问题在本次验证中**未能取得**。

> **已于同日取得答案**：在后续的容量压测中，队列填满 100,000 条时会话进程堆达到 **513 MB**，超过 384 MB 上限并被杀（约 5.4 KB/条）。即当前默认 `EMQX_MAX_MQUEUE_LEN=100000` 偏大。详见 [2026-10-28 容量与瓶颈压测报告](2026-10-28-capacity-and-bottleneck.md)。该风险只在入库彻底停摆、队列持续堆积时才会触发，而那正是最不希望 EMQX 连接进程被杀的时刻。要取得这个数字，需要构造「接入正常但入库停摆」的场景（例如停掉 server 的流水线或 InfluxDB）并持续加压至队列接近容量。**在取得实测前，不应上调 `EMQX_MAX_MQUEUE_LEN`。**

### 其他未执行项

- 未做 `max_inflight` / `batch-size` 成对上调（64 / 256 / 1024）的吞吐阶梯测试，留待后续容量规划。
- 未做长时间浸泡测试（压测机磁盘仅 16 GB）。
- 未测连接数上限，本次最多 300 条并发连接。
