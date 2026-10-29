# 本地 CLI 编排交接

- 状态：Hive 已在本机启动，等待用户完成 OpenCode 登录并在 Hive 内创建安全命令预设和角色；未派发实际仓库任务。
- 目标：在 Windows 本机使用 Hive 提交和跟踪任务，为协调、实现、审查、测试等角色分别绑定 Claude Code、Codex 和 OpenCode CLI。
- 已完成：安装 OpenCode CLI `v2.2.0`；确认 `claude`、`codex`、`opencode` 均在 PATH；Hive `v2.2.1` 安装于 Node `v22.23.2` 的独立全局目录，未切换系统当前 Node `v20.19.2`；Hive 运行在 `http://127.0.0.1:9483`，健康检查 HTTP 200。浏览器已打开该地址。
- 安全状态：Hive 内置 Claude/Codex 命令预设带跳过权限/沙箱参数，不能用于本项目。尚未创建成员或任务；接手者不得直接选择内置预设。应在 Hive Settings 创建不带自动批准参数的自定义预设，分别绑定 `claude`、`codex`、`opencode`，再由角色使用这些安全预设。
- 验证：OpenCode 在临时空目录运行 `PONG` 探针，返回 `provider.auth` / 401，原因是本机 OpenCode API key 已过期；未读取或发送 Conduit 源码。Claude Code 与 Codex 已安装可发现。项目工作树在本次操作后无功能代码改动（本交接文件除外）。
- 下一步：用户在交互终端执行 `opencode auth login` 完成认证；在 Hive 创建安全 CLI 预设、角色（建议协调/实现/审查/测试）和 Conduit workspace；接手者先用不含源码的空目录 PONG 探针复验 OpenCode，再以只读审查任务验证三 CLI 流程。
- 风险：Zuse 不提供 Windows 版本，未部署。另一个 Windows 图形编排器的发布 EXE 无有效签名，且已知公开哈希与实测文件不一致，已停止安装；不得绕过该核验。Hive 服务目前仅为当前登录会话启动，未注册为 Windows 开机自启。
