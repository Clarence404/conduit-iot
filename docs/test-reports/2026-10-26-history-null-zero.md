# 历史曲线缺测与零值修复测试报告

- 日期：2026-10-26；分支 `codex/v0.3b-rbac-design`。
- 范围：聚合空桶、历史图表和迷你趋势线空值；全局遥测值约定。
- 历史查询扫描参数/错误提示计划按用户要求保持暂停，本次没有改 Docker 参数、retention 或写入历史数据。

## 变更

聚合查询沿用 `BucketPolicy` 的有界桶宽并补齐 `[from,to)` 覆盖的桶，首桶按1970原点向下对齐。数值空桶 `[ts,null,null,null]`，状态空桶 `[ts,null]`；0、false和枚举0保留。API 返回的数据仅用于展示，不向数据库写入缺测占位。

前端 `HistoryPoint` 支持 null，波动带计算不再把 null 参与减法；所有折线、状态线、波动带和迷你趋势线设置不连接空点。趋势数据转换保留 null；全空序列显示「该时段没有数据」。原始稀疏数据保持原有返回方式，尚无采样周期/阈值，不把任意较大时间间隔自动视为缺测。

## 已执行验证

- TypeScript 检查通过。
- 前端专项：`npm test -- historyChart.spec.ts metricCards.spec.ts HistoryPanel.spec.ts sparkline.spec.ts`，5文件42项通过（新增迷你趋势空桶断言前）。
- 前端全量：`npm test`，65文件425项通过；覆盖 null 与0并存、布尔false、波动带缺测、迷你趋势线保留空桶。
- 后端：Java21、Testcontainers使用VM Docker临时环境，`mvn -B -pl conduit-server -am verify -Dit.test=HistoryApiIT -Dfailsafe.failIfNoSpecifiedTests=false`，附 Mockito 5.17.0 javaagent；common单元9、server单元247、HistoryApiIT集成8项通过。
- 实库集成验证：稀疏测点聚合从旧10个非空点变为240个时间桶，第11桶为 `[ts,null,null,null]`；布尔false和枚举0保持原值。
- 补充「缺桶后有真实0」单元断言后单独重跑 `HistoryServiceTest`：9项全部通过。
- `npm run build` 通过，包含 TypeScript 检查；保留既有 Vite 大chunk提示，无构建错误。
- 证据：各模块 `target/surefire-reports`、server `target/failsafe-reports`。
- 非阻塞警告：既有测试中 Vue change 事件声明提示；既有 Java API弃用提示。
- 未执行：完整多模块回归、e2e故障注入、尚未实现的导出功能与原始数据采样周期推断。

## 架构同步

已更新 README、CLAUDE.md第4节、全局数据语义文档和 API DTO 注释；检查现有系统/模块/组织SVG，组件拓扑、模块依赖与领域关系均未改变，无需改图。历史查询扫描上限计划明确标记暂停。
