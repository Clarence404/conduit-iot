# transport 身份池多活设计（2026-11-17，11-18 修订）

- **状态**：待所有者审阅。
- **决定来源**（所有者 2026-11-17 ～ 11-18）：
  - 保留 transport，改为横向扩展，并且以后要能无限扩展。
  - 下游不依赖消息顺序，乱序可以接受。
  - 本次只做 transport 多活；总线分区、Redis 集群、改用 MQ 另立项。
- **背景**：
  - 「单活多备 + 选主」是历史包袱：2026-10-16 为了用磁盘持久会话而被迫采用，当时磁盘会话不支持共享订阅；这个前提在 10-28 和 10-29 已经不存在。
  - EMQX 直写方案的验证见 [`2026-11-17-transport-necessity.md`](../../test-reports/2026-11-17-transport-necessity.md)。
  - 路线图见 `roadmap-saas.md` 第 2 节架构短板第 1 项。
- **11-18 修订**：按讨论结论改了以下几点，审阅时请逐项确认。
  1. 身份池大小 K 改为在线可调（初始 64），实例数没有硬上限。
  2. 共享订阅的分发策略由 `hash_clientid` 改为轮询（`round_robin`）。
  3. 同一实例内各连接的批，合并后再提交，从「视压测决定」改为本次必做。
  4. 实例数逼近 K 时先告警，由人手动扩大 K；自动翻倍以后再做。
  5. 缩小 K 放到第二步。

## 1. 目标与不做的事

**目标**
- 所有 transport 实例同时干活，加实例就能分担 EMQX 投递与写总线的负载。
- 实例增减、宕机时自动重新分配，不需要人工操作，也不丢消息。
- 实例数没有硬上限：实例数逼近身份池大小 K 时，在线调大 K 即可，不停机、不搬数据。

**不做**
- 不做总线分区、Redis 集群、MQ：总线仍是一个 Redis Streams 流。
- 不改接入协议、鉴权、ACL、可靠上报约定。
- 不支持新旧版本混跑的滚动升级（见第 7 节）。
- 本次不做缩小 K（见第 4 节）。

## 2. 核心思路：身份池

**什么是身份池**
- 身份池是 K 个大家都知道名字的 MQTT 身份（clientId）：`conduit-transport`、`conduit-transport-1` … `conduit-transport-{K-1}`。
- 每个身份是共享订阅组 `$share/conduit-transport/...` 的一个成员，对应 EMQX 上的一个内存持久会话。
- 身份只是连接与会话，不承载数据。

**为什么身份要从池里取，而不是每个实例自己起名**
- 这和顺序无关，原因是**掉线成员的消息会卡住**：EMQX 共享订阅里，成员掉线后，派给它的消息继续排进它的会话，不会改派给别的成员。
- 所以实例宕机后，必须有别的实例用**同一个 clientId** 把会话接过去。如果每个实例都用自己独有的名字，宕机实例那一份消息会一直卡在 EMQX 里，直到会话过期被丢弃。

**实例和身份的关系**
- 一个实例就是「当前持有哪些身份」。
- 加减实例时，身份在实例之间迁移，不用重新分片，也不用搬数据。

**为什么 K 可以在线调大**
- 身份里没有数据，K 变了，只是 EMQX 按新的成员数重新分配设备。
- 下游不依赖顺序：最新值按 ts 比较，InfluxDB 按（设备, ts）幂等写入，在线状态有 `since` 守护。所以重新分配时的短暂乱序不影响数据。

**分发策略改为轮询**
- 原来用 `hash_clientid`，是为了让同一台设备固定走同一条连接、保持顺序。现在不需要顺序了，改用 `round_robin`，各连接负载更均匀，个别高频设备也不会压满某一条连接。
- 配置项：`EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY`。
- 压测时和 `hash_clientid` 对比一次再定稿。

## 3. 身份分配

### 3.1 Redis 键

| 键 | 类型 | 内容 |
|---|---|---|
| `conduit:transport:pool-size` | string | 身份池大小 K。不存在时取配置默认值 64。只允许调大（第一步） |
| `conduit:transport:id:{i}` | string，带 TTL | 持有者的 instanceId。抢占、续期、释放都用比对 id 的 Lua 脚本，沿用现有选主锁的写法 |
| `conduit:transport:members` | zset | 成员为 instanceId，分数为最近一次心跳的毫秒数。时间取 Redis 服务端时钟（`TIME`），避免各机器时钟不一致 |

### 3.2 节奏与参数

参数前缀 `conduit.transport.allocation`：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `initial-pool-size` | 64 | Redis 里没有 `pool-size` 时使用的初始 K |
| `lease-ttl` | 15s | 身份租约有效期，与现有选主锁一致 |
| `renew-interval` | 5s | 续期间隔 |
| `tick-interval` | 2s | 抢占与再平衡的检查间隔 |
| `member-ttl` | 15s | 心跳超过这个时长，视为实例下线 |
| `rebalance-stable` | 10s | 成员列表要稳定这么久，才让出多余身份，避免实例刚启动或短暂抖动时来回迁移 |
| `pool-alert-ratio` | 0.5 | 存活实例数超过 K 的这个比例时告警，提示调大 K |

### 3.3 每次检查做什么

1. 写心跳，读出存活实例列表（按 id 字典序排列，长度为 N）和当前的 K。
2. 计算本实例的配额：本实例在列表中的位置为 p，`quota = ⌊K / N⌋ + (p < K mod N ? 1 : 0)`。各实例配额合计恰为 K、相差不超过 1，所以全部身份都有人持有；若只以 ⌈K / N⌉ 为上限，会停在 6/6/4 这类不均的状态。
3. 续期自己持有的全部身份。续期失败（被别人占了或 Redis 出错）的身份，立即停止对应的适配器。安全要求与现在的下台规则相同：宁可短暂没人接，也不能让两个实例同时连同一个 clientId。
4. 持有数少于 `quota`：按本实例的偏好顺序抢占空闲身份，每次检查最多抢 `quota − 持有数` 个；每抢到一个，就启动对应的适配器。
5. 持有数多于 `quota`，且成员列表已稳定 `rebalance-stable`：按偏好顺序从后往前，每次让出一个（见 3.4），给其他实例接手的时间。按列表而非人数判断稳定，因为一个实例下线、另一个同时加入时人数不变，位置却变了。

**偏好顺序**：按 `hash(instanceId, i)` 排序（rendezvous hash），每个实例尽量拿回自己偏好的那批身份，减少不必要的迁移。

**身份 #0 的特殊职责**：订阅上下线事件 `$SYS/...`，以及从单连接升级时退订旧的非共享过滤器。这些职责跟着身份走，谁持有 #0 谁负责，沿用现有逻辑。

### 3.4 让出身份

1. 停止该适配器的拉取。
2. 断开连接。
3. 释放租约（比对 id 后删除）。

已投递给本实例、但还没回 PUBACK 的消息，会在会话被接管后由 EMQX 重新投递，不会丢，只可能出现少量重复；写入按（设备, ts）幂等，重复不影响数据。批的攒批等待只有 5 ms，所以不另做排空等待；如果实测重复偏多，再加「等待在途批写完」这一步。

### 3.5 宕机与网络问题

- **实例宕机**：它的租约在 15 秒内到期，其他实例在下一次检查时抢到。等待窗口和现在的主备切换相同，期间消息排在 EMQX 的会话队列里。
- **Redis 不可用**：所有实例续期都失败，全部停止适配器，消息排在 EMQX。总线本来就在 Redis 上，Redis 不可用时本来也写不进去，这一点和现状相同。
- **实例与 EMQX 断开、但能连 Redis**：适配器报告需要重连，先续期确认仍持有该身份，再重连。沿用现有 `superviseTick` 的做法，只是改为按身份处理。

## 4. 调整身份池大小

**调大（本次实现）**
- 运维修改 Redis 键 `conduit:transport:pool-size`（提供脚本或管理接口），各实例在下一次检查时读到新的 K。
- 新增的身份由各实例按配额抢占；每个新身份第一次连接时，EMQX 按 transport zone 的参数创建会话，自动加入共享组。
- 共享组成员数变化后，EMQX 按新的成员数分发，不用搬数据。
- **告警**：存活实例数超过 `K × pool-alert-ratio` 时记 WARN，并暴露指标，提示调大 K。自动翻倍以后再做。

**缩小（第二步，本次不做）**
- 要先把多出的身份「排空」：停止拉取，等会话队列清空，再用会话不保留的方式断开，把它从共享组里移除；否则这些会话会继续收到消息、卡在 EMQX 里。

## 5. 同一实例内的批合并（本次必做）

- K 变大后，流量分散到更多连接上，每条连接各自攒的批会变小，写 Redis 的次数随之增加。
- 改为每个实例只有一个提交器：本实例持有的所有连接收到的消息，进入同一个攒批队列，按 `batch-size` 和 `batch-delay` 一起 `XADD`。写入成功后，再分别回到各自的连接回 PUBACK、发业务回执。
- 约束保持不变：`batch-size ≤ max_inflight ≪ max_mailbox_size`。`max_inflight` 是每条连接的在途窗口，合并之后，一个批里的消息可能来自多条连接，各连接的在途数仍各自受自己的窗口限制。

## 6. 代码改动

| 位置 | 改动 |
|---|---|
| `allocation/`（取代 `leader/` 包） | 新增 `IdentityAllocator`（替代 `LeaderElection`）、`IdentityLeaseStore`（Redis 实现，含 Lua）、`AllocationProperties`。删除 `LeaderElection`、`RedisLeaderLock`、`LeaderLock`、`LeaderProperties`；不再使用选主锁键 `conduit:transport:leader` |
| `TransportApplication` | 按当前 K 创建适配器；K 调大时再追加新的适配器；启停改由 `IdentityAllocator` 按身份控制 |
| `MqttTransportProperties` | `connections` 改名为「初始身份池大小」，由 `allocation.initial-pool-size` 取代，保留旧键作为兼容别名。K = 1 仍是回退开关：只有一个身份、普通订阅，由字典序最小的存活实例持有（id 更小的实例后加入时，成员稳定后交接一次），等价于单活；此时忽略 Redis 中的其他值，从 K = 1 改回多身份前要先删除 `conduit:transport:pool-size` |
| `MqttProtocolAdapter` | 启停仍由 `ProtocolAdapter.start(stillValid)` / `stop()` 驱动，`stillValid` 改为「本实例仍持有该身份」。攒批逻辑移到实例级的共享提交器（第 5 节） |
| 健康检查 | `LeaderHealthIndicator` 改为 `AllocationHealthIndicator`，明细包括 `instanceId`、`held`（持有身份数）、`members`（存活实例数）、`poolSize`、`connected`（持有的身份是否都已连上） |
| 会话监控 | `SessionMonitorGroup` 只监控本实例持有的身份；「投递停滞判 DOWN」只针对自己持有的身份 |
| 指标 | 新增 `conduit.transport.identities.held`、`conduit.transport.pool.size`、`conduit.transport.members`（仪表），以及 `conduit.transport.identities.moved`（计数，带 `reason=acquire\|release\|lost`） |
| 部署 | EMQX 的 transport zone 单连接会话队列由 10 万改为 1.5 万（`EMQX_TRANSPORT_MAX_MQUEUE_LEN`），64 × 1.5 万 ≈ 96 万，总缓冲与现在持平；信箱上限保持 20 万；分发策略改为 `round_robin`。standalone 与 split 保留两个 transport 服务，从此两者同时工作 |

## 7. 容量与资源

- **EMQX**：64 条订阅连接，每条最坏约占 15,000 × 1.2 KB ≈ 18 MB 堆，全部积压到上限时约 1.2 GB，与现在 8 × 10 万的上限持平。K 调大后，按「K × 单连接队列」估算总缓冲；需要时同步调小单连接队列，保持总量不变。
- **transport**：每个实例持有 ⌊K / N⌋ 或 ⌈K / N⌉ 条连接（按 3.3 的配额）。HiveMQ 客户端的连接开销小，现有 512 MB 内存上限够用，以压测为准。

## 8. 升级

- **不支持新旧混跑**：旧版靠一把选主锁持有全部身份，新版按身份租约分配，两者同时运行会互相踢掉对方的连接。
- **升级步骤**：先停掉所有旧版 transport，再启动新版；中间的空档，消息排在 EMQX 的会话队列里。
- **身份数从 8 变为 64**：原有 8 个会话被接管后，队列上限仍是旧值，需要按 `deploy/split/README.md` 的做法丢弃一次旧会话；新增的 56 个身份直接按新参数创建。分发策略的改动需要重建 EMQX 才生效。
- 部署文档写明以上步骤。

## 9. 验收

1. **单元测试**（先写失败用例）：
   - 配额计算：按位置分配、合计恰为 K，覆盖 N = 1、N 不整除 K、N > K、K = 1；多个分配器共用一份内存 Redis 时收敛到各实例相差不超过 1 且全部身份有人持有（含实例崩溃与重启）；
   - 偏好顺序稳定；
   - 抢占与让出受配额约束，成员列表稳定后才让出；
   - 续期失败立即停止对应的适配器；
   - 身份 #0 的职责跟着持有者走；
   - K 调大后各实例抢占新身份；
   - 实例数超过阈值时告警；
   - 合并提交器在批写成功后，把 PUBACK 分别回到各自的连接。
2. **集成测试**（Testcontainers Redis）：租约 Lua 的抢占、续期、比对后释放；心跳与存活判定，使用 Redis 服务端时钟。
3. **e2e**（Testcontainers EMQX，本地起 3 个 transport 进程）：
   - 现有 6 个场景照常通过，其中「杀主 transport」改为「杀任一 transport」；
   - 新增场景：负载下从 1 个实例扩到 3 个；从 3 个缩到 1 个；同时杀 2 个；负载下把 K 从 16 调到 32；
   - 断言：计数恰好、无死信、无待确认；最终全部身份都有人持有，各实例持有数相差不超过 1。
4. **压测**（拆分部署）：
   - 2 个 transport 同时工作，对比单活时的吞吐与 CPU；
   - 2.5 万台断网补传，计数守恒；
   - 负载下强杀一个 transport，不丢数；
   - `round_robin` 与 `hash_clientid` 各跑一次对比。
5. **文档**：
   - README、CLAUDE.md（transport 章节与决定记录）、`architecture.svg`（「Redis 选主 · 主实例 8 条连接」改为「身份池 · 多活」），检查 `module-dependencies.svg`；
   - `deploy/split/README.md` 写升级步骤和调大 K 的方法；
   - 测试报告。
