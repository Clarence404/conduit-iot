# 流水线 Redis 往返批量化验证

- 日期：2026-10-28
- 分支：`perf/pipeline-batch-redis`（基于 `ea367d3`）
- 范围：`DeviceIdentityCache.getAll`、`DeviceIdentityLookup.findAll`、`TelemetryIngestor` 四参 `prepare` 与 `updateLatestBatch`、`LatestValueStore.updateBatch/validate`、`PipelineProcessor` 接线。依据见 `2026-10-28-pipeline-redis-roundtrips.md`。
- 环境：本机 JDK 21，Testcontainers 经开发虚拟机 Docker（不涉及压测机）。
- 命令：`mvn -pl conduit-common install -B -o -DskipTests`，然后 `mvn -B -o verify`
- 结果：BUILD SUCCESS，总耗时 4 分 18 秒；各模块 surefire/failsafe 汇总用例数 9、20、87、16、262、277、82、4，合计 757，0 失败、0 跳过（基线 744）。
- 一致性验证：`LatestValueStoreIT.batchUpdateProducesExactlyTheSameStateAsOneByOneUpdates` 用同一组含乱序、同设备多条、同 ts、字符串/布尔值的输入，分别逐条 `update()` 与一次 `updateBatch()` 写入两组不同设备，比较 `get()` 结果与 Redis hash 原始内容（含 `__last`），完全相等；另有旧时间戳不覆盖新值（批量与逐条各一）、空批不发命令、含保留名的批整批拒绝且不写入。
- 未执行：未做压测机上的吞吐复测，报告中的 22,000 条/秒外推仍未验证。
