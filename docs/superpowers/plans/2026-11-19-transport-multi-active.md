# transport 身份池多活 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 transport 从「单活多备 + 选主」改为「身份池多活」：所有实例按 Redis 租约分摊 K 个共享订阅身份，K 可在线调大，同一实例内各连接的消息合并成批写总线。

**Architecture:**
- 新增 `IdentityAllocator`，取代 `LeaderElection`。它周期性地做三件事：写心跳；按 ⌈K/N⌉ 抢占或让出身份；按身份启停对应的 `MqttProtocolAdapter`。
- 租约、心跳、K 都存在 Redis，读写由 `IdentityLeaseStore` 的 Lua 实现完成。
- 适配器不再各自攒批，改为把消息交给实例级的 `SharedBatcher`。批写入总线成功后，按原顺序回调各连接，先发业务回执，再回 PUBACK。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Data Redis（Lua 脚本）、HiveMQ MQTT Client、JUnit 5 + AssertJ + Mockito、Testcontainers（`TestBroker`）。

**Spec:** `docs/superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md`（11-18 修订版）

## Global Constraints

- 身份 clientId 沿用 `MqttTransportProperties.clientIdOf(i)`：第 0 个为 `conduit-transport`，第 i 个为 `conduit-transport-i`，**必须保留 `conduit-` 前缀**（transport 超级用户鉴权依赖它）。
- Redis 键：
  - `conduit:transport:pool-size`（K）；
  - `conduit:transport:id:{i}`（租约，值为 instanceId，带 TTL）；
  - `conduit:transport:members`（zset，score 为毫秒，时间取 Redis 服务端 `TIME`）。
- 分配参数，前缀 `conduit.transport.allocation`，默认值：`initial-pool-size` 64、`lease-ttl` 15s、`renew-interval` 5s、`tick-interval` 2s、`member-ttl` 15s、`rebalance-stable` 10s、`pool-alert-ratio` 0.5。
- 份额 `share = ceil(K / N)`。偏好顺序为 rendezvous hash `hash(instanceId, i)`，降序。
- K 只允许调大；Redis 里的值小于当前 K 时，忽略并记 WARN。
- 回退开关：`initial-pool-size = 1` 时只有一个身份，使用普通订阅，等价于单活。共享订阅与否在启动时按 `initial-pool-size > 1` 确定；从 1 调大不受支持，记 WARN 并忽略。
- 约束保持不变：`batch-size ≤ max_inflight ≪ max_mailbox_size`。
- 先写失败测试（TDD）。每个类写中文 Javadoc，重点方法写注释（CLAUDE.md 2.1）。
- 日志：首次失败 WARN、后续 DEBUG、角色或身份变化 INFO；不记口令、令牌。
- 提交遵循 Conventional Commits，不加任何 AI 署名行。
- 本机 shell 默认 JDK 不是 21：`export JAVA_HOME=/c/Users/ChenHan/.jdks/ms-21.0.8`。
- 集成测试和 e2e 需要 Docker，本机没有。在 129 上用 `maven:3.9.9-eclipse-temurin-21` 容器跑：host 网络，挂载 `/var/run/docker.sock` 与 `~/.docker-java.properties`（内容为 `api.version=1.44`）。做法见 `docs/test-reports/2026-11-16-transport-internal-listener.md` 第 2 节。

## Review Focus

1. **Redis 里的 K 被人改小**：分配器忽略、保持当前 K、记 WARN，不得停掉已持有的身份。→ Task 3 的 `poolSizeDecreaseIsIgnored`。
2. **已死实例的心跳残留**：超过 `member-ttl` 的成员不计入 N，否则份额永远偏小，有身份没人接。→ Task 2 的 `staleMembersAreNotCounted`。
3. **总线不可用时让出身份**：批提交器阻塞重试期间，被让出的身份的条目不得被 ack；`stop()` 不得等待批提交器而卡住。→ Task 4 的 `itemsOfStoppedSessionsAreDroppedNotAcked`、`stopDoesNotWaitForABlockedBatch`。
4. **续期结果模糊**：Redis 超时导致续期结果未知时按失败处理，立即停掉对应适配器，宁可短暂没人接，也不能双持。→ Task 3 的 `renewErrorStopsTheAdapter`。
5. **同一连接的 PUBACK 顺序**：合批之后，同一连接的回执和 PUBACK 必须仍按到达顺序发出。→ Task 4 的 `callbacksPreserveArrivalOrderPerConnection`。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| 新建 `conduit-transport/src/main/java/dev/conduit/transport/allocation/AllocationProperties.java` | 分配参数 record 与校验 |
| 新建 `.../allocation/IdentityShares.java` | 纯函数：份额计算、偏好顺序、告警判断 |
| 新建 `.../allocation/IdentityLeaseStore.java` | 接口：租约、心跳、K 的读写 |
| 新建 `.../allocation/RedisIdentityLeaseStore.java` | Redis 实现（Lua） |
| 新建 `.../allocation/IdentityAllocator.java` | SmartLifecycle：每轮检查、按身份启停适配器、指标 |
| 新建 `.../allocation/AllocationHealthIndicator.java` | 健康检查明细 |
| 新建 `.../mqtt/SharedBatcher.java` | 实例级合批提交器 |
| 修改 `.../mqtt/MqttProtocolAdapter.java` | 去掉会话内攒批与 `flushTimer`，改为提交给 `SharedBatcher`；index 只校验非负 |
| 修改 `.../mqtt/MqttTransportProperties.java` | `connections` 改为兼容别名，由 `allocation.initial-pool-size` 取代；`shared()` 改由初始 K 决定 |
| 修改 `.../TransportApplication.java` | 装配新组件；会话监控只采样已持有的身份 |
| 修改 `.../monitor/SessionMonitorGroup.java` | 新增 `sample(Set<String> clientIds)`，只采样指定身份 |
| 删除 `.../leader/*`（`LeaderElection`、`LeaderLock`、`RedisLeaderLock`、`LeaderProperties`、`LeaderHealthIndicator`）及对应测试 | 被 allocation 包取代 |
| 修改 `conduit-transport/src/main/resources/application.yml` | 新参数；删除 `conduit.transport.leader.*` |
| 修改 `deploy/dev/docker-compose.yml`、`deploy/standalone/docker-compose.yml`、四个 `.env.example` | 队列 1.5 万、`round_robin`、初始 K = 64 |
| 修改 `conduit-e2e/.../E2eEnvironment.java`、`ReliabilityE2EIT.java` | 3 个 transport 实例，新场景 |
| 修改 `README.md`、`CLAUDE.md`、`docs/images/architecture.svg`、`deploy/split/README.md` | 文档与架构图 |

---

### Task 1: 分配参数与份额计算

**Files:**
- Create: `conduit-transport/src/main/java/dev/conduit/transport/allocation/AllocationProperties.java`
- Create: `conduit-transport/src/main/java/dev/conduit/transport/allocation/IdentityShares.java`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/allocation/IdentitySharesTest.java`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/allocation/AllocationPropertiesTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("conduit.transport.allocation") record AllocationProperties(int initialPoolSize, Duration leaseTtl, Duration renewInterval, Duration tickInterval, Duration memberTtl, Duration rebalanceStable, double poolAlertRatio)`，默认值见 Global Constraints。
  - `final class IdentityShares`，提供三个静态方法：
    - `static int share(int poolSize, int members)`：`members ≤ 0` 时按 1 计算；
    - `static List<Integer> preference(String instanceId, int poolSize)`：按 rendezvous 分数降序排列的身份序号；分数用 `Hashing.murmur3_32_fixed()` 或 `Objects.hash(instanceId, i)` 的混合（任选一个确定性实现，需在 Javadoc 里写明）；
    - `static boolean shouldAlert(int members, int poolSize, double ratio)`：返回 `members > poolSize * ratio`。

- [ ] **Step 1: Write the failing tests**
  - `shareRoundsUp`：`share(64,1)==64`、`share(64,2)==32`、`share(64,3)==22`、`share(64,64)==1`、`share(64,65)==1`、`share(64,0)==64`。
  - `preferenceIsAPermutationAndStable`：`preference("a",64)` 含 0～63 各一次；同一参数两次调用结果相等；`preference("a",64)` 与 `preference("b",64)` 的前 8 位不完全相同。
  - `preferenceIsStableWhenPoolGrows`：`preference("a",128)` 中序号 < 64 的子序列，与 `preference("a",64)` 相同。这一点保证 K 调大时已持有的身份不会被打乱。
  - `alertAboveRatio`：`shouldAlert(33,64,0.5)` 为 true，`shouldAlert(32,64,0.5)` 为 false。
  - `AllocationPropertiesTest`：默认值逐项断言；`renewInterval ≥ leaseTtl`、`initialPoolSize < 1`、`poolAlertRatio` 不在 (0, 1] 时都抛 `IllegalStateException`。

- [ ] **Step 2: Run to verify they fail**
  Run: `cd conduit-transport && mvn -B -o test -Dtest='IdentitySharesTest,AllocationPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败（类不存在）

- [ ] **Step 3: Implement** `AllocationProperties` 与 `IdentityShares`，签名见 Interfaces。

- [ ] **Step 4: Run to verify they pass**
  Expected: `Tests run: …, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit** `feat(transport): 身份池分配参数与份额计算`

---

### Task 2: Redis 租约与心跳存储

**Files:**
- Create: `.../allocation/IdentityLeaseStore.java`
- Create: `.../allocation/RedisIdentityLeaseStore.java`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/allocation/RedisIdentityLeaseStoreIT.java`（用 `TestBroker.redis()`）

**Interfaces:**
- Produces: `interface IdentityLeaseStore`
  - `String instanceId()`
  - `boolean tryAcquire(int identity)`：键不存在时写入本实例 id；键已是本实例 id 时续期并返回 true；否则返回 false。
  - `boolean renew(int identity)`：比对 id 后续期。
  - `boolean release(int identity)`：比对 id 后删除。
  - `int heartbeat(Duration memberTtl)`：一个 Lua 完成全部动作：用 `TIME` 取服务器时间、ZADD 本实例、ZREMRANGEBYSCORE 清除过期成员、ZCARD，返回存活实例数 N。
  - `void leave()`：ZREM 本实例。
  - `int poolSize(int initial)`：读 `pool-size`；不存在时用 SETNX 写入 `initial` 并返回。
  - 以上方法在 Redis 不可用时抛 `DataAccessException`。
- `RedisIdentityLeaseStore(StringRedisTemplate redis, Duration leaseTtl, String instanceId)`。抢占、续期、释放的 Lua 照搬 `RedisLeaderLock` 的三段脚本，键改为 `conduit:transport:id:{i}`。`static String newInstanceId()` 从 `RedisLeaderLock` 迁过来。

- [ ] **Step 1: Write the failing IT**
  - `acquireRenewReleaseAreOwnerChecked`：a 抢到 #3，b 抢不到；b 续期或释放 #3 都返回 false 且键不变；a 释放后 b 能抢到。
  - `leaseExpiresAfterTtl`：`leaseTtl` 设为 300ms，a 抢到后不续期，400ms 后 b 能抢到。
  - `heartbeatCountsLiveMembers`：a、b 各心跳一次，返回 2。
  - `staleMembersAreNotCounted`：`memberTtl` 设为 300ms；a 心跳一次后，b 等 400ms 再心跳，返回 1。
  - `poolSizeInitialisedOnceThenRead`：第一次 `poolSize(64)` 返回 64；手工 `SET pool-size 128` 后返回 128；再调 `poolSize(64)` 仍返回 128。
  - 每个测试结束删除自己写入的键。

- [ ] **Step 2: Run to verify it fails**（需要 Docker，见 Global Constraints）
  Run: `mvn -B verify -pl conduit-transport -Dit.test=RedisIdentityLeaseStoreIT -Dtest=NoUnit -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败

- [ ] **Step 3: Implement** `IdentityLeaseStore` 与 `RedisIdentityLeaseStore`。

- [ ] **Step 4: Run to verify it passes**

- [ ] **Step 5: Commit** `feat(transport): 身份租约、实例心跳与身份池大小的 Redis 存储`

---

### Task 3: 分配器

**Files:**
- Create: `.../allocation/IdentityAllocator.java`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/allocation/IdentityAllocatorTest.java`
  - 用内存版 `IdentityLeaseStore` 假实现，可让心跳返回指定 N、让 Redis 报错、模拟其他实例持有身份；
  - 用假 `ProtocolAdapter` 记录 start、stop 次数；
  - 时钟可注入。

**Interfaces:**
- Consumes：
  - Task 1 的 `AllocationProperties`、`IdentityShares`；
  - Task 2 的 `IdentityLeaseStore`；
  - 现有的 `ProtocolAdapter`。
- Produces：`class IdentityAllocator implements SmartLifecycle`
  - 构造函数：`IdentityAllocator(IdentityLeaseStore store, IntFunction<ProtocolAdapter> adapterFactory, AllocationProperties props, Clock clock, MeterRegistry registry)`
  - `void tick()`：一轮检查，按 spec 3.3 的五步执行。测试直接调用，生产由调度线程每 `tick-interval` 调用一次。
  - `void renewTick()`：每 `renew-interval` 续期一次全部持有的身份；也可以并入 `tick`，但续期频率必须不低于 `renew-interval`。
  - `Set<Integer> held()`、`int members()`、`int poolSize()`、`boolean allConnected()`、`String instanceId()`。
  - `Set<String> heldClientIds()`：供会话监控使用。
  - 启停适配器仍在专用单线程执行器上进行（沿用 `LeaderElection` 的 `submit` / `guard` 写法），`start` 的 `stillValid` 为「本实例仍持有该身份」。
  - `stop()`：停掉全部适配器、释放全部租约，再调用 `store.leave()`。
  - 指标：
    - `conduit.transport.identities.held`（gauge）；
    - `conduit.transport.pool.size`（gauge）；
    - `conduit.transport.members`（gauge）；
    - `conduit.transport.identities.moved`（counter，标签 `reason` 取 `acquire`、`release`、`lost`）。

- [ ] **Step 1: Write the failing tests**
  - `singleInstanceTakesAllIdentities`：N = 1、K = 4，tick 后 `held()` 为 {0, 1, 2, 3}，4 个适配器各启动一次。
  - `acquiresOnlyUpToShare`：N = 2、K = 4，其他实例不持有任何身份；tick 后持有 2 个，且是 `preference` 排在前面的 2 个。
  - `releasesExtrasOnlyAfterStable`：先 N = 1 持有 4 个；N 变为 2，立刻 tick 不让出；推进时钟 10s 后，每次 tick 只让出 1 个，直到持有 2 个；被让出的适配器停止，租约被释放。
  - `renewFailureStopsTheAdapter`：持有 #1 时 `renew(1)` 返回 false，tick 后 #1 的适配器停止，`moved{reason=lost}` 加 1。
  - `renewErrorStopsTheAdapter`：`renew` 抛 `DataAccessException`，处理同上。
  - `poolSizeIncreaseAddsIdentities`：K 从 4 读到 8，N = 1，tick 后通过 `adapterFactory` 新建 #4～#7 并全部持有。
  - `poolSizeDecreaseIsIgnored`：K 从 8 读到 4，记 WARN，持有的身份不变，`poolSize()` 仍为 8。
  - `alertWhenMembersNearPool`：N = 33、K = 64 时记 WARN，每 10 分钟最多记一次（时钟可注入）。
  - `identityZeroDutyFollowsHolder`：本实例启动的适配器 #0，就是负责 `$SYS` 事件的那一个，即由 factory(0) 创建；只断言这里调用了 factory(0)，订阅细节由 `MqttProtocolAdapter` 的现有测试覆盖。
  - `brokenAdapterIsRestartedOnlyWhileHeld`：持有 #2 的适配器 `needsRestart()` 返回 true 时，先续期确认仍持有，再 stop + start；如果续期失败，只 stop，不再 start。
  - `stopReleasesEverything`：`stop()` 后全部适配器停止，每个持有的身份调用一次 `release`，并调用 `leave()`。

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement** `IdentityAllocator`，按 spec 3.3 的五步实现；启停与重连监督可参照 `LeaderElection` 的结构。

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit** `feat(transport): 身份池分配器，按租约抢占、让出与续期`

---

### Task 4: 实例级合批提交器

**Files:**
- Create: `.../mqtt/SharedBatcher.java`
- Modify: `.../mqtt/MqttProtocolAdapter.java`：删除 `Session.pending`、`scheduleFlush`、`flush`、`publishAllWithRetry`、`flushTimer`；`onPublish` 改为调用 `batcher.submit(...)`；构造器中的 `MessageBus` 参数换成 `SharedBatcher`，即 `MqttProtocolAdapter(MqttTransportProperties props, SharedBatcher batcher, Clock clock, int index)`，测试用的带 `initialBackoff` 的重载照此改；index 只校验 `>= 0`。
- Test: `conduit-transport/src/test/java/dev/conduit/transport/mqtt/SharedBatcherTest.java`
- Modify tests: `MqttProtocolAdapterTest`、`MultiConnectionTest`、`ReliableUplinkTest`，改为通过 `SharedBatcher` 构造。原有的攒批断言迁到 `SharedBatcherTest`。

**Interfaces:**
- Produces：`final class SharedBatcher implements AutoCloseable`
  - 构造函数：`SharedBatcher(MessageBus bus, int batchSize, Duration batchDelay, Duration initialBackoff)`
  - `void submit(RawMessage message, BooleanSupplier live, Runnable onCommitted)`：线程安全。
    - `live`：所属会话是否仍有效；
    - `onCommitted`：写入成功后，在批提交线程上按提交顺序调用；适配器在其中先发业务回执，再调用 `publish.acknowledge()`。
  - 单线程批提交循环：满 `batchSize` 立即提交，否则最迟 `batchDelay` 提交。每次提交前，丢弃 `live` 为 false 的条目（不写入，也不回调）。写入失败时，按 `MqttProtocolAdapter` 现有的退避与「只重提未成功条目」语义重试。
  - `close()`：停止循环，正在重试的批放弃，不回调。

- [ ] **Step 1: Write the failing tests**
  - `mergesSubmissionsFromManyConnectionsIntoOneBatch`：3 个 live 来源各提交 2 条，`batchSize` = 6；bus 只被调用一次，参数为 6 条。
  - `flushesAfterDelayWhenNotFull`：`batchSize` 64、`batchDelay` 20ms；提交 1 条，100ms 内 bus 被调用，回调执行。
  - `callbacksPreserveArrivalOrderPerConnection`：来源 A 交错提交 a1、a2、a3；回调记录的顺序为 a1、a2、a3。
  - `callbackRunsOnlyAfterSuccessfulPublish`：bus 第一次抛异常、第二次成功；回调只执行一次，且在第二次调用之后。
  - `retriesOnlyFailedItems`：bus 返回第 2 条失败，下一轮只重提第 2 条。
  - `itemsOfStoppedSessionsAreDroppedNotAcked`：bus 一直失败；期间把来源 B 的 live 置为 false；之后 bus 恢复，B 的条目没有写入，也没有回调，A 的条目照常回调。
  - `stopDoesNotWaitForABlockedBatch`：bus 一直失败时调用 `close()`，200ms 内返回。

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement** `SharedBatcher`，并改造 `MqttProtocolAdapter`：`onPublish` 构造 `RawMessage` 后调用 `batcher.submit(message, () -> !s.stopping, () -> { if PROPERTY sendReliableAck(...); acknowledge(publish); })`。

- [ ] **Step 4: Run all transport unit tests**
  Run: `cd conduit-transport && mvn -B -o test`
  Expected: 全部通过；被删掉的旧攒批测试已迁到新文件。

- [ ] **Step 5: Commit** `refactor(transport): 实例内各连接合批写总线（SharedBatcher）`

---

### Task 5: 装配、健康检查、会话监控，删除选主

**Files:**
- Create: `.../allocation/AllocationHealthIndicator.java`
- Modify: `.../TransportApplication.java`、`.../mqtt/MqttTransportProperties.java`、`.../monitor/SessionMonitorGroup.java`、`conduit-transport/src/main/resources/application.yml`
- Delete: `.../leader/LeaderElection.java`、`LeaderLock.java`、`RedisLeaderLock.java`、`LeaderProperties.java`、`LeaderHealthIndicator.java`，以及测试 `LeaderElectionTest`、`LeaderElectionIT`、`LeaderHealthIndicatorTest`、`LeaderPropertiesTest`、`RedisLeaderLockIT`
- Test：
  - `AllocationHealthIndicatorTest`；
  - 修改 `TransportApplicationPollTest`；
  - 修改 `TransportApplicationIT`：启动后健康明细含 `held`，`connected` 为 true；
  - 修改 `MqttTransportPropertiesTest`：`connections` 旧键仍能设置初始 K。

**Interfaces:**
- Consumes：Task 3 的 `IdentityAllocator`，Task 4 的 `SharedBatcher`。
- Produces：
  - 健康组件名改为 `allocation`，明细字段为 `instanceId`、`held`、`members`、`poolSize`、`connected`。UP 的条件：实例在运行，且已持有的身份全部已连接。会话监控判定 DOWN 时整体 DOWN，规则同现状，但只针对已持有的身份。
  - `SessionMonitorGroup.sample(Set<String> clientIds)`：只采样给定身份，其余身份的监控状态重置。
  - `TransportApplication.pollOnce(IdentityAllocator, SessionMonitorGroup)` 改为 `monitor.sample(allocator.heldClientIds())`。

- [ ] **Step 1: Write the failing tests**：健康明细字段；`pollOnce` 只采样已持有的身份；旧键 `conduit.transport.mqtt.connections` 映射到初始 K。
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement**：
  - 装配 `RedisIdentityLeaseStore`、`SharedBatcher`、`IdentityAllocator`（`adapterFactory` 为 `i -> new MqttProtocolAdapter(props, batcher, Clock.systemUTC(), i)`）；
  - 会话监控按身份惰性创建；
  - 删除 leader 包；
  - `application.yml` 增加 `conduit.transport.allocation.*`，删除 `conduit.transport.leader.*`。
- [ ] **Step 4: Run** `mvn -B -o test`（单测），然后在 129 上跑 `mvn -B verify -pl conduit-transport`（含 IT）。Expected: 全部通过。
- [ ] **Step 5: Commit** `feat(transport): 以身份池分配器取代单活选主，健康检查与会话监控按持有身份`

---

### Task 6: 部署参数

**Files:**
- Modify：
  - `deploy/dev/docker-compose.yml`：`EMQX_ZONES__TRANSPORT__MQTT__MAX_MQUEUE_LEN` 默认值改为 15000；`EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY` 改为 `${EMQX_SHARED_SUBSCRIPTION_STRATEGY:-round_robin}`；
  - `deploy/standalone/docker-compose.yml`：环境变量 `TRANSPORT_MQTT_CONNECTIONS` 改为 `TRANSPORT_POOL_SIZE`（默认 64），映射到 `conduit.transport.allocation.initial-pool-size`；
  - `deploy/split/app/docker-compose.yml`；
  - 四个 `.env.example`；
  - `conduit-e2e/.../E2eEnvironment.java`：zone 环境变量改为与上面一致。
- 验证：`docker compose config`，在 129 上对 dev、standalone、split 三个包分别执行（做法同 `verify_host_network.py`），以及运行 `python docs/script/loadtest/verify_host_network.py`。

- [ ] **Step 1: 修改上述文件**。注释写明 64 × 1.5 万 ≈ 96 万、`round_robin` 的理由（不需要顺序），以及「调大 K 时按 K × 单连接队列重新估算总缓冲」。
- [ ] **Step 2: Run** 上述配置校验。Expected：三个包的 `config` 都成功；`verify_host_network.py` 4 项 PASS。
- [ ] **Step 3: Commit** `feat(deploy): 身份池默认 64、单连接队列 1.5 万、共享订阅改为轮询`

---

### Task 7: e2e 多实例场景

**Files:**
- Modify: `conduit-e2e/src/test/java/dev/conduit/e2e/E2eEnvironment.java`、`ReliabilityE2EIT.java`

**Interfaces:**
- Produces（`E2eEnvironment`）：
  - 启动 3 个 transport：`transport-a`、`transport-b`、`transport-c`，初始 K 为 16（缩短测试时间）；
  - `void killTransport(int i)`、`void startTransport(int i)`；
  - `void setPoolSize(int k)`：直接写 Redis 键；
  - `Map<String,Integer> heldByInstance()`：读各实例健康检查里的 `held`；
  - `void awaitAllIdentitiesHeld(Duration timeout)`：断言全部身份都有人持有，且各实例持有数之差 ≤ 1。

- [ ] **Step 1: 改写与新增场景**：
  - 原场景 2 改名为「杀任一 transport」；
  - 新增「负载下 1 个扩到 3 个」「3 个缩到 1 个」「同时杀 2 个」「负载下把 K 从 16 调到 32」。
  - 每个场景沿用三项断言：计数恰好、无死信、无待确认。场景结束时调用 `awaitAllIdentitiesHeld`。
- [ ] **Step 2: Run on 129**：`mvn -B -DskipTests install`，再 `mvn -B verify -Pe2e -pl conduit-e2e`。Expected：全部用例通过，包括原有 6 个与新增 4 个。
- [ ] **Step 3: Commit** `test(e2e): transport 多实例扩缩容、同时宕机与在线调大身份池`

---

### Task 8: 文档、架构图与测试环境验收

**Files:**
- Modify：
  - `CLAUDE.md`：第 1 节模块表；「transport 单活多备与共享订阅」一节改写为「transport 身份池多活」；删除选主小节；第 4 节加一行决定；
  - `README.md`；
  - `docs/images/architecture.svg`：把「Redis 选主 · 主实例 8 条连接」改为「身份池 64 · 多活」；同时检查 `module-dependencies.svg`；
  - `deploy/split/README.md`：升级步骤、调大 K 的命令。
- Create: `docs/test-reports/2026-11-xx-transport-multi-active.md`

- [ ] **Step 1: 更新文档与 SVG**，并自检文字、间距、连线与引用。
- [ ] **Step 2: 部署到拆分环境**：
  1. 先停掉 129 上的全部旧 transport；
  2. 重建 128 的 EMQX（新 zone 队列与分发策略）；
  3. 按 split README 丢弃 8 个旧会话；
  4. 启动 2 个新 transport；
  5. 回读运行配置：两个实例的 `held` 之和为 64，且各约 32；EMQX 共享订阅数为 64；`mqueue_max` 为 15000；分发策略为 `round_robin`。
- [ ] **Step 3: 压测**（拆分部署，压测前回读运行配置并核对宿主机空闲）：
  - 每秒 1 条 8,000 台，2 实例对比单活时的吞吐与 CPU；
  - 2.5 万台断网补传（断网 300 秒、每台每秒补 5 条），要求计数守恒、零强杀；
  - 2.5 万台画像负载下强杀一个 transport，要求计数守恒；
  - `round_robin` 与 `hash_clientid` 各跑一次画像档，做对比。
- [ ] **Step 4: 写测试报告**：每项写明代码版本、实际命令、结果、跳过项与日志位置。
- [ ] **Step 5: Commit** `docs: transport 身份池多活的架构文档、部署说明与验收报告`
