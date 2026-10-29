# transport 多连接共享订阅与流水线减负 实施计划

> 对应 spec：[`../specs/2026-10-29-conduit-transport-shared-subscription-design.md`](../specs/2026-10-29-conduit-transport-shared-subscription-design.md)
> 状态：**已实施并在压测 VM 上验证**（2026-10-29）。本计划为事后整理，记录实际的步骤、顺序与验证方式。

**目标**：在压测 VM 上求不崩溃的最大吞吐（所有者要求）。

**架构**：保留 transport 单活多备选主；主实例从 1 条连接改为 K 条共享订阅连接，拆掉 EMQX 单连接进程的单核天花板。
随后 Redis 成为次级瓶颈，以同批合并最新值更新为其减负。

## 全局约束

- 每一步先**回读运行中的实际配置**再测量，不以仓库或文档为准（当天 VM 部署落后于仓库，导致过一次错误归因）。
- 验收只认守恒判据：客户端 publish 数、PUBACK 数、EMQX 接收、流水线入库、**InfluxDB 实际行数**五口径相等，且 EMQX 丢弃、死信、强杀均为 0。
- 每档至少重复两次；单次通过不算数。
- 一次只改一个变量，否则收益无法归属。
- 改动可靠性路径前，先在压测机上实测其安全性前提。

## 任务 1：确认瓶颈位置（证据，而非推断）

- [x] 用 EMQX 自带 `recon` 在 9,000 条/秒下采样调度器占用与进程 reductions
- [x] 确认 transport 连接进程约为单个设备连接的 350 倍、顶在单调度器上限

## 任务 2：快速验证（不进仓库）

- [x] 停 transport、踢掉其会话，4 个只回 PUBACK 的探测订阅者测纯 EMQX 投递：14,000 条/秒 100% 收到
- [x] 安全性：持久会话共享组全员离线后重连补收全部；1 个离线时其会话排队、重连补收
- [x] 发现：transport 超级用户鉴权要求 clientId 以 `conduit-` 开头

## 任务 3：transport 多连接（提交 `65ad9a2`）

**文件**：`MqttTransportProperties`（`connections`、`sharedGroup`、`clientIdOf`、`shared`）、`MqttProtocolAdapter`（按序号构造、
`deviceTopicFilter`、`subscribesPresence`、`retireLegacySubscription`）、`TransportApplication`（`MqttConnections` 持有者）、
`SessionMonitor`（`client` 标签）、新增 `SessionMonitorGroup`、`LeaderHealthIndicator`、`application.yml`、两份 compose。

- [x] 第 0 条沿用旧 clientId 接管旧会话，退订旧的非共享过滤器（NO_SUBSCRIPTIONS_EXISTED 视为成功）
- [x] 上下线事件只由第 0 条订阅
- [x] `connections=1` 与改动前完全一致（回退开关）
- [x] EMQX `mqtt.shared_subscription_strategy = hash_clientid`（回读确认；键不在 `broker` 下）
- [x] 会话监控按连接拆分，任一停滞即 DOWN
- [x] 新增 `MultiConnectionTest`、`SessionMonitorGroupTest`；变异验证摘掉 `client` 标签时测试失败
- [x] 部署顺序刻意让旧版先建立带非共享订阅的旧会话，再换新版：EMQX 订阅表确认旧过滤器已退订

## 任务 4：最新值同批合并（提交 `141bdd6`）

- [x] Redis 实测（12,000 条/秒）：单线程峰值 91%，evalsha 占 58.9%
- [x] `LatestCoalescer`：只在批量路径合并，逐条回退不合并，被剔除者照常 ACK
- [x] 参考模型 2,000 次随机等价对照；变异验证（去子集判断 / 去 ts 比较分别 1,434 / 1,206 个失败）
- [x] 更新 `PipelineProcessorTest` 两处旧条数断言，ACK 断言不变

## 任务 5：验收

- [x] 容量阶梯：10,000 通过、13,000 通过、16,000 失败（VM CPU 96～97%）
- [x] 13,000 重复：3 次 2 过 → 边缘
- [x] 主备切换：10,000 条/秒下 `docker kill` 主 transport，InfluxDB 行数 = 实发，零丢失
- [x] 11,500 重复两次：均通过 → 可靠无损上限 11,500
- [ ] `MqttProtocolAdapterIT`、`ReliabilityE2EIT`、`LatestValueStoreIT` 需要 Docker，未运行（所有者决定不跟进）

## 任务 6：文档

- [x] `CLAUDE.md` 第 1.1 / 1.2 节与决定记录；更正当日一条依据错误配置值的记录
- [x] `README.md` transport 一节
- [x] `docs/images/architecture.svg`（实测文字包围盒无压线）
- [x] 测试报告 [`../../test-reports/2026-10-29-vm-max-throughput.md`](../../test-reports/2026-10-29-vm-max-throughput.md)
- [x] 后续事项写入 `docs/backlog-v0.2.md`

## 未做（见 backlog）

- 把 VM 上验证过的配置（信箱 10000、inflight 1024、transport 批 512）写成仓库默认值——所有者决定暂缓
- 信箱 1000 与共享订阅组合未测
- 海量连接（而非海量消息）的容量未测
