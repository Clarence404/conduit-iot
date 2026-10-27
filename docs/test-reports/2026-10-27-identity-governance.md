# V0.3-B3 身份治理切片测试报告

- 日期：2026-10-27（Asia/Shanghai）
- 分支：`codex/v0.3b-identity-governance`；最终提交和演示 VM 版本待补。
- 范围：会话注册/吊销、授权版本、刷新轮换、一次性邮件邀请及当前 UI。

## 已通过

- Flyway V9/V10 在 Testcontainers PostgreSQL 16.8 中从空库顺序迁移成功。
- 后端认证专项：`SessionIT` 17/17；单会话吊销后旧 access 下一请求 401，另一会话继续有效；登出和版本递增后旧 access 401，代入/退出租户与刷新回归通过。
- 后端邀请专项：`InvitationIT` 2/2；发送接口经注入的可替换通道取得邀请链接，只保存 token 哈希；接受后可登录，重复接受或撤销后再访问均失败。该专项用测试通道，未把单元测试等同于真实 SMTP 投递。
- 授权及个人接口专项：`MeApiIT + AuthorizationIT` 16/16；改权后旧令牌为 401，重新登录后按新范围得到资源级 404。
- 全仓库 `mvn verify`：693 项通过、0 失败、0 错误、0 跳过；`conduit-server` 517 项（单测 249、集成 268），其余模块合计 176。命令：在 JDK 21、Testcontainers 可访问 Docker 的环境下运行 `mvn -B -DargLine=-javaagent:<mockito-core jar> verify`；本机 Docker 连接为独立环境变量，不写入报告凭据。证据为各模块 `target/surefire-reports`、`target/failsafe-reports`。
- 前端 `npm run build` 通过；`npm test -- --run` 68 个文件、431 项通过。`python -m py_compile deploy/demo/identity-demo.py` 和 `git diff --check` 通过。
- 全量完成后补跑 `UserSessionRegistryTest`（生产无 sid 拒绝）、`SmtpInvitationChannelTest`（邮件适配器调用）2/2，以及 `PlatformTenantApiIT` 12/12（租户停用时版本递增改动）；均通过，证据为对应 Surefire/Failsafe XML。

## 失败后修正

- 首轮全量后端 268 项中 6 条旧测试失败：旧测试在撤权或登出后继续持有同一 access token，原预期资源级 404/继续操作；新会话版本规则先拒绝该旧令牌并返回 401。保留服务端立即失效规则，更新测试为先断言旧令牌 401，再重新登录检查资源范围；专项重跑 16/16、全量重跑 693/693 通过。没有放宽失效断言。

## 待验证和限制

- 测试 profile 为兼容旧测试中直接构造 JWT 的方式开启 `allow-legacy-sessions`；生产默认关闭，且需另查部署配置没有误开启。
- SMTP 到 Mailpit 的真实投递、邮件链接在浏览器接受、页面会话列表与吊销、`deploy/demo/identity-demo.py` 对当前 VM 的真实演示尚未执行；不能据以上单元/集成测试声称 VM 演示已通过。
- 未执行 `conduit-e2e -Pe2e` 故障注入套件。本切片不改设备接入或遥测处理链路；后端/前端常规回归已执行。
- B3 spec 的租户密码策略、密码历史/过期/失败锁定、MFA 数据预留和邀请完整作用域草稿未实现，不在本报告通过范围内。

## 演示准备

- 演示脚本 `deploy/demo/identity-demo.py` 只在 `default` 演示租户使用，通过 API 幂等准备 `identity-demo-viewer` 待接受邀请以及两条可查看会话；收件邮箱 `identity-demo-viewer@example.test` 只用于 Mailpit，不是实际业务邮件。脚本不改已有密码或令牌，也不打印邀请链接。
- 预期入口：Web「系统管理 → 用户 → 邮件邀请」、头像「个人设置 → 登录会话」、Mailpit `http://<演示宿主机>:8025`。实际 VM 地址与运行结果待部署验收后补；详见 `docs/identity-governance.md`。
