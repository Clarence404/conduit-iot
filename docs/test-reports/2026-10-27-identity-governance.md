# V0.3-B3 身份治理切片测试报告

- 日期：2026-10-27（Asia/Shanghai）
- 分支：`codex/v0.3b-identity-governance`；功能提交 `cc2722c`，演示 VM 发布目录 `/opt/conduit-releases/cc2722c`。
- 范围：会话注册/吊销、授权版本、刷新轮换、一次性邮件邀请及当前 UI。

## 已通过

- Flyway V9/V10 在 Testcontainers PostgreSQL 16.8 中从空库顺序迁移成功。
- 后端认证专项：`SessionIT` 17/17；单会话吊销后旧 access 下一请求 401，另一会话继续有效；登出和版本递增后旧 access 401，代入/退出租户与刷新回归通过。
- 后端邀请专项：`InvitationIT` 2/2；发送接口经注入的可替换通道取得邀请链接，只保存 token 哈希；接受后可登录，重复接受或撤销后再访问均失败。该专项用测试通道，未把单元测试等同于真实 SMTP 投递。
- 授权及个人接口专项：`MeApiIT + AuthorizationIT` 16/16；改权后旧令牌为 401，重新登录后按新范围得到资源级 404。
- 全仓库 `mvn verify`：693 项通过、0 失败、0 错误、0 跳过；`conduit-server` 517 项（单测 249、集成 268），其余模块合计 176。命令：在 JDK 21、Testcontainers 可访问 Docker 的环境下运行 `mvn -B -DargLine=-javaagent:<mockito-core jar> verify`；本机 Docker 连接为独立环境变量，不写入报告凭据。证据为各模块 `target/surefire-reports`、`target/failsafe-reports`。
- 前端 `npm run build` 通过；`npm test -- --run` 68 个文件、431 项通过。`python -m py_compile deploy/demo/identity-demo.py` 和 `git diff --check` 通过。
- 全量完成后补跑 `UserSessionRegistryTest`（生产无 sid 拒绝）、`SmtpInvitationChannelTest`（邮件适配器调用）2/2，以及 `PlatformTenantApiIT` 12/12（租户停用时版本递增改动）；均通过，证据为对应 Surefire/Failsafe XML。
- 演示 VM：部署前 PostgreSQL 做了 `pg_dump -Fc` 备份（仅存于 VM 新发布目录，不入仓库）；新目录的 server readiness 为 `UP`，Flyway 最新版本 `10` 且成功，Mailpit 首页 HTTP 200。运行 `deploy/demo/identity-demo.py` 两次后，PostgreSQL 均为 1 条 `identity-demo-viewer` 邀请、2 条有效 admin 会话；Mailpit 均为 1 封邀请邮件。浏览器打开 `http://192.168.126.128:8090/invitations/accept` 显示页面和“邀请已失效”（无 token 的预期），打开 `http://192.168.126.128:8025` 显示收件箱 1 封邀请及正确的收件人/主题。完整链接令牌未记录在报告。

## 失败后修正

- 首轮全量后端 268 项中 6 条旧测试失败：旧测试在撤权或登出后继续持有同一 access token，原预期资源级 404/继续操作；新会话版本规则先拒绝该旧令牌并返回 401。保留服务端立即失效规则，更新测试为先断言旧令牌 401，再重新登录检查资源范围；专项重跑 16/16、全量重跑 693/693 通过。没有放宽失效断言。
- VM 演示脚本首次调用返回 400：命令从旧 `.env` 读取未设置的 `CONDUIT_ADMIN_USERNAME` 并把空字符串显式传入，覆盖了脚本的 `admin` 默认值。改用默认用户名后两次运行成功；脚本代码和服务无需修改。

## 待验证和限制

- 测试 profile 为兼容旧测试中直接构造 JWT 的方式开启 `allow-legacy-sessions`；生产默认关闭，且需另查部署配置没有误开启。
- SMTP 到 Mailpit 的真实投递、收件箱、可复现脚本和公开接受页已在 VM 验证。为保留用户可操作的演示邀请，未在 VM 上消费该一次性链接；接受后登录与重复接受的判据由 `InvitationIT` 在独立 Testcontainers 数据库通过。个人设置中会话列表/吊销的页面交互尚未做真实浏览器登录检查，后端接口集成测试已通过。
- 未执行 `conduit-e2e -Pe2e` 故障注入套件。本切片不改设备接入或遥测处理链路；后端/前端常规回归已执行。
- B3 spec 的租户密码策略、密码历史/过期/失败锁定、MFA 数据预留和邀请完整作用域草稿未实现，不在本报告通过范围内。

## 演示准备

- 演示脚本 `deploy/demo/identity-demo.py` 只在 `default` 演示租户使用，通过 API 幂等准备 `identity-demo-viewer` 待接受邀请以及两条可查看会话；收件邮箱 `identity-demo-viewer@example.test` 只用于 Mailpit，不是实际业务邮件。脚本不改已有密码或令牌，也不打印邀请链接。
- 演示入口：[Web](http://192.168.126.128:8090)「系统管理 → 用户 → 邮件邀请」、头像「个人设置 → 登录会话」、[Mailpit 收件箱](http://192.168.126.128:8025)；邀请资源名 `identity-demo-viewer`，收件人 `identity-demo-viewer@example.test`。用户可从邮件打开链接设置自己的密码。详见 `docs/identity-governance.md`。
