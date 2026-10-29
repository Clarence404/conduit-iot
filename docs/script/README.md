# 脚本

项目的可执行工具，按用途分目录。

| 目录 | 用途 | 说明 |
|---|---|---|
| [`loadtest/`](loadtest/) | **压测**：容量测试、压力测试、改造前后回归对比 | 见 [`loadtest/README.md`](loadtest/README.md) |
| [`durability/`](durability/) | **持久性验证**：Redis AOF 与 EMQX 的故障注入实验，以及 `appendfsync` 的 `everysec` / `always` 对照 | 用法见 [`../durability-validation.md`](../durability-validation.md) |
| [`demo/`](demo/) | **演示数据**：生成「缺测 / 零值」历史，支撑 [`../telemetry-value-semantics.md`](../telemetry-value-semantics.md) 的语义演示 | `python3 docs/script/demo/seed-null-zero.py` |

## 凭据

`loadtest/config.json` 与 `loadtest/devices.json` 含地址、账号口令与设备密钥，
**已在 `.gitignore` 中，不得提交**。其余脚本的凭据一律从环境变量或命令行参数读取
（例如 `durability/verify.py` 读 `CONDUIT_SSH_PASSWORD`）。

仓库是公开的：不要把 IP、本机路径、用户名、口令、token 写进任何受版本控制的文件。

## 这些脚本各自产出过什么

| 目录 | 对应的测试报告 |
|---|---|
| `loadtest/` | [2026-10-29 容量测试](../test-reports/2026-10-29-capacity-after-batching.md)、[2026-10-28 瓶颈定位](../test-reports/2026-10-28-pipeline-redis-roundtrips.md) |
| `durability/` | [持久性验证](../durability-validation.md) |
| `demo/` | [遥测值语义](../telemetry-value-semantics.md) |
