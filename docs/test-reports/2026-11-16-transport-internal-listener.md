# transport 内部监听器验收测试（2026-11-16）

**测试类型**：单元测试；压力测试（断网补传，已核对失效日志）；回归对比（画像档、每秒 1 条档）。

**被测范围**：
- [transport 内部监听器设计](../superpowers/specs/2026-11-16-conduit-transport-internal-listener-design.md)：EMQX 新增 `tcp:internal`（1884）与 zone `transport`（信箱 20 万、会话队列 10 万、堆上限不变）；
- transport 默认连 1884、开 8 条连接；
- 内部监听器只许 transport 连接。

代码为本地 main 上的工作区（提交见文末）。

## 结论

1. **根因已消除。** 断网补传同一场景（2.5 万台、断网 300 秒、每台每秒补 5 条）：
   - **计数守恒 2,280,243 = 2,280,243**，transport 订阅连接被强杀 **0 次**；
   - 订阅连接信箱峰值 1,887 条（上限 20 万），会话队列峰值 27,098 条（上限 10 万），堆峰值 34 MB（上限 256 MB）。
   - 修正前同一场景少 250,394 条、强杀 5 次。
   - 判据里仍然记了不合格，原因是 p99 1,739 ms 和 1 次 `runq_overload` 告警：补传洪峰期间时延变高，但数据完整。另有 66 条丢弃，见第 4 条。
2. **画像 2.5 万台、每秒 1 条 8,000 台回归通过，守恒成立。**
   - 画像档中途 Redis 出现命令超时，transport 按设计主备切换，数据没丢，见第 3 节。
   - 每秒 1 条 8,000 台按判据全部合格。
3. **内部监听器的隔离有效。** 同一设备凭据：连 1883 成功，连 1884 返回 `Not authorized`，server 计数 `device_on_internal_listener` 为 1。
4. **判据里的「丢弃」都是发给已断开设备的业务回执，不是上行数据。** 两轮的 66 条和 398 条都是 `no_subscribers`，集中在负载结束、发压器断开设备之后的一分钟内。那时 transport 对最后几条消息发出的 QoS 0 业务回执已经没有接收者。上行行数与 PUBACK 完全相等，可以证明不是上行丢失。判据把这类回执丢弃也算作不合格，改进见第 5 节。
5. **升级时需要一次性丢弃旧会话。** EMQX 的会话参数在创建时就固定了。transport 改连 1884 后接管了原来在 1883 上创建的 4 个会话，队列上限仍是 50,000。用 `emqx ctl clients kick` 丢弃旧会话后，transport 立即按新 zone 重建，8 条都变成 100,000。这一步已写进 `deploy/split/README.md` 与 CLAUDE.md。

## 1. 环境

- 拆分部署：EMQX 5.10.5 在 128 上，应用在 129 上，与上一份报告相同。
- 部署变更：
  - 上传前先备份远端文件（`.bak-20261116`）；远端原有的 compose 只是旧注释与旧默认值，`.env` 中的实际值与仓库一致。
  - 128 重建 EMQX；129 用本地构建的 jar（`BUILD_FROM=prebuilt`）重建 server 与两个 transport。
- 回读结果：
  - `tcp:internal` 监听在 0.0.0.0:1884；
  - zone `transport` 的 `max_mqueue_len` 为 100000，`force_shutdown` 为 `max_mailbox_size` 200000、`max_heap_size` 256 MB；
  - 8 条 transport 连接都在 `tcp:internal` 上，`mqueue_max` 为 100000，`inflight_max` 为 1024；共享订阅数 8。

## 2. 单元与集成测试

| 命令 | 结果 |
|---|---|
| `mvn -o test -Dtest='MqttAuthServiceTest,MqttAuthPropertiesTest'`（conduit-server） | 6 个全部通过。新用例先编译失败（缺少四参数 `authenticate`），实现后通过 |
| `mvn -o test`（conduit-transport） | 115 个全部通过。`MqttTransportPropertiesTest` 先失败（期望 1884，实际 1883），改默认值后通过 |
| `mvn -B verify -pl conduit-server`（2026-11-17，129 上 Maven 容器内） | 单测 2,284 个、集成测试 280 个全部通过，含新增的「内部监听器的回调只放行 transport」 |
| `mvn -B verify -Pe2e -pl conduit-e2e`（同上，`E2E_DEV_HOST` 为 129） | `ReliabilityE2EIT` 5 个故障场景全部通过（271.6 秒），transport 走内部监听器 |

JDK 用的是 `ms-21.0.8`（shell 默认的 JDK 不是 21）。

**集成测试与 e2e 的运行方式**
- 本机没有 Docker，128 不能联网，所以在 129 上用 `maven:3.9.9-eclipse-temurin-21` 容器跑：host 网络，挂载 `/var/run/docker.sock`，源码是 main HEAD 的 `git archive`。
- 前两次全部失败，报「Could not find a valid Docker environment」。原因是 129 的 Docker 29.9.2 最低只接受 API 1.40，而 Testcontainers 1.21.3 默认用 1.32。设 `DOCKER_API_VERSION` 环境变量无效。
- 挂载 `~/.docker-java.properties`（`api.version=1.44`）后解决。

## 3. 压测验收

| 场景 | PUBACK / InfluxDB 行 | 强杀 | EMQX 丢弃 | 掉线 | p99 ms | 其他 | 判据结论 |
|---|---|---|---|---|---|---|---|
| 断网补传：2.5 万台，断网 300 秒，每台补 5 条/秒 | 2,280,243 / 2,280,243 | 0 | 66（回执） | 0 | 1,739 | 积压 735,115 条；重连 CONNACK p99 546 ms；补完最长 133 秒；业务回执 1,127,663 / 带 id 发出 1,127,791 | ❌：p99、1 次过载告警、回执丢弃 |
| 画像：2.5 万台，稳态 600 秒 | 1,140,117 / 1,140,117 | 0 | 398（回执） | 0 | 41 | 中途 Redis 超时导致 transport 主备切换一次 | ❌：仅回执丢弃 |
| 每秒 1 条：8,000 台，稳态 300 秒 | 2,399,979 / 2,399,979 | 0 | 0 | 0 | 42 | — | ✅ |

**对照：修正前同一断网补传场景**（[上一份报告](2026-11-16-connect-storm-outage-replay.md)）：少 250,394 条，强杀 5 次，被杀时信箱最大 44,243 条。

**画像档中途的主备切换**
- 14:25（UTC）Redis 后台 AOF 重写期间，`appendfsync always` 下命令超时超过 2 秒。transport 主实例续锁失败，按设计断开、转为备用；另一实例 23 秒后抢到锁，8 条连接全部接管。
- 计数守恒成立，切换期间的消息都排在 EMQX 会话里，重连后补发。
- 这次超时与本改动无关，是「Redis AOF 重写拖慢命令」的环境现象，记入遗留项。

## 4. 跳过、阻塞与遗留

- **判据改进（2026-11-17 已改）**：发压器在断网前和收尾时，先等业务回执收齐（最多 10 秒）再断开设备，与真实设备「收到回执才删待确认消息」一致，回执不再发给已断开的设备。冒烟测试：2,500 台全部断网 30 秒、每台每秒补 5 条，守恒 24,353 = 24,353，回执 10,155 / 10,155，EMQX 丢弃 0，判定合格。
- **补传洪峰期间 p99 超过 1 秒、出现 1 次过载告警**：所有者决定不作为验收不通过，作为已知限制写进设备接入规范 4.3 节。
- **Redis AOF 重写引起命令超时、触发 transport 主备切换（数据不丢）**：已另立项，记入 `docs/backlog-v0.2.md`。
- 结果 JSON 在 `docs/script/loadtest/results-internal-*`，不入库。
