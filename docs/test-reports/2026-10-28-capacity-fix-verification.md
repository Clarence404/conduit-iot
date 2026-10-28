# 容量参数与内存上限改动验证

- 日期：2026-10-28
- 范围：`SessionMonitor` 判据更正、`batch-size`/`max_inflight` 默认值 64/1024、流水线批大小 20000、standalone 容器 `mem_limit`
- 命令：`mvn -B -o verify -pl conduit-transport,conduit-server`（`DOCKER_HOST` 指向开发虚拟机）

## 结果

| 模块 | 单元测试 | 集成测试 | 结论 |
|---|---|---|---|
| conduit-transport | 82 通过 | 16 通过 | 通过（含新增 SessionMonitor 用例） |
| conduit-server | 258 通过 | **268 个全部 Error** | 集成测试未能验证 |

conduit-server 集成测试失败原因：Spring 上下文缺少 `PrometheusMeterRegistry` bean（`internalMetricsController` 无法装配）。
**该失败在改动前的基线（`git stash` 后单跑 `AuthIT`，7 项全 Error，同一原因）同样存在，与本次改动无关**，未在本次处理。
因此 `conduit.pipeline.batch-size` 默认 20000 对 `PipelineIT` 等集成测试的影响**未验证**。

## 未验证
- compose 仅用 Python `yaml.safe_load` 校验语法与 `mem_limit` 位置，未用 `docker compose config`（本机无 docker）。
- e2e（`-Pe2e`）未运行；`E2eEnvironment` 的 EMQX 窗口已改为 1024 以匹配默认批 64。
- 设内存上限后的吞吐、1024/64 长时间表现均未测。
