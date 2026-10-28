# 入库端到端延迟指标测试报告（2026-10-28）

被测范围：分支 `feat/ingest-latency`，`PipelineProcessor` 新增 Timer `conduit.pipeline.ingest.latency`（仅实时消费、成功入库后记录）。

环境：JDK 21（liberica 21.0.8），Testcontainers 使用开发虚拟机 Docker。

命令：
- `mvn -pl conduit-common install -B -o -DskipTests`
- `mvn -B -o verify`

结果：BUILD SUCCESS。各模块 surefire/failsafe 汇总 9 + 20 + 87 + 16 + 266 + 277 + 82 + 4 = 761 项，0 失败、0 跳过（基线 757，新增 4 项）。

新增用例（PipelineProcessorTest）：样本数等于成功条数且总时长正确；死信消息不记录；时钟漂移（receivedAt 晚于当前）记 0 而非负值；重放路径（TelemetryIngestor + REPLAY）不触及该指标。

说明：未在真实环境抓取 /actuator/prometheus 验证导出名，导出名依据 Micrometer 命名规则推导。
