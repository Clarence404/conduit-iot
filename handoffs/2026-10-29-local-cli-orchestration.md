# 本地 CLI 编排交接

- 状态：等待用户完成 OpenCode 登录；未完成前不派发实际仓库任务。
- 目标：在 Windows 本机以 Claude Code 统筹、Codex 实现、OpenCode 独立复核的方式协作。
- 已完成：安装 OpenCode CLI `v2.2.0`；确认 `claude`、`codex`、`opencode` 均在 PATH；将编排技能安装到用户目录 `C:\Users\ChenHan\.claude\skills\orchestrate`，并创建本机执行器配置 `executors.local.md`。
- 当前策略：Claude Code 写任务简报、审查和验收；Codex 只在独立 Git worktree 中改代码；OpenCode 只读复核，不自动应用输出。简报禁止包含 `.env`、密钥、生产数据或个人信息；不授权迁移、数据写入、安装依赖、提交或推送。
- 验证：OpenCode 在临时空目录运行 `PONG` 探针，返回 `provider.auth` / 401，原因是本机 OpenCode API key 已过期；未读取或发送 Conduit 源码。Claude Code 与 Codex 已安装可发现。项目工作树在本次操作后无改动（本交接文件除外）。
- 下一步：用户在交互终端执行 `opencode auth login` 完成账户认证；接手者在空目录重跑不含仓库内容的 `PONG` 探针，成功后将 `opencode-free-review` 标记为已验证，再以一个只读代码审查任务验证三方流程。
- 风险：Zuse 不提供 Windows 版本，未部署。另一个 Windows 图形编排器的发布 EXE 无有效签名，且已知公开哈希与实测文件不一致，已停止安装；不得绕过该核验。
