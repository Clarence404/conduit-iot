# 验证：EMQX 直写 Kafka 能否替代 transport（2026-11-17）

- 背景：transport 的「单活多备」是历史包袱（见 `roadmap-saas.md` 第 2 节架构短板第 1 项）。所有者计划改用 EMQX 商业版，所以先验证「EMQX 数据集成直写 Kafka、server 消费 Kafka、去掉 transport」是否满足可靠性要求，再决定 transport 去留（2026-11-17 所有者决定）。
- 性质：技术验证（spike）。只在 129 上起隔离的临时环境，不改仓库代码，不动拆分部署。
- 前提已确认：`emqx/emqx:5.10.5` 自带 `emqx_bridge_kafka`，内置社区 License（单节点，`max_sessions` 1000 万，有效期到 2029-03-01），验证不需要额外 License。

## 1. 要回答的问题

1. **PUBACK 的时机**：EMQX 给设备回 PUBACK，是在 Kafka 确认之前还是之后？如果在之前，下游出问题时，已确认的消息有没有被丢、在哪里丢？
2. **Kafka 不可用或变慢**：消息在 EMQX 内部缓冲多少、能撑多久，满了怎么办？分别看内存缓冲与磁盘缓冲。
3. **强杀 EMQX**：缓冲中尚未写进 Kafka 的消息，在内存缓冲与磁盘缓冲下能否恢复？
4. **补传洪峰与吞吐**：在 30 测点画像下，直写 Kafka 的无损吞吐是多少？同样的断网补传场景（2.5 万台、断网 300 秒）能否守恒？
5. **顺序与分区**：按 `clientid` 作 key 写入（`key_dispatch`），同一设备是否落在同一分区、顺序是否保持？
6. **业务回执**：去掉 transport 后由 server 消费后发回执，可行性只做评估（EMQX HTTP 发布接口或 MQTT 客户端），本次不实现。

## 2. 环境

- 129 上的独立 compose 项目 `emqx-kafka-spike`：EMQX 5.10.5（不鉴权，只做验证，端口不与拆分部署冲突）与 Kafka 4.0 单节点（KRaft）。
- 主题 `conduit-up` 设 16 个分区；动作 `required_acks = all_isr`；消息 key 为 `${clientid}`，value 为原始报文。
- 规则：`SELECT * FROM "conduit/+/+/up/#"`，直接交给 Kafka 动作。
- 发压用 `loadgen.py` fleet 模式，设 `LOADGEN_MQTT_HOST/PORT` 指向验证环境。
- 判据：客户端 PUBACK 数 = Kafka 主题各分区 end offset 之和（只发验证专用主题，无其他流量），同时记录 EMQX 动作的 `success` / `failed` / `dropped` 计数。

## 3. 场景

| # | 场景 | 缓冲模式 | 判定 |
|---|---|---|---|
| S0 | 基线：2,000 台 × 每秒 1 条，稳态 120 秒 | 内存 | 守恒 |
| S1 | 发送中途停掉 Kafka 60 秒后恢复 | 内存 | 是否守恒；缓冲在哪里、丢弃计数 |
| S2 | 同 S1 | 磁盘 | 同上 |
| S3 | 发送中途暂停 Kafka 60 秒（模拟变慢） | 磁盘 | 同上 |
| S4 | Kafka 停掉期间强杀 EMQX，再依次恢复 | 内存 / 磁盘各一次 | 缓冲中的消息能否恢复 |
| S5 | 30 测点画像，逐档提速到不守恒为止 | 磁盘 | 无损吞吐，与 transport 方案对照 |
| S6 | 断网补传：2.5 万台、断网 300 秒、每台每秒补 5 条 | 磁盘 | 是否守恒；与 transport 内部监听器方案对照 |

## 4. 输出

- 测试报告 `docs/test-reports/2026-11-17-emqx-kafka-sink-spike.md`：逐项回答第 1 节的问题，并给出「去掉 transport」是否可行的结论与条件。
- 结论交所有者定 transport 去留，再写正式 spec。
- 验证环境用后停掉，保留与否交所有者决定。
