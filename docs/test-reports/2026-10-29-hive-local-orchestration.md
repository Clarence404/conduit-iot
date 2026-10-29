# Hive 本地多 CLI 编排部署验证报告

- 日期：2026-10-29（Asia/Shanghai）
- 目标：在 Windows 本机部署可为不同角色选择 Claude Code、Codex、OpenCode 的本地开源任务编排界面。
- 结论：Hive `v2.2.1` 已启动并可访问；未启动任何 agent 任务。OpenCode 凭据过期，故三 CLI 联合任务尚未验收。

## 环境与部署

- Hive：`@tt-a1i/hive@2.2.1`，安装在用户 Node `v22.23.2` 的全局目录；不改变当前默认 Node `v20.19.2`，避免影响既有 CLI。
- 地址：`http://127.0.0.1:9483`，仅本机回环监听。
- CLI 发现：`claude`、`codex`、`opencode` 均在 PATH。

## 验证结果

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| Hive 可启动 | 通过 | Node 进程启动，`GET http://127.0.0.1:9483` 返回 HTTP 200，页面标题为 `Hive`。 |
| 本机访问边界 | 通过 | 使用 `127.0.0.1` 地址启动，未配置远程访问或开机自启。 |
| CLI 可发现 | 通过 | Hive 启动环境可解析 Claude Code、Codex、OpenCode 命令。 |
| OpenCode 空目录探针 | 未通过 | 空目录执行不含源码的 `PONG` 请求返回 401 `provider.auth`；本机 OpenCode API key 已过期。 |
| 三角色真实协作 | 未执行 | 依赖 OpenCode 重新认证，并需先配置安全命令预设。 |

## 安全发现与限制

Hive 内置 Claude Code 与 Codex 预设包含跳过权限/沙箱的参数。部署过程中没有创建成员或任务，也没有使用这些预设。

首次使用前必须在 Hive Settings 创建不带自动批准参数的自定义命令预设，再给协调、实现、审查、测试角色绑定这些预设。不得把 `.env`、密钥、生产数据或个人信息放入任务描述或附件。

## 后续验收

1. 用户在交互终端执行 `opencode auth login`，完成 OpenCode 认证。
2. 在空目录重新运行 `PONG` 探针；通过后记录实际模型与结果。
3. 在 Hive 建立 Conduit workspace 和安全角色映射，先运行一个只读审查任务，不允许代码编辑、部署、迁移或提交。
4. 验证任务记录、角色路由、取消和日志可见性后，再允许实现角色进入独立 worktree。
