# EMQX 会话楔死修复设计（V0.2-R 修正）

- 日期：2026-10-28
- 状态：设计已确认，待实施
- 实测依据：[`docs/test-reports/2026-10-28-emqx-durable-session-wedge.md`](../../test-reports/2026-10-28-emqx-durable-session-wedge.md)、[`docs/test-reports/2026-10-27-load-test-and-pipeline-ceiling.md`](../../test-reports/2026-10-27-load-test-and-pipeline-ceiling.md)

## 1. 问题

3000 条/秒压测下，EMQX 磁盘持久会话在约 70 秒后**投递永久停止**：`inflight` 占满 32、`delivered_msgs` 冻结、`dropped_msgs` 为 0、`connected=true`，transport 投递线程空闲、TCP socket 为空、两侧日志零错误、健康检查全绿。

重启 transport 不能恢复：重连触发 `emqx_persistent_session_ds:replay_batch`，连接进程超堆（384 MB）被杀，投递 32 条后再次被杀，死循环。唯一出路是以 `cleanStart=true` 丢弃会话，**连同滞留在 DS 中的约 29 万条消息一起丢失**。

已证伪的三个假设（过程见测试报告第 3 节）：`mqtt.retry_interval` 对持久会话不生效；transport 不是瓶颈；与会话新旧无关。

对比实验（唯一变量 `durable_sessions.enable`）：关闭后同样负载下投递 307,263 条并完全排空，`dropped_msgs=53,552` 可见计数，EMQX 超堆被杀 0 次，停压后自愈。

**失败模式由「静默、整批、必须人工」变为「可见、有界、自愈」。**

## 2. 目标与非目标

**目标**：消除楔死；让任何消息丢失都可见且有界；提高 transport 持续投递上限以减少溢出。

**非目标**：不追求零丢失（见第 7 节契约）；不升级 EMQX；不改单活多备拓扑；不实现自动有损恢复。

## 3. 变更一：关闭磁盘持久会话，改用内存持久会话

`deploy/dev/docker-compose.yml` 的 EMQX 服务：

| 配置 | 原值 | 新值 |
|---|---|---|
| `EMQX_DURABLE_SESSIONS__ENABLE` | `true` | `false` |
| `EMQX_MQTT__MAX_MQUEUE_LEN` | 未设（默认 1000） | `${EMQX_MAX_MQUEUE_LEN:-100000}` |
| `EMQX_CLUSTER__DISCOVERY_STRATEGY` / `EMQX_NODE__NAME` | singleton / `emqx@127.0.0.1` | **保留** |
| `EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE` | 256MB | **保留** |

保留固定节点名的原因不变（按容器 IP 命名会在 IP 变化时换数据目录）。注释中「磁盘持久会话」相关说明全部改写。

transport 侧 `cleanStart=false`、`session-expiry-seconds=86400` **不变**——内存持久会话同样依靠这两项为断开的订阅端排队。

### 3.1 mqueue 容量如何定

mqueue 要覆盖 transport 主备切换窗口：选主锁 TTL 15 秒 + 抢锁间隔 2 秒 ≈ **17 秒**。按 1500 条/秒峰值计约需 2.6 万条，取 **100,000** 留足余量。

**必须验证的约束**：mqueue 驻留在会话进程堆内，与 `max_heap_size`（配置 256MB，实际按 1.5 倍即 384MB 触发）**共享同一上限**。100,000 条若平均占用超过约 3.8 KB 即会触发超堆被杀——这正是磁盘会话那个故障的形状。

所幸这件事不必靠一次性人工测量：`GET /api/v5/clients/{clientid}` 直接返回会话进程的 `heap_size`（Erlang 字）。因此

- 实施时压到 mqueue 满载，记录 `heap_size × 8` 与 384MB 的余量并写入报告；余量不足则下调 `MAX_MQUEUE_LEN`，**不上调堆限**
- 第 5 节的监控**持续**采集该字段，超过堆限 70% 即 WARN——把这条约束从一次性验证变成常驻守卫

## 4. 变更二：transport 微批写入总线

### 4.1 依据

内存会话实验中 307,263 条用约 250 秒投完，**持续投递仅约 1,230 条/秒**，低于 3000 条/秒接入速率，导致 mqueue 触顶丢弃 53,552 条。瓶颈是 `MqttProtocolAdapter.Session.deliveryExecutor`——单线程、每条消息一次 `bus.publish()`（一次 Redis `XADD` 往返）。

### 4.2 设计

`MessageBus` 增加批量发布：

```java
/** 批量发布，按入参顺序返回各条目 id。实现以管道方式一次往返提交；允许部分成功。 */
List<PublishResult> publish(List<RawMessage> messages);
```

`PublishResult` 为 `record PublishResult(String id, RuntimeException failure)`，`id` 与 `failure` 恰有一个非空。Redis 管道不是原子的，必须允许逐条区分成败。

`MqttProtocolAdapter` 的投递线程改为攒批：

- 收到消息后入本地队列，满足**条数达到 `conduit.transport.bus.batch-size`（默认 64）** 或 **等待达到 `conduit.transport.bus.batch-delay`（默认 5ms）** 两者之一即提交
- 提交成功的条目各自 `acknowledge()`，属性上报再按原规则发业务回执
- 提交失败的条目**不 ACK**，按现有 `publishWithRetry` 的退避规则整批重试；会话停止时放弃且不 ACK

**保序不变**：仍是单线程、批内按接收顺序，批间按提交顺序。

**PUBACK 延迟**：最坏增加一个 `batch-delay`（5ms）。已确认接受。

**回执语义不变**：业务回执仍在 `XADD` 成功后才发，含义仍是「平台已持久接收」。

**不改**：批大小为 1 时行为与现在等价，可作为回退开关。

## 5. 变更三：经 EMQX HTTP API 检测异常

### 5.1 为什么需要

楔死期间所有既有信号都是绿的。关闭持久会话后楔死本身消失，但 **mqueue 溢出丢弃仍然是真实的数据丢失**，必须有人知道。

### 5.2 设计

transport **仅在主实例**上，每 `conduit.transport.monitor.interval`（默认 30s）调用
`GET {base}/api/v5/clients/{clientId}`。字段名以 EMQX 5.10.5 实际返回为准（已实测核对）：

| 判据 | 字段 | 动作 |
|---|---|---|
| 队列溢出丢弃 | `mqueue_dropped` 相对上次采样增加 | WARN + 指标 `conduit.transport.mqueue_dropped` + 健康明细 |
| 溢出前预警 | `mqueue_len / mqueue_max` ≥ 0.8 | WARN |
| 会话进程内存逼近上限 | `heap_size × 8` ≥ 堆限的 70%（见 3.1） | WARN |
| 投递停滞 | `inflight_cnt == inflight_max` 且 `mqueue_len > 0` 且 **`send_msg`** 连续 `conduit.transport.monitor.stall-samples`（默认 6，约 3 分钟）不变 | **健康检查 DOWN** + ERROR |
| 配置漂移 | `durable` 不为 `false` | WARN（持久会话被意外启用） |
| 批大小超过窗口 | `batch-size > inflight_max` | WARN（批永远攒不满、每批白等满 batch-delay）。**更正：** 原判据为「不相等即告警」、规则为「必须相等」，已被 2026-10-28 实测否定（见 `docs/test-reports/2026-10-28-throughput-ab.md`）；正确规则是 `batch-size ≤ max_inflight` 且窗口为批的若干倍（默认 1024/64），不相等是正确配置。明细同时给出 `inflightToBatchRatio`（窗口/批，即流水线深度） |

投递总数的字段是 **`send_msg`**（不是 CLI 里的 `delivered_msgs`），队列上限是 `mqueue_max`，两者都由 API 直接给出，容量阈值无需另行配置。

「投递停滞」一条是**对本次故障的防御性兜底**：关闭持久会话后预期不再发生，但一旦复现必须能被发现，不再是零信号。

轮询失败（EMQX 不可达、认证失败、字段缺失）**不改变健康状态**，只记 WARN 并保留上次结论——避免监控本身引起误报与抖动。

### 5.3 认证

**EMQX 5 的 REST API 不接受 dashboard 账号密码**（实测返回 401 `BAD_API_KEY_OR_SECRET`），必须用 API key/secret。采用声明式下发，不走控制台手工创建：

- 新增 `deploy/dev/emqx/api-key.bootstrap`，内容为 `${EMQX_API_KEY}:${EMQX_API_SECRET}:viewer`，与现有 `base.hocon.tpl` 一样在启动命令中渲染后只读挂载
- EMQX 侧设 `EMQX_API_KEY__BOOTSTRAP_FILE`（该配置项在 5.10.5 中存在，已实测确认）
- transport 以 Basic 认证使用同一对 key/secret；角色取 **viewer**（只读足够）

配置项：`conduit.transport.monitor.enabled`（默认 true）、`.base-url`、`.api-key`、`.api-secret`、`.interval`、`.stall-samples`。两个包的 `.env` 各增 `EMQX_API_KEY` / `EMQX_API_SECRET`（必须改）。**密钥不得出现在日志中。**

关闭（`enabled=false`）时不轮询，健康检查退回现有 `LeaderHealthIndicator` 行为。

## 6. 变更四：文档与架构同步

| 文件 | 内容 |
|---|---|
| `deploy/dev/.env.example`、`deploy/standalone/.env.example` | EMQX 组增加 `EMQX_API_KEY` / `EMQX_API_SECRET`（必须改）与 `EMQX_MAX_MQUEUE_LEN`；持久会话相关注释改写 |
| `CLAUDE.md` 1.2 | 「transport 单活多备」中磁盘持久会话相关描述改写；新增 mqueue 定容与监控说明；第 4 节追加决定记录 |
| `README.md` | 架构说明中涉及 EMQX 持久化的表述 |
| `docs/images/architecture.svg`、`docs/images/reliable-uplink.svg` | 涉及「EMQX 磁盘持久会话」的标注 |
| `docs/roadmap-saas.md` | V0.3-F 中「去掉磁盘持久会话」条目标记为已在本次实施 |
| `docs/superpowers/specs/2026-10-16-conduit-v0.2r-reliability-design.md`、`2026-10-20-conduit-reliable-uplink-design.md` | 标注本设计取代其中的磁盘持久会话决定 |
| 设备接入契约文档 | 第 7 节的缺口声明 |

## 7. 契约变更：明确声明可靠性缺口

属性上报的顶层 `id` **保持可选**（改为必填是对外契约变更，单独立项）。本次在契约中明确写入：

> 不携带 `id` 的上报只有逐跳的 QoS 1 保证。在 EMQX 进程重启或崩溃时，已 PUBACK 但尚未投递给平台的消息会丢失；在接入速率持续超过平台投递能力时，超出 broker 队列容量的消息会被丢弃并计入 `dropped_msgs`。需要端到端不丢的设备必须携带 `id` 并实现本地持久化待确认队列，收到平台业务回执后才删除。

这不是新增缺口，是把既有事实写明——此前依赖磁盘持久会话兜底，而实测证明它兜不住。

## 8. 测试

| 层次 | 内容 |
|---|---|
| 单元 | `MessageBus.publish(List)` 的部分成功、顺序、空批；微批的条数触发、超时触发、失败不 ACK、会话停止时放弃；监控的三条判据、轮询失败不改健康状态 |
| 集成 | Testcontainers 中批量发布的条目顺序与 id；监控对伪造 EMQX 响应的判定 |
| e2e | 现有五个场景全部重跑（杀 server / 杀主 transport / 杀全部 transport / 重启 EMQX / 可靠上报途中强杀 EMQX）。**重启 EMQX 场景的预期会变**：内存会话下未投递消息不再保留，该场景须改为断言「带 `id` 的设备经重发不丢」，不带 `id` 的不再断言不丢 |
| 压测 | 压测机复跑 3000 条/秒 × 180 秒，断言：无投递停滞、停压后积压归零、超堆被杀 0 次；记录微批前后的持续投递速率对比与 mqueue 满载时的会话进程内存 |

## 9. 风险

| 风险 | 处置 |
|---|---|
| mqueue 100,000 条触发会话进程超堆 | 实施时实测内存余量，不足则下调容量（第 3.1 节） |
| 微批部分成功导致重复入库 | 流水线本就幂等（同 ts 覆盖、最新值按 ts 比较）；逐条区分成败避免整批重发 |
| 微批引入乱序 | 单线程 + 批内保序 + 批间顺序提交；由单元测试固定 |
| 监控引入 EMQX API 依赖 | 轮询失败不改健康状态；可整体关闭 |
| API key 以 bootstrap 文件下发，密钥进入编排与 `.env` | 角色取只读 viewer；`.env` 已在 `.gitignore`；模板只给占位值 |
| e2e「重启 EMQX」场景语义变化 | 第 8 节已明确改写方向，属预期内的契约变化 |
| 关闭持久会话后 EMQX 重启丢数据 | 第 7 节契约声明；带 `id` 的设备由重发覆盖 |

## 10. 明确不做

- 不自动执行有损恢复（丢弃会话必须由人决定）
- 不升级 EMQX 6.x、不启用持久共享订阅、不取消 transport 选主（留在 V0.3-F / V0.8 评估）
- 不把设备契约的 `id` 改为必填
- 不标定 DS 超堆阈值与积压量的定量关系（磁盘持久会话关闭后该路径不再使用）
