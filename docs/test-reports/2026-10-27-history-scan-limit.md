# 历史查询文件扫描上限：根因、参数实测与错误分类

- 日期：2026-10-27（Asia/Shanghai）
- 分支：`main`
- 对应计划：`docs/superpowers/plans/2026-10-26-history-query-limits.md`（第一、二阶段）
- 范围：根因定位与参数实测、compose 参数固化、后端错误分类、前端失败状态；第三阶段（分段查询）未实施。

## 1. 根因（已确认，非推测）

对演示 VM 的 InfluxDB 3 Core 3.12.0 实测：

| 事实 | 取得方式 | 值 |
|---|---|---|
| `--gen1-duration` 默认值 | 容器内 `influxdb3 serve --help-all` | **10m** |
| 由此产生的文件数 | 实测 1575 个文件 / 12 天 | 约 **144 个/天** |
| `--query-file-limit` 默认值 | help 未列默认值，由报错反推 | **432** |
| 432 ÷ 144 | — | **恰好 3 天** |

`--help` 只输出摘要（31 个参数），完整参数需要 `--help-all`（98 个）；`--query-file-limit` 只出现在后者。

**单设备过滤不改善**。用与 `HistoryQueryBuilder` 相同的 WHERE（`tenant_id` + `product_key` + `device_key`）实测：

| 范围 | 结果 |
|---|---|
| 1 天 | HTTP 200，0.13s |
| 3 天 | **HTTP 500，扫描上限** |
| 7 天 / 30 天 | **HTTP 500，扫描上限** |

Core 不做 compaction，时间范围内的文件全部要打开，标签过滤不减少文件数。因此默认配置下**任何超过 2 天的历史查询都必然失败**，与历史接口对外承诺的「最长 30 天」不符。

错误报文（HTTP 500，固定）：

```
External error: Query would scan 432 Parquet files, exceeding the file limit.
InfluxDB 3 Core caps file access to prevent performance degradation and memory issues.
Use a narrower time range, or increase the limit with --query-file-limit ...
```

失败发生在**查询规划阶段**（耗时 0.01 秒），不是超时。

## 2. 参数实测

临时以 `INFLUXDB3_QUERY_FILE_LIMIT=6000` 重建 InfluxDB 容器（数据卷不动，并加 3 GB 内存上限兜底），用真实历史查询的两种形态测量：

| 范围 | 计数查询 | 聚合查询（1 小时桶） | 进程内存 |
|---|---|---|---|
| 1 天 | 0.21s | 0.14s | 169 MB |
| 3 天 | 0.24s | 0.18s | 274 MB |
| 7 天 | 1.09s | 1.18s | 431 MB |
| 12 天 | 0.85s | 0.86s | 508 MB |
| 30 天 | 1.00s | 0.57s | 521 MB |

基线 84 MB，稳定在约 520 MB，最高延迟约 1.2 秒。VM 配置 7.7 GB 内存、8 核。

**「30 天」这一行不是真正的 30 天**：库中最早数据为 2026-10-15，只有 12 天，所以 30 天范围返回的点数与 12 天一致（8766 / 8765）。**真实 30 天数据下的开销未实测**，按文件数线性外推约 1.4 GB、3 秒，属于推算，不计入通过。

## 3. 代码与配置改动

### 3.1 compose（第一阶段）

`deploy/dev/docker-compose.yml` 的 influxdb 服务新增，`deploy/standalone` 经 `extends` 继承：

- `INFLUXDB3_QUERY_FILE_LIMIT`（默认 `5000`）：按 30 天 × 144 个/天 = 4320 取整留余量。**这是唯一可用的杠杆**，原因见 3.4 的失败尝试。
- **不设 `INFLUXDB3_GEN1_DURATION`**：它只接受 1m/5m/10m，默认 10m 已是最大，无法降低文件数。
- `mem_limit`（默认 `3g`）：**原先完全没有内存限制**。这是独立于本问题的缺口：VM 共 7.7 GB，还运行 PostgreSQL、Redis、EMQX、server、两个 transport 与模拟器，一次失控查询可拖垮其余服务。

两个变量同步写入两个包的 `.env.example`。

### 3.4 失败的尝试：`--gen1-duration=1h`（已纠正）

最初判断「gen1 粒度是根因上的杠杆，调到 1h 可把文件数降到 24 个/天」，并据此写进了 compose、`.env.example`、`CLAUDE.md` 与计划文档。**部署到演示 VM 时 InfluxDB 启动即失败**，反复重启：

```
error: invalid value '1h' for '--gen1-duration <GEN1_DURATION>':
invalid gen1 duration 1h. Must be one of 1m, 5m, 10m
```

`--help-all` 只写了 `[default: 10m]`，没有列出取值范围，所以这一点在参数调研阶段没有暴露。

结论随之改变：**默认的 10m 已经是最大粒度，文件数无法降低**，144 个/天是下限，唯一杠杆是扫描上限。相应地上限从 3000 调到 5000（30 天需 4320 个，3000 只能覆盖约 20 天）。全部四处文档与配置已同步纠正。

**这次失败说明只做参数调研不足以验证编排改动**：如果不实际部署，一个会导致 InfluxDB 无法启动的配置会随提交进入仓库。

### 3.2 后端错误分类（第二阶段）

- `InfluxHttpException` 保留原始响应体，新增 `fileLimitExceeded()`，按 `parquet files` + `file limit` 两个关键词判定，不依赖具体数字与后半段的升级推广文案。
- `ApiException` 支持可选 `code`，`GlobalExceptionHandler` 将其写入 ProblemDetail 的 `code` 属性（与既有 `PASSWORD_CHANGE_REQUIRED` 的表达方式一致）。
- `HistoryService.influx()`：扫描上限 → **400 + `HISTORY_SCAN_LIMIT`**，提示「查询范围超过时序库限制，请缩短时间范围」；其余 5xx 仍为 503，其他错误仍为 500 且不暴露 SQL。

### 3.3 前端失败状态（第二阶段）

- 查询失败**不再弹 toast**：快捷范围每 30 秒自动刷新，持续失败会刷屏。改为常驻提示。
- 保留旧曲线时显示 `el-alert`，写明本次失败原因与**旧曲线对应的真实时间范围**（新增 `shownRange`）——此前工具栏已显示新选范围而图表是旧数据，容易误读。
- 首次加载失败（没有旧曲线）显示带「重试」按钮的空状态。
- 恢复成功后提示自动清除。

**迟到响应覆盖新范围**这一项核查后确认**既有实现已覆盖**（`createRequestSeq` + `seq.isLatest`），未重复实现。

## 4. 测试结果

### 4.1 新增用例

- 后端 `HistoryServiceTest.fileScanLimitIs400WithStableCode`：扫描上限报文 → 400、code `HISTORY_SCAN_LIMIT`、中文提示正确。`HistoryServiceTest` 共 **9 项通过**。
- 前端 `HistoryPanel.spec.ts` 新增 2 项：刷新失败保留旧曲线并标注其范围、恢复后提示消失；首次加载失败给出可重试空状态并能重试成功。该文件共 **19 项通过**（原 17）。

### 4.2 回归

- 前端全量：**68 个文件、431 项通过**；`vue-tsc --noEmit` 无错误。
- 后端全量 `mvn -B -o verify`：`BUILD SUCCESS`，**696 项通过，0 失败、0 错误、0 跳过**，用时 3 分 51 秒。
  分项（单元 + 集成）：common 9 + 17、transport 48 + 16、server 252 + 268、simulator 82 + 4。
  环境：Liberica JDK 21.0.8，Testcontainers 指向演示 VM 的 Docker Engine。
  计数核对：server 单测 249（本会话此前基线）+ 2（B3 合并带入的会话与 SMTP 单测）+ 1（本次新增）= 252，无意外增减。
  证据：各模块 `target/surefire-reports`、`target/failsafe-reports`。

## 5. 演示 VM 部署验证（2026-10-27）

按仓库参数（`INFLUXDB3_QUERY_FILE_LIMIT=5000`、内存上限 3 GB、不设 gen1）重建容器后：

| 检查项 | 结果 |
|---|---|
| 容器健康 | healthy（6 秒） |
| 聚合查询 1 天 | HTTP 200，0.14s，内存 107 MB |
| 聚合查询 7 天 | HTTP 200，0.35s，内存 156 MB |
| 聚合查询 30 天 | HTTP 200，0.58s，内存 202 MB |
| 写入链路 | 最近 2 分钟 2088 个点（174 台设备 × 10 秒间隔，吻合） |
| 最新样本时间 | 与当前时间一致 |
| `conduit-server` readiness | `{"status":"UP"}` |

内存低于第 2 节的测量值（202 MB vs 521 MB），因为进程刚重启、缓存为冷；第 2 节是连续查询后的稳定值。两者都远低于 3 GB 上限。

30 天一行仍受第 2 节同一限制：库中只有 12 天数据，不是真正的 30 天跨度。

## 6. 未执行与限制

- **第三阶段（分段查询）未实施**，仍留在 V0.3-E，实施前需补专项设计。
- **真实 30 天数据下的查询开销未实测**（库中只有 12 天），第 2 节的 30 天数字是 12 天的结果，外推值不计入通过。
- `gen1-duration` 改为 1h 后，**存量数据的文件粒度不变**；完全生效要等旧文件按 30 天保留期淘汰。过渡期内可查询跨度仍受存量文件数影响。
- **演示 VM 的容器是用 Docker Engine API 重建的，不是 `docker compose` 部署的**。本机没有 docker CLI，到 VM 也没有免密 SSH，因此无法执行编排。容器的镜像、命令、挂载、端口、网络别名、健康检查、重启策略均按原容器复制，并按仓库参数设置环境变量与内存上限；但它**不受 compose 管理**，下次在 VM 上执行 `docker compose up -d` 会按仓库配置重新创建。原容器以 `conduit-influxdb-1-orig` 保留（已停止）可回滚。
- 未执行 `conduit-e2e -Pe2e`：本次不涉及设备接入与遥测写入链路。
