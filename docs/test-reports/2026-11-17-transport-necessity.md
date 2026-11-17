# transport 有没有必要：EMQX 直写 Kafka 与直写 Redis Streams 对照验证（2026-11-17）

**测试类型**：技术验证（spike）。包括故障注入的压力测试，以及与 transport 方案的回归对比。
**前序报告**：[EMQX 直写 Kafka 验证](2026-11-17-emqx-kafka-sink-spike.md)；transport 方案见 [内部监听器验收](2026-11-16-transport-internal-listener.md)。
**所有者的问题**：不论大小集群，到底有没有必要保留 transport？

## 结论

1. **直写 Kafka：transport 没有必要。** 吞吐、Kafka 变慢、补传洪峰都守恒；Kafka 停机时 EMQX 有可以落盘的缓冲，不丢。恢复后的重复放大与 `max_inflight` 有关，设为 1 后，75,000 条里只重复 18 条。
2. **直写 Redis Streams：比 transport 方案弱，不能直接替代。**
   - 缓冲有默认 45 秒超时，Redis 停 60 秒会丢 3.5～4.8 万条。改成永不过期后，停机仍丢 17 条：发送时直接判失败，不重试。
   - 缓冲不能跨 EMQX 重启保留，选「溢写磁盘」模式也一样，强杀 EMQX 时整批丢。
   - Redis 每条写入都刷盘，写入速度跟不上补传洪峰，积压全部堆进 EMQX 内存，EMQX 被系统 OOM 强杀。这一轮我的去重脚本也占了内存，加重了这个结果。
3. **所以「要不要 transport」取决于总线用什么，而不是集群大小。**
   - 总线是 Kafka：去掉 transport，由 EMQX 直写。
   - 总线是 Redis Streams：要么保留 transport（已验证可靠），要么接受直写 Redis 的上述短板。
4. **建议统一用 Kafka**：小集群用单节点、小堆的 Kafka，大集群用 Kafka 集群，transport 全部去掉，只维护一套架构。代价是小集群多一个组件，内存约多 0.5～1 GB，需要实测。如果小集群坚持不上 Kafka，就保留 transport，那就是两套接入架构并存。

## 1. 环境

- 129 上的验证环境 `/opt/emqx-kafka-spike`，不影响拆分部署：
  - `emqx/emqx:5.10.5`，自带 Kafka 与 Redis 数据集成，社区 License；
  - Kafka 4.0 单节点：主题 `conduit-up`，16 个分区；
  - Redis 7.4.2：AOF，`appendfsync always`，与 `deploy/dev` 一致。
- 直写 Redis 的规则：从主题拆出 `pk` / `dk`，`payload` 做 Base64，`receivedAt` 取 `timestamp`，`attr.topic` 取主题；然后执行 `XADD conduit:stream:up`。
  - **写入的字段与 transport 完全一致**，已核对一条样本，现有 server 可以不改代码直接消费。
- Redis 动作：`batch_size` 64，`inflight_window` 100，`query_mode = async`；缓冲按场景取 `memory_only` 或 `volatile_offload`。
- 发压与计数沿用前序报告：PUBACK 对比 Kafka offset 增量或流长度增量。去重方法：读出当轮新增的全部消息，按 payload 去重；payload 里有每台设备唯一的 ts。

## 2. 直写 Redis Streams 的结果

| 场景 | 缓冲 | PUBACK | 流新增 | 去重后 | 动作计数 | 结论 |
|---|---|---|---|---|---|---|
| R0 基线：2,000 台 × 每秒 1 条，120 秒 | 内存 | 239,984 | 239,984 | 239,984 | 全部成功 | ✅ |
| R1 停 Redis 60 秒 | 内存 | 359,984 | 324,710 | 324,710 | `dropped.expired` 35,237，`failed` 40 | ❌ 少 35,274 |
| R2 同上 | 溢写磁盘 | 359,998 | 324,638 | 324,638 | `dropped.expired` 35,344 | ❌ 少 35,360 |
| R3 暂停 Redis 60 秒 | 溢写磁盘 | 359,997 | 312,411 | 312,395 | `dropped.expired` 47,618 | ❌ 少 47,602 |
| R4 停 Redis 期间强杀 EMQX | 内存 | 143,143 | 83,447 | 83,447 | 重启后计数归零 | ❌ 少 59,696 |
| R4 同上 | 溢写磁盘 | 142,321 | 82,700 | 82,700 | 同上 | ❌ 少 59,621（溢写的磁盘文件不跨重启保留） |
| R5 1,000 台 × 每秒 2 / 5 / 8 / 12 条，30 测点 | 溢写磁盘 | 每档都等于流新增 | — | 无重复 | 全部成功 | ✅；12,000 条/秒时 p99 2.5 秒，接近上限 |
| R6 2.5 万台断网 300 秒后补传，每台每秒补 5 条 | 溢写磁盘 | 935,598 | 615,582 | 615,582 | 指标读取失败 | ❌ EMQX 被系统 OOM 强杀（beam.smp 常驻内存 3.9 GB），25,000 台全部掉线 |
| R1t 停 Redis 60 秒，`request_ttl = infinity` | 内存 | 359,986 | 359,969 | 359,969 | `failed` 17，`retried` 1,041 | ❌ 少 17，失败的不重试 |
| R3t 暂停 Redis 60 秒，`request_ttl = infinity` | 内存 | 359,992 | 360,028 | 359,992 | 全部成功 | ✅ 不丢，36 条重复 |

**R6 的环境说明**：R6 开始前一刻，我用来去重 R5 第四档（144 万条）的 `redis-cli` 占用了 3.9 GB 内存，于 18:27 被系统强杀；EMQX 在 18:39 被强杀。129 上同时运行着拆分部署的应用侧、Kafka 和 Redis，内存本来就紧张。所以 R6 失败的程度被测试环境放大了。但积压堆在 EMQX 内存里这个机制本身是成立的：同样的场景，直写 Kafka 时守恒。

## 3. Kafka 重复放大的对照

条件：500 台、内存缓冲、停 Kafka 60 秒；每轮先清空主题，结束后读出全部消息去重。

| `max_inflight` | PUBACK | Kafka 中 | 去重后 | 重复倍数 | 单条最多 |
|---|---|---|---|---|---|
| 10（默认） | 74,998 | 88,530 | 74,998 | 1.18 | 3 |
| 2 | 75,000 | 79,675 | 75,000 | 1.06 | 3 |
| 1 | 74,999 | 75,017 | 74,999 | 1.0002 | 2 |

- 三种取值都不丢。重复程度随 `max_inflight` 减小而明显下降。
- **前序报告中同样条件下出现过约 10 倍重复，单条最多 22 次**，这一轮 `max_inflight = 10` 只有 1.18 倍，波动很大，原因没有查明（可能和 Kafka 恢复耗时有关）。`max_inflight = 1` 只测了一轮，对吞吐的影响也没测。
- 每轮 EMQX 日志都有大量 `failed_to_fetch_metadata`（2,000 多次）与 `producer_is_still_disconnected_after_retry`（约 250 次），说明 Kafka 停机期间生产者一直在重试连接。

## 4. 三种方案对照

| | transport（现状） | EMQX 直写 Kafka | EMQX 直写 Redis Streams |
|---|---|---|---|
| 设备收到 PUBACK 的含义 | EMQX 已收到 | 相同 | 相同 |
| 总线短时不可用 | 消息排在 EMQX 会话队列（内存，8 × 10 万条），transport 重试 | 动作缓冲可落盘，不丢 | 默认 45 秒超时即丢；改成永不过期后仍有少量判失败不重试 |
| 强杀 EMQX | 会话队列丢失 | 内存缓冲全丢；磁盘缓冲丢约 2% | 全丢 |
| 补传洪峰 | 修正后守恒 | 守恒 | EMQX 内存耗尽 |
| 恢复后重复 | 少 | `max_inflight = 1` 时极少 | 少 |
| 横向扩展 | 需要改成身份池多活 | EMQX 集群 + Kafka 分区 | 受单流、单 Redis 限制 |
| 业务回执 | transport 写入后发 | 需改为 server 入库后发 | 同左 |
| 依赖 | 任何 MQTT Broker 都能用 | 依赖 EMQX 数据集成（集群需商业版） | 同左 |
| 组件 | 多一个 transport 服务 | 多一个 Kafka | 最少 |

## 5. 跳过、阻塞与遗留

- `max_inflight = 1` 只跑了一轮，吞吐代价没测；前序报告中 10 倍重复的原因没有查明。
- 小集群单节点 Kafka 的最小内存和吞吐没测。
- R6 受测试环境内存影响，没有在内存充足的条件下重跑。
- 去重脚本一次读出 144 万条导致 129 内存紧张，以后要分段读取。
- 验证环境 `/opt/emqx-kafka-spike` 仍在 129 上；当前 EMQX 启用的是 Redis 规则，Kafka 规则已停用。
- 驱动脚本在 scratchpad 中，没有入库。
