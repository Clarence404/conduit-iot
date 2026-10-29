# 平台吞吐总览测试报告（2026-10-29）

## 1. 被测范围与代码状态

平台管理员首页新增「实时吞吐」一行，以及支撑它的后端采样器。

| 层 | 新增 / 修改 |
|---|---|
| 后端 | `ThroughputSampler`（新增）、`PlatformDtos.PlatformOverview` 增加 `throughput`、`PlatformOverviewService` 注入采样器 |
| 前端 | `PlatformHomeView.vue` 增加「实时吞吐」四格、`api/types.ts` 增加 `ThroughputView` |
| 测试 | `ThroughputSamplerTest`（新增 7 例）、`PlatformHomeView.spec.ts`（新增 3 例）、`PlatformOverviewApiIT`（补 3 条断言） |

代码状态：分支 `docs/script-reorg`，基线提交 `070a45b`。

## 2. 环境

| 项 | 值 |
|---|---|
| JDK | Liberica 21.0.8 |
| 构建 | Maven（离线 `-o`） |
| 前端 | Node + Vitest 3.2.6、vue-tsc |
| Docker | **本机不可用**（`docker: command not found`） |

## 3. 实际执行的命令与结果

| # | 命令 | 结果 | 耗时 |
|---|---|---|---|
| 1 | `mvn -o -B -pl conduit-server test -Dtest=ThroughputSamplerTest` | **7 通过**，0 失败 | 0.212 s |
| 2 | `mvn -o -B -pl conduit-server test` | **273 通过**，0 失败 0 错误 0 跳过 | 见下 |
| 3 | `mvn -o -B -pl conduit-server test-compile` | 成功（确认 IT 新断言可编译） | — |
| 4 | `npx vitest run src/views/PlatformHomeView.spec.ts` | **10 通过** | 230 ms |
| 5 | `npx vitest run`（前端全量） | **68 文件 / 436 通过** | 42.71 s |
| 6 | `npx vue-tsc --noEmit` | 无输出（类型检查干净） | — |

命令 2 的 273 例 = 改动前的 266 例 + 本次新增的 7 例，无回归。

## 4. 重点验证的语义

遵循项目的全局遥测约定「null = 没有数据，0 = 真实的零」，这是本次最容易写错的地方，
因此在后端和前端各写了针对性用例：

| 场景 | 期望 | 覆盖它的用例 |
|---|---|---|
| 进程刚启动，一次都没采样 | 速率 `null`，累计 0 | 后端 `没有采样时速率未知而不是零` |
| 只采样一次（算不出差值） | 速率 `null` | 后端 `只采样一次仍然未知` |
| 采样两次、期间确实零流量 | 速率 **`0`**，不是 null | 后端 `真实的零流量速率是零而不是未知` |
| 速率未知 | 界面「—」+「采样中，还取不到速率」 | 前端 `tells "sampling" apart from a real zero rate` |
| 速率真实为零 | 界面「0」+「近 60 秒平均」 | 前端 `shows a real zero rate as 0` |
| 窗口滑过后流量停止 | 速率回落到 0，累计值不回退 | 后端 `缓冲填满后只统计最近窗口` |
| 死信有多个 `reason` | 累计值为各 reason 之和 | 后端 `死信按所有原因合计` |

## 5. 未执行的测试（阻塞项）

**`PlatformOverviewApiIT` 本次没有运行。** 该集成测试依赖 Testcontainers，
而本机没有 Docker（`docker: command not found`）。本次只做到：

- `mvn test-compile` 通过，确认新增的三条断言能编译；
- 人工核对该 IT 只读取具体的 JSONPath（`$.tenantCount` 等），新增字段不会影响既有断言。

**因此「接口返回的 `throughput` 字段在真实 HTTP 响应中形状正确」这一点尚未由测试证实**，
只有单元测试层面的证据。需在有 Docker 的环境补跑：

```bash
mvn -B verify -pl conduit-server
```

同样地，**没有在真实运行的服务上用浏览器看过这一行**——本次未启动整套本地服务做界面验收。

## 6. 非阻塞警告

`mvn test` 输出中有一行 `ERROR dev.conduit.server.telemetry.HistoryService -- History query rejected by InfluxDB`，
来自 `HistoryServiceTest` 对错误分支的预期断言，不是失败。

## 7. 日志位置

本次测试未产生需要留存的服务日志（未启动服务）。Surefire 报告在
`conduit-server/target/surefire-reports/`。
