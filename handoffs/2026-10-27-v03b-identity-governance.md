# V0.3-B3 身份治理交接

- 状态：进行中（2026-10-27）。完成后在本文件标记完成并链接最终测试报告。
- 目标：按 `docs/superpowers/specs/2026-10-26-conduit-v0.3b-organization-rbac-design.md` B3 实现邀请、可吊销会话、租户密码策略与 MFA 数据预留。用户额外决定：邀请走可插拔通知通道，首个实现为邮件。
- 授权：用户要求“开始下个任务”，已有演示数据与测试报告全局约定。任何新功能都需可复现演示；敏感凭据只从本地环境或 VM 安全配置读取，不写入本文件。

## 实时状态（接手时必须重新核对）

- 工作分支：`codex/v0.3b-identity-governance`，从 main `5187258` 切出。交接约定提交 `e05a581`；B3 功能提交 `cc2722c`。本次报告/交接更新在功能提交后追加，接手时用 `git status` 和 `git log -1` 复核；尚未合并 main。
- 演示 VM：2026-10-27 09:34（Asia/Shanghai）已将发布包 `cc2722c` 解压到 `/opt/conduit-releases/cc2722c` 并用该目录的 compose 更新 server、web、Mailpit。Web 8090 / API 8091 / Mailpit 8025；迁移到 V10、readiness UP。旧发布目录 `046687c` 保留；接手者仍须核对当前容器版本。
- 已实现的 B3 切片：V9 服务端会话与授权版本、JWT sid/av、刷新令牌轮换、登出/禁用/改权立即失效、个人会话列表/吊销及 UI；V10 一次性邀请、通知通道接口与 SMTP 实现、Mailpit 演示邮箱、邀请/接受 UI 和可复现脚本。账号邀请当前仅支持租户级内置管理员或只读角色。
- 已验证：全量 Maven 693/693、前端 431/431 与 build；新增严格模式/SMTP 单测 2/2、平台租户回归 12/12。VM readiness UP、Flyway V10、Mailpit 收到 1 封邮件；脚本两次运行均保持 1 邀请/2 有效会话/1 邮件。详见正式报告。
- 报告：`docs/test-reports/2026-10-27-identity-governance.md`。
- 演示：`docs/identity-governance.md`、`deploy/demo/identity-demo.py`；Web `http://192.168.126.128:8090`，Mailpit `http://192.168.126.128:8025`。一次性演示邀请尚未消费，方便用户自己操作；真实浏览器会话吊销交互仍未验证。

## 待完成

1. VM 上仍需验证真实浏览器的个人会话列表/吊销，以及一次性链接接受后登录。可另创建专用 smoke 邀请，保留 `identity-demo-viewer` 给用户；不要把 token 或新密码写进报告。
2. 本分支尚待推送/合并；提交前复核 `git status`、报告与 VM 状态。部署目录升级需保留旧 `.env` 与 PG 备份；回滚应用可用旧发布目录，但 V9/V10 数据库迁移不应直接回退到旧代码使用无 sid 令牌。
3. B3 spec 剩余：租户密码策略（最小长度、复杂度、历史、最长有效期、失败锁定，含平台下限）、MFA 方法/恢复码数据预留；邀请可携带完整初始范围角色草稿（当前只支持租户级角色）。为剩余功能分别补演示和测试报告。
4. 核对 `CLAUDE.md` §1.2 和 §4、README、架构 SVG、standalone README 与 spec 状态是否都同步；此任务已改部分文本和 SVG，仍需检查最终状态。
5. 上线真实环境前检查密钥、邮件地址和 Mailpit 暴露范围；后续提交/推送/合并时记录最终提交与版本。

## 风险与验证重点

- 生产默认拒绝旧无 sid 的 JWT，升级后需重新登录；测试 profile 为兼容既有直接造 JWT 的旧测试才启用 legacy，不能带到生产。
- Mailpit 只适合演示环境，正式 SMTP 应走安全配置；`CONDUIT_PUBLIC_URL` 必须是收件人可访问的 Web 根地址。邮件失败时邀请事务回滚；真实 SMTP 至演示 Mailpit 投递已验证。
- 无明文邀请/刷新 token 落库；交接与报告不得记录密码、token 或完整邀请链接。仅描述从环境变量/VM `.env` 安全读取的方法。
