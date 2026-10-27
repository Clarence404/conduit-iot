# 身份治理（V0.3-B3 当前切片）

当前已提供：邮件邀请、会话登记/吊销、刷新令牌轮换和授权版本失效。B3 spec 中的租户密码策略、密码历史/过期/失败锁定与 MFA 数据预留**已于 2026-10-27 决定不实施**（见 `CLAUDE.md` 第 4 节），不再作为待办跟踪；邀请携带完整范围角色同样已决定不实施。密码规则因此保持全局固定的 8～64 位含字母和数字，登录失败不做锁定或限流。

## 操作入口

- 管理员打开「系统管理 → 用户 → 邮件邀请」，填写邮箱、用户名和租户级角色。邮件送到配置的通知通道；列表能看到待接受、已接受、已撤销和已过期状态。链接在 48 小时内有效，只能接受一次。
- 收件人点击邮件链接，在 `/invitations/accept` 设置密码，然后用邮件里的用户名登录。邀请角色只有租户管理员或只读成员两种，接受时写入正式角色绑定；这是最终形态，需要项目/站点级范围的成员请在接受邀请后由管理员到用户页配置资源权限。
- 任一登录用户打开右上角头像 →「个人设置 → 登录会话」，查看和退出单条或全部会话。禁用账号、重置密码、修改角色/绑定后，旧 access token 在下一请求返回 401；重新登录后按新权限访问，资源越权仍返回 404。

## 通知通道

服务端的 `InvitationNotificationChannel` 是扩展接口，当前 `email` 插件通过 Spring `JavaMailSender` 使用 SMTP。邀请创建时必须找到所选插件、配置公开 Web 地址并成功发送；否则返回 503，数据库邀请事务回滚。数据库只保存令牌 SHA-256 哈希，不保存链接或明文 token，日志也不输出。

独立演示包默认内含 Mailpit，Web 收件箱为 `http://<宿主机>:8025`，SMTP 在 Docker 内部 `mailpit:1025`。正式环境可将 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` 指向真实 SMTP，并关闭 Mailpit。`CONDUIT_PUBLIC_URL` 必须设置为收件人可打开的 Web 根地址，例如 `http://<宿主机>:8090`；它仅用于生成邀请链接。

Mailpit 是演示收件箱，只有演示环境使用；正式邮件接入前应使用受控 SMTP 和 HTTPS 公开地址。可通过新增 `InvitationNotificationChannel` 实现其他通知方式，不需改变邀请生命周期和存储。

## 会话升级行为

Flyway V9 新增 `user_session` 和 `app_user.authz_version`，V10 新增 `user_invitation`。生产环境默认拒绝不含 `sid` 的旧令牌，升级后需重新登录。refresh 令牌仅在数据库留 SHA-256 哈希，同一会话每次刷新原子轮换；旧 refresh Cookie 再使用返回 401。退出租户仍保留同一会话，修改密码则吊销所有旧会话并创建新会话。

## 可复现演示

在明确的演示环境、独立包启动并配置 `CONDUIT_PUBLIC_URL` 后，用已有默认演示租户管理员口令执行：

```bash
CONDUIT_API_URL=http://localhost:8091 \
CONDUIT_DEMO_ADMIN_USERNAME=admin \
CONDUIT_DEMO_ADMIN_PASSWORD='<从演示环境安全配置读取>' \
python3 deploy/demo/identity-demo.py
```

脚本只接受 `default` 演示租户：保持至少两条可查看会话；若不存在 `identity-demo-viewer` 账号或待处理邀请，就向 `identity-demo-viewer@example.test` 发一封邀请。重复运行不会覆盖账号、密码、邀请或人工设置；不打印口令与 token。到「用户 → 邮件邀请」查看待接受状态，到 Mailpit 查看链接；接受后可用自己设置的密码登录，已接受的邀请不会再生成。

演示结果与限制以 `docs/test-reports/2026-10-27-identity-governance.md` 为准。
