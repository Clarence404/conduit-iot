# transport 身份池多活设计（2026-11-17）

- **状态**：设计已获所有者确认，待审阅 spec。
- **决定来源**（所有者 2026-11-17）：
  - 保留 transport，改为横向扩展。
  - 身份池取 K = 64，以留出扩展余量。
  - 这次只做 transport 多活；总线分区和 Redis 集群另立项。
- **背景**：
  - 「单活多备 + 选主」是历史包袱。2026-10-16 为了用磁盘持久会话被迫采用，因为当时的磁盘持久会话不支持共享订阅；这个前提在 10-28、10-29 已经消失。
  - EMQX 直写的验证见 [`2026-11-17-transport-necessity.md`](../../test-reports/2026-11-17-transport-necessity.md)。
  - 路线图见 `roadmap-saas.md` 第 2 节架构短板第 1 项。

## 1. 目标与不做的事

**目标**
- 所有 transport 实例同时干活，加实例就能分担 EMQX 投递与写总线的负载。
- 实例增减、宕机时自动重新分配，不需要人工操作，也不丢消息。
- 上限：最多 64 个实例同时工作。

**不做**
- 不做总线分区、Redis 集群、Kafka，总线仍是一个 Redis Streams 流。
- 不改接入协议、鉴权、ACL、可靠上报约定。
- 不支持新旧版本混跑的滚动升级（见第 6 节）。

## 2. 核心思路：固定身份池

**身份池**
- 身份池是固定的 K 个 MQTT 身份（clientId）：`conduit-transport`、`conduit-transport-1` … `conduit-transport-63`。
- 每个身份是共享订阅组 `$share/conduit-transport/...` 的一个成员，对应 EMQX 上的一个内存持久会话。
- 身份只是连接与会话，不承载数据。

**为什么 K 必须固定**
- EMQX 的 `hash_clientid` 是对组成员数取模，把每台设备固定分给某个成员。
- 成员数不变，设备的归属就不变。
- 成员掉线时，派给它的消息排进它的会话，不会改派给别人。所以掉线实例的身份必须由别的实例用同一个 clientId 接管。

**实例与身份的关系**
- 实例只是「当前持有哪些身份」。
- 加减实例时，只是身份在实例之间迁移；不用重新分片，不用搬数据。

## 3. 身份分配

### 3.1 Redis 键

| 键 | 类型 | 内容 |
|---|---|---|
| `conduit:transport:id:{i}` | string，带 TTL | 持有者的 instanceId。抢占、续期、释放都用比对 id 的 Lua 脚本，沿用现有选主锁的写法 |
| `conduit:transport:members` | zset | 成员为 instanceId，分数为最近一次心跳的毫秒数。时间取 Redis 服务端时钟（`TIME`），避免各机器时钟不一致 |

### 3.2 节奏与参数

前缀为 `conduit.transport.allocation`：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `lease-ttl` | 15s | 身份租约有效期，与现有选主锁一致 |
| `renew-interval` | 5s | 续期间隔 |
| `tick-interval` | 2s | 抢占与再平衡的检查间隔 |
| `member-ttl` | 15s | 心跳超过该时长视为实例下线 |
| `rebalance-stable` | 10s | 成员数稳定这么久才让出多余身份，防止实例刚起来或短暂抖动时来回迁移 |

### 3.3 每次检查做什么

1. 写心跳，读出存活实例数 N。
2. 计算本实例应持有的数量：`share = ⌈K / N⌉`。
3. 续期自己持有的全部身份。续期失败（被别人占了，或 Redis 出错）的身份，立即停止对应的适配器。安全性与现在的下台规则相同：宁可短暂没人接，也不让两个实例同时连同一个 clientId。
4. 持有数少于 `share` 时：按本实例的偏好顺序抢占空闲身份，每次检查最多抢 `share − 持有数` 个。抢到一个，就启动一个对应的适配器。
5. 持有数多于 `share`，且成员数已稳定 `rebalance-stable`：按偏好顺序从后往前，每次让出一个（见 3.4），给其他实例接手的时间。

**偏好顺序**：按 `hash(instanceId, i)` 排序（rendezvous hash）。每个实例尽量拿回它偏好的那批身份，减少不必要的迁移。

**身份 #0 的特殊职责**：订阅上下线事件 `$SYS/...`，并在从单连接升级时退订旧的非共享过滤器。这些职责跟着身份走，谁持有 #0 谁负责，沿用现有逻辑。

### 3.4 让出身份

1. 停止该适配器的拉取。
2. 断开连接。
3. 释放租约，用比对 id 的方式删除。

已投递给本实例、但还没回 PUBACK 的消息，会在会话被接管后由 EMQX 重新投递，所以不会丢，只可能出现少量重复；写入按（设备, ts）幂等，重复不影响数据。

在途批的等待时间只有一个批的延迟（默认 5 ms 攒批），所以不另做排空等待。如果实测重复偏多，再加「等待在途批写完」这一步。

### 3.5 宕机与网络问题

- **实例宕机**：它的租约在 15 秒内到期，其他实例在下次检查时抢到，等待窗口和现在的主备切换相同。期间消息排在 EMQX 的会话队列里。
- **Redis 不可用**：所有实例续期都失败，全部停止适配器，消息排在 EMQX。总线本身就在 Redis 上，Redis 不可用时本来也写不进去，这一点和现状相同。
- **实例与 EMQX 断开但能连 Redis**：适配器报告需要重连，先续期确认仍持有，再重连，沿用现有 `superviseTick` 的做法，只是改成按身份处理。

## 4. 代码改动

| 位置 | 改动 |
|---|---|
| `leader/` | 新增 `IdentityAllocator`（取代 `LeaderElection`）、`IdentityLeaseStore`（Redis 实现，含 Lua）、`AllocationProperties`。`LeaderElection`、`RedisLeaderLock`、`LeaderLock`、`LeaderProperties` 删除；选主锁键 `conduit:transport:leader` 不再使用 |
| `TransportApplication` | 仍按 `connections`（即 K）创建 K 个 `MqttProtocolAdapter`，改为交给 `IdentityAllocator` 按身份启停 |
| `MqttTransportProperties` | `connections` 默认值由 8 改为 64，含义改为「身份池大小」。`connections = 1` 仍是回退开关：只有一个身份，按普通订阅工作，谁抢到谁干活，等价于单活 |
| `MqttProtocolAdapter` | 基本不动。启停仍由 `ProtocolAdapter.start(stillValid)` / `stop()` 驱动，`stillValid` 改为「本实例仍持有该身份」 |
| 健康检查 | `LeaderHealthIndicator` 改为 `AllocationHealthIndicator`，明细为 `instanceId`、`held`（持有身份数）、`members`（存活实例数）、`connected`（持有的身份是否都已连上） |
| 会话监控 | `SessionMonitorGroup` 只监控本实例持有的身份；「投递停滞判 DOWN」只针对自己持有的身份 |
| 指标 | 新增 `conduit.transport.identities.held`（仪表）、`conduit.transport.identities.moved`（计数，带 `reason=acquire\|release\|lost`） |
| 部署 | EMQX 的 transport zone 单连接会话队列由 10 万改为 1.5 万（`EMQX_TRANSPORT_MAX_MQUEUE_LEN`），64 × 1.5 万 ≈ 96 万，总缓冲与现在持平；信箱上限保持 20 万；`TRANSPORT_MQTT_CONNECTIONS` 默认 64。standalone 与 split 保留两个 transport 服务，从此两者同时工作 |

## 5. 容量与资源

- **EMQX**：64 条订阅连接，每条最坏占堆约 15,000 × 1.2 KB ≈ 18 MB。全部积压到上限时约 1.2 GB，与现在 8 × 10 万的上限持平。
- **transport**：每个实例最多持有 64 条连接。HiveMQ 客户端的连接开销小，现有 512 MB 内存上限够用，以压测结果为准。
- **攒批变小**：身份多了，流量分到更多连接上，每条连接的批会变小，写 Redis 的次数会增加。如果压测显示 `XADD` 次数成为瓶颈，再把同一实例内各连接的批合并后提交。这一项先不做，以压测结果决定。

## 6. 升级

- **不支持新旧混跑**：旧版靠单一选主锁持有全部身份，新版按身份租约，两者同时运行会互相踢连接。
- **升级步骤**：先停掉所有旧版 transport，再启动新版。中间的空档，消息排在 EMQX 会话队列里。
- **身份数从 8 变为 64**：设备到身份的映射会重排一次。下游不依赖顺序，切换时的短暂乱序可以接受。原有 8 个会话被接管后，队列上限仍是旧值，需要按 `deploy/split/README.md` 的做法丢弃一次旧会话；新增的 56 个身份直接按新参数创建。
- 部署文档写明以上步骤。

## 7. 验收

1. **单元测试**（先写失败用例）：
   - 份额计算：N = 1、2、3、64、65；
   - 偏好顺序稳定；
   - 抢占与让出：受份额约束，成员数稳定后才让出；
   - 续期失败时立即停止对应适配器；
   - 身份 #0 的职责跟着持有者走。
2. **集成测试**（Testcontainers Redis）：租约 Lua 的抢占、续期、比对释放；心跳与存活判定，使用 Redis 服务端时钟。
3. **e2e**（Testcontainers EMQX，本地起 3 个 transport 进程）：
   - 现有 6 个场景照常通过，其中「杀主 transport」改为「杀任一 transport」；
   - 新增：负载下从 1 个实例扩到 3 个；从 3 个缩到 1 个；同时杀 2 个；
   - 断言：计数恰好、无死信、无待确认，最终 64 个身份全部有人持有，且各实例持有数相差不超过 1。
4. **压测**（拆分部署）：
   - 2 个 transport 同时工作，对比单活时的吞吐与 CPU；
   - 2.5 万台断网补传守恒；
   - 负载下强杀一个 transport 不丢数。
5. **文档**：
   - README、CLAUDE.md（transport 章节与决定记录）、`architecture.svg`（「Redis 选主 · 主实例 8 条连接」改为「身份池 64 · 多活」）、`module-dependencies.svg`（检查后视情况修改）；
   - `deploy/split/README.md` 写升级步骤；
   - 测试报告。
