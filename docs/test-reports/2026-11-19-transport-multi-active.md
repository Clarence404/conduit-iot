# transport 身份池多活验收测试（2026-11-19）

**测试类型**：单元测试；集成测试；e2e；回归对比（每秒 1 条档、画像档、分发策略对比）；压力测试（断网补传，已核对失效日志）；故障测试（负载下强杀一个 transport）。

**被测范围**：
- [transport 身份池多活设计](../superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md) 与[实施计划](../superpowers/plans/2026-11-19-transport-multi-active.md)；
- 所有 transport 实例同时工作，共享 K 个身份（默认 64），按 Redis 租约精确配额分配，各实例持有数相差不超过 1；
- 实例内合批写总线（`SharedBatcher`），写成功后各连接分别回 PUBACK 与业务回执；
- EMQX 共享订阅分发策略 `round_robin`，transport zone 单连接会话队列 1.5 万。

代码在分支 `feat/transport-multi-active`：单元、集成、e2e 在各自提交上运行（见第 2 节）；部署与压测用 `911434f` 的 transport（之后的提交只改文档）。server、simulator 沿用 11-16 部署的版本，本次未改。

## 结论

1. **功能与可靠性验收通过。**
   - transport 单元 151 个、集成 13 个、e2e 10 个场景全部通过。
   - e2e 覆盖：杀任一实例、杀全部、重启 EMQX、可靠上报中强杀 EMQX、1 个扩到 3 个、3 个缩到 1 个、同时杀 2 个、负载下 K 从 16 调到 32。每个场景计数恰好、无死信、无待确认，最终全部身份有人持有且持有数相差不超过 1。
2. **两个 transport 同时工作，负载下强杀其中一个不丢数据。**
   - 画像 2.5 万台：计数守恒 1,140,111 = 1,140,111，p99 36 ms，业务回执 563,889 / 563,889。
   - 强杀后 16 秒内，存活实例接管全部 64 个身份。被杀实例重启后约 76 秒回到 32 / 32，每次检查让出一个。
3. **断网补传计数守恒、零强杀，但时延比单活时差。**
   - 2.5 万台断网 300 秒、每台每秒补 5 条：守恒 2,280,255 = 2,280,255，丢弃 0，订阅连接强杀 0，业务回执完整（单活时有 66 条回执丢弃）。
   - p99 3,785 ms（单活基线 `results-internal-outage-r5` 为 1,739 ms），重连 CONNACK p99 4,082 ms（单活 546 ms），EMQX 过载告警 5 次（单活 1 次）。按判据仍不合格，原因只在时延。
   - 原因尚未定位。分发策略不是原因（第 4 条）。候选：订阅连接由 8 条增至 64 条，补传洪峰时 EMQX 的调度开销更大；发压所在宿主机 CPU 峰值 98%。见第 5 节。
4. **`round_robin` 优于或等于 `hash_clientid`，保留 `round_robin`。**
   - 画像档两者相当（p99 36 / 38 ms，CPU 相同）。
   - 断网补传档 `hash_clientid` 更差：p99 5,464 ms、34 台运行中掉线、丢弃 86、过载告警 7 次。
5. **稳态 CPU 略高于单活；p99 是否优于单活取决于基线。** 每秒 1 条 8,000 台：守恒、全部合格，p99 20 ms，应用主机 CPU 65%，EMQX 主机 68%，总线积压峰值 13 万，稳态结束时积压在判据以内。
   - 对照单活基线 `results-internal-1s`（11-16，已改连内部监听器，与本次配置最接近）：p99 42 ms，CPU 58 / 57%，积压峰值 4.8 万。
   - 但同日更早的单活一轮 `results-rps-off2`（11-16，内部监听器之前）p99 只有 16 ms，CPU 52 / 53%，积压峰值 15,743。两轮单活相差一倍多，说明这一档运行间波动大，「多活 p99 优于单活」不成立，只能说两者在同一量级；CPU 与积压峰值则两条基线都低于多活。
6. **部署回读与设计一致。** 两个实例各持有 32 个身份，`members` 为 2，`poolSize` 为 64；EMQX 共享订阅 64 个，64 个会话都在 `tcp:internal` 上，`mqueue_max` 15000，`inflight_max` 1024，分发策略 `round_robin`。

## 1. 环境

- 拆分部署：EMQX 5.10.5 在 128 上，应用在 129 上，与 11-16 报告相同；均为本机 VMware 虚拟机，数字不得外推到物理机或云主机。
- 部署变更（上传前远端文件备份为 `.bak-20261119`）：
  - 部署前回读：远端 compose 与仓库基线完全一致，无漂移；EMQX 主机 `.env` 不覆盖 zone 队列与分发策略。
  - 先停 129 上的两个旧 transport，再在 128 上重建 EMQX（重建即丢弃全部内存会话，包括 8 个旧 transport 会话，因此不需要单独 kick），最后用新 jar（`BUILD_FROM=prebuilt`）构建并启动两个 transport。
  - 分发策略对比时，在 EMQX 主机 `.env` 中设 `EMQX_SHARED_SUBSCRIPTION_STRATEGY`，每次切换都是「停 transport → 重建 EMQX → 启 transport」；结束时已切回 `round_robin`。
- 发压机为开发机（Windows），压测前确认 CPU 空闲（约 4%），空闲内存约 1.3 GB，与以往压测相同。

## 2. 单元、集成与 e2e

| 命令 | 代码 | 结果 |
|---|---|---|
| `mvn -o test`（conduit-transport，本机） | 各任务提交 | 151 个全部通过 |
| `mvn -B verify -pl conduit-common,conduit-transport -am`（129 上 Maven 容器） | `7114ba2` | 单元 151、集成 13 全部通过（`RedisIdentityLeaseStoreIT` 6、`MqttProtocolAdapterIT` 6、`TransportApplicationIT` 1），46 秒 |
| `mvn -B -DskipTests install` 后 `mvn -B verify -Pe2e -pl conduit-e2e`（同上，`E2E_DEV_HOST` 为 129） | `911434f` | `ReliabilityE2EIT` 10 个全部通过（660.8 秒），`EmqxConfigTest` 2 个通过；总计 677 秒 |

- 先写失败测试：份额改为精确配额前，3 实例与 10 实例收敛测试在旧算法下失败（停在 6 / 6 / 4、7 × 9 + 1），实现后通过。
- 发现并修复的测试隔离问题：`TransportApplicationIT` 的 Spring 上下文缓存后，其 transport 仍连在共享的测试 EMQX 上，使同一 JVM 中的 `MqttProtocolAdapterIT` 多收一条回执；该失败在本次改动前的提交上同样出现。已加 `@DirtiesContext`，此后整模块 verify 通过。
- 运行方式同 11-16 报告第 2 节（129 上 `maven:3.9.9-eclipse-temurin-21` 容器，挂载 `~/.docker-java.properties`）。

## 3. 部署回读

| 项 | 期望 | 实际 |
|---|---|---|
| 两个实例 `allocation` 明细 | 各 32，`members` 2，`poolSize` 64，`connected` true | 32 / 32，2，64，true |
| EMQX 分发策略 | `round_robin` | `round_robin` |
| zone `transport` | 会话队列 15000、信箱 200000 | 15000、200000 |
| transport 会话 | 64 个，在 `tcp:internal` 上 | 64 个；`mqueue_max` 15000、`inflight_max` 1024 |
| 共享订阅数 | 64 | 64 |

## 4. 压测

判据与列含义见 `docs/script/loadtest/README.md`。「PUBACK = InfluxDB 行」即计数守恒；对外口径只用入库 TPS。

| 场景 | 设备 / 消息每秒 | 结论 | PUBACK = InfluxDB 行 | 入库 TPS | 掉线 | p99 ms | 总线积压峰值 | 应用 / EMQX 主机 CPU% | 宿主机 CPU% 平均 / 峰值 | 不合格原因 |
|---|---|---|---|---|---|---|---|---|---|---|
| 每秒 1 条（多活，`round_robin`） | 8,000 / 8,000 | ✅ | 2,399,956 / 2,399,956 | 8,095 | 0 | 20 | 130,159 | 65 / 68 | 77 / 93 | — |
| 对照：单活 `results-internal-1s`（11-16，内部监听器） | 8,000 / 8,000 | ✅ | 2,399,979 / 2,399,979 | 7,201 | 0 | 42 | 47,676 | 58 / 57 | 67 / 89 | — |
| 对照：单活 `results-rps-off2`（11-16，内部监听器之前） | 8,000 / 8,000 | ✅ | 2,399,955 / 2,399,955 | 7,938 | 0 | 16 | 15,743 | 52 / 53 | 65 / 81 | — |
| 断网补传（多活，`round_robin`） | 25,000 / 1,958 | ❌ | 2,280,255 / 2,280,255 | 1,917 | 0 | 3,785 | 217,928 | 26 / 30 | 45 / 98 | p99；过载告警 5 次 |
| 断网补传（多活，`hash_clientid`） | 25,000 / 1,958 | ❌ | 2,277,631 / 2,277,631 | 1,915 | 34 | 5,464 | 192,439 | 30 / 35 | 52 / 96 | 丢弃 86；34 台掉线；p99；过载告警 7 次 |
| 对照：断网补传单活 `results-internal-outage-r5`（11-16） | 25,000 / 1,958 | ❌ | 2,280,243 / 2,280,243 | 1,917 | 0 | 1,739 | 206,669 | 27 / 26 | 42 / 92 | 丢弃 66（回执）；p99；过载告警 1 次 |
| 画像 + 强杀一个 transport（多活，`round_robin`） | 25,000 / 1,958 | ✅ | 1,140,111 / 1,140,111 | 1,917 | 0 | 36 | 5,393 | 30 / 32 | 53 / 98 | — |
| 画像（多活，`hash_clientid`） | 25,000 / 1,958 | ✅ | 1,140,115 / 1,140,115 | 1,916 | 0 | 38 | 25,358 | 28 / 32 | 52 / 86 | — |
| 对照：画像单活 `results-internal-profile`（11-16） | 25,000 / 1,958 | ❌ | 1,140,117 / 1,140,117 | 1,867 | 0 | 41 | 57,768 | 31 / 28 | 47 / 81 | 丢弃 398（回执） |

对照基线均为 11-16 同一拆分环境上的单活版本（结果目录在 `docs/script/loadtest/` 下，本机，未入库）：每秒 1 条档用 `results-internal-1s`，断网补传档用 `results-internal-outage-r5`，画像档用 `results-internal-profile`，三者都已改连内部监听器（8 条连接，zone 队列 10 万，`hash_clientid`）。`results-rps-off2` 是同日内部监听器之前的一轮（`2026-11-16-connect-storm-outage-replay.md`），列出来说明每秒 1 条档的 p99 在单活自身两轮之间就相差一倍多，跨版本的时延比较要看用的是哪条基线。

补充数据：
- 断网补传（`round_robin`）：断网 25,000 台、本地积压 749,502 条；重连 25,007 次、CONNACK p99 4,081.7 ms；补完最长 154.0 秒；未补完 0 条、未连回 0 台；业务回执 1,127,797 / 带 id 发出 1,127,797；失效日志（EMQX 强杀等）0 行。
- 断网补传（`hash_clientid`）：重连 25,579 次、CONNACK p99 5,759.6 ms；补完最长 157.3 秒；业务回执 1,125,666 / 1,127,795，34 台掉线期间未确认的消息由带 id 的设备重发、不带 id 的设备在本地可见为未确认；失效日志 0 行。
- 强杀时间线（每 5 秒回读两个实例的 `held / members`）：
  - 16:49:21 强杀 `conduit-transport-2`；16:49:37 `conduit-transport-1` 持有 64、`members` 1。
  - 16:50:47 启动 `conduit-transport-2`；16:50:58 就绪，持有 0；16:51:04 起成员列表稳定，每次检查让出一个；16:52:20 回到 32 / 32。
  - 鉴权计数 `transport/allow` 64：接管 32 次、回迁 32 次。

实际命令（在 `docs/script/loadtest` 下）：

```
python ladder.py run --plan 8000 --rate 1 --hold 300 --out results-ma-1s-8000
python ladder.py run --plan 25000 --hold 1200 --mix 60:0.7,15:0.25,1:0.05 --points 30 --id-ratio 0.5 --per-process 2500 --connect-retries 4 --outage 300:300:1.0 --replay-rate 5 --out results-ma-outage-r5
python ladder.py run --plan 25000 --hold 600 --mix 60:0.7,15:0.25,1:0.05 --points 30 --id-ratio 0.5 --per-process 2500 --out results-ma-kill
python ladder.py run --plan 25000 --hold 600 --mix 60:0.7,15:0.25,1:0.05 --points 30 --id-ratio 0.5 --per-process 2500 --out results-ma-profile-hash
python ladder.py run --plan 25000 --hold 1200 --mix 60:0.7,15:0.25,1:0.05 --points 30 --id-ratio 0.5 --per-process 2500 --connect-retries 4 --outage 300:300:1.0 --replay-rate 5 --out results-ma-outage-hash
```

强杀场景另由脚本在负载开始约 260 秒后执行 `docker kill conduit-transport-2`，90 秒后 `docker start`，并每 5 秒回读健康明细。

## 5. 未解决与后续

1. **断网补传时延回退的原因未定位。** 下一步可做的对照：同一版本把 K 设为 8（zone 队列相应调回 10 万）复跑断网补传，判断是否由订阅连接数增加引起；同时观察 EMQX 调度器利用率与 `runq_overload` 的时间点。
2. **稳态 CPU 与总线积压峰值高于单活。** 对照 `results-internal-1s`，两台虚拟机的 CPU 都上升约 7～11 个百分点（对照 `results-rps-off2` 为 13～15 个百分点），积压峰值在稳态结束前回落。是否由 64 条连接、两个实例各自的合批，或宿主机负载（本次宿主机 CPU 平均高 10 个百分点）引起，需要与上一条一起对照。
3. 「丢弃」判据：本次 `round_robin` 各场景丢弃为 0；`hash_clientid` 断网补传的 86 条与 34 台掉线同时出现。

## 6. 跳过与阻塞

- 没有在多台发压机上做更高档位；每秒 1 条只跑了 8,000 台一档。
- 没有测 K 调大后的线上效果（e2e 已覆盖 16 → 32，拆分环境未做）。

## 7. 日志与结果位置

- 压测结果：`docs/script/loadtest/results-ma-*`（本机，未入库）及同名 `.log`。
- 集成测试与 e2e 的 Maven 输出：执行机临时目录（未入库），关键行已摘入第 2 节。
- 应用日志：129 上 `conduit-logs` 卷，transport 写 `conduit-transport.log`，第二个实例在 `transport-2` 子目录。

## 8. 终审修复后的复测（2026-11-19）

被测提交：`18b14e4`（合批线程捕获 `Throwable` 并计数、回执异常仍 PUBACK、Java 镜像 `ExitOnOutOfMemoryError`）与 `a4adb19`（身份启动失败按身份退避重试、非法 K 保持当前值、检测旧选主键时不抢身份）。环境：开发机 Windows，JDK 21.0.8，Maven 离线；无 Docker。

| 命令 | 结果 | 耗时 |
|---|---|---|
| `mvn -B -o -q -pl conduit-transport test` | 179 个单元测试全部通过（0 失败、0 错误、0 跳过） | 约 12 秒 |
| `mvn -B -o -q -pl conduit-transport test-compile` | 通过（含 `RedisIdentityLeaseStoreIT` 新增的非法 K 与旧选主键用例） | — |
| `mvn -B -o -q -pl conduit-e2e -Pe2e test-compile` | 通过 | — |

新增用例先失败后通过：`SharedBatcherTest` 2 个（总线或回调抛 `Error` 后续批照常提交）、`MqttProtocolAdapterTest` 1 个（回执同步抛异常仍 PUBACK）、`IdentityAllocatorTest` 7 个（一个身份始终起不来不阻塞同一执行器上的其他身份、按 1/2/4/8/10/10 秒退避重试、丢失身份后不再重试、同一身份不排两个任务、非法 K、旧选主键）。修复前前 4 个分配器用例超时或条件不满足，其余为断言失败。

**未执行**：集成测试（`RedisIdentityLeaseStoreIT` 等需 Docker）与 e2e，留待 Docker 主机执行，结果另行补入；本节不把它们记为通过。
