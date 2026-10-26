# 租户隔离加固测试报告

- 日期：2026-10-22
- 分支：`codex/tenant-isolation`（由 `main` 创建）
- 对应设计：`docs/superpowers/specs/2026-10-22-conduit-tenant-isolation-design.md`
- 结论：通过。V5 已由 Flyway 应用于开发测试库和演示 VM；完整构建、越权测试及 5 个可靠性 E2E 场景全部通过。

## 1. 实施范围

- PostgreSQL：为 `device`、`product_property` 增加 `(tenant_id, product_id)` 到 `product(tenant_id, id)` 的组合外键，保留属性级联删除。
- 应用隔离：跨租户 Mapper 集中到 `dev.conduit.server.tenant.system`；调用必须显式进入 `TenantContext.callAsSystem`，并记录用途日志。
- 事务边界：活跃事务已经绑定租户后，禁止切换到其他租户或系统模式。
- Redis/InfluxDB：继续采用共享存储逻辑隔离；访问前必须通过受租户过滤的 PostgreSQL 查询取得设备身份。
- 测试：补充数据库约束、系统通道、事务断言、Mapper 架构、锁 SQL 改写和跨租户 API 测试。

## 2. 迁移前强制检查

演示 VM `192.168.126.128` 上直接查询 PostgreSQL，而不是猜测约束名。

### 2.1 实际外键名

`pg_constraint` 返回：

- `device_product_id_fkey`
- `device_tenant_id_fkey`
- `product_property_product_id_fkey`
- `product_property_tenant_id_fkey`

V5 按实际名称删除旧的产品外键，没有依赖 PostgreSQL 默认命名规则。

### 2.2 脏数据

迁移前执行设计 §4.1 的两条查询：

- `device.tenant_id <> product.tenant_id`：0 行。
- `product_property.tenant_id <> product.tenant_id`：0 行。

没有发现脏数据，因此没有执行修复或静默清理。

## 3. 专项验证

### 3.1 `FOR UPDATE` 租户改写

`ProductMapper.lockById` 去掉豁免后，通过 MyBatis `StatementHandler` 捕获最终预编译 SQL：

`select id from product where id = ? and tenant_id = 5 for update`

确认租户条件位于 `FOR UPDATE` 前，SQL 可由 PostgreSQL 正确执行。现有 `ThingModelApiIT` 的并发属性新增/导入用例同时通过。

### 3.2 一事务一租户

覆盖结果：

- 事务中 A → B：拒绝。
- 事务中 A → 系统模式：拒绝。
- 事务开始时尚未绑定租户：允许首次绑定一种模式。
- 非事务调用：允许临时切换并在退出后恢复上下文。

完整测试没有命中设计中担心的三个现有调用场景；平台租户管理、物模型缓存和认证会话相关用例均通过。断言没有放宽。

### 3.3 跨租户 API

租户 A 对租户 B 的产品、属性、物模型、设备、最新值、历史、重置密钥和用户写操作均返回 404；列表不包含 B 的资源，概览计数只包含 A；用 B 的产品创建 A 的设备返回 404。

## 4. 自动化测试结果

运行环境：Liberica JDK 21.0.8；Testcontainers 使用演示 VM Docker Engine（SSH socket 隧道），容器回连地址为 `192.168.126.128`。

### 4.1 完整 Maven 验证

命令：`mvn -B verify`（Mockito 以显式 `-javaagent` 加载）。

结果：`BUILD SUCCESS`，五个主 Reactor 模块全部成功；失败 0、错误 0、跳过 0。

主要报告计数：

| 模块 | 单元测试 | 集成测试 | 结果 |
|---|---:|---:|---|
| conduit-common | 9 | 17 | 通过 |
| conduit-transport | 48 | 16 | 通过 |
| conduit-server | 247 | 246 | 通过 |
| conduit-simulator | 82 | 4 | 通过 |

XML 证据位于各模块的 `target/surefire-reports` 与 `target/failsafe-reports`。

### 4.2 可靠性 E2E

先执行 `mvn -B -DskipTests install`，再执行 `mvn -B -Pe2e -pl conduit-e2e verify`。

`ReliabilityE2EIT`：5 个场景全部通过，失败 0、错误 0、跳过 0，耗时约 201.9 秒：

1. 上报途中终止并重启 server；
2. 终止当前主 Transport 并完成主备切换；
3. 同时终止两个 Transport 后恢复持久会话；
4. 重启 EMQX 后恢复；
5. 对 EMQX 发送 `SIGKILL` 后恢复。

日志目录：`conduit-e2e/target/e2e-logs/20261022-150012`。

## 5. 演示 VM 迁移验证

将本次构建的 `conduit-server.jar` 作为一次性迁移进程运行，Flyway 日志确认：

- 迁移前版本：4；
- 执行：`5 - tenant scoped fk`；
- 结果：成功应用 1 个迁移，当前版本 v5。

迁移后回查 `flyway_schema_history`：版本 5、`tenant scoped fk`、`success = true`。实际约束为：

- `fk_device_product`: `(tenant_id, product_id) -> product(tenant_id, id)`；
- `fk_product_property_product`: `(tenant_id, product_id) -> product(tenant_id, id) ON DELETE CASCADE`。

迁移后再次执行两条脏数据查询，仍均为 0 行。

## 6. 无效尝试与环境说明

以下问题均已显式保留，没有当作通过：

- 本机默认 JDK 17 无法编译 Java 21 项目，后续固定使用 Liberica JDK 21.0.8。
- 沙箱内不能访问 SSH 转发的 Docker 端口；经授权后在允许网络访问的环境重跑并通过。
- Mockito 在当前运行环境自附加会挂起；显式使用 `mockito-core-5.17.0.jar` 作为 Java agent 后测试通过。
- `ProductLockTenantSqlIT` 初次断言误以为租户值仍是占位符；实际 MyBatis-Plus 生成字面量 `tenant_id = 5`。测试改为验证实际 SQL 的租户条件位置及 `FOR UPDATE` 结尾，随后通过；没有放宽“必须含租户条件”的判据。
- VM 第一次一次性迁移进程继承了 `.env` 的宿主机端口 `15432`，在容器网络中连接失败，数据库未发生变化。显式指定容器内端口 `5432` 后重跑成功。
- V5 成功后，一次性进程继续初始化应用时因 `.env` 未直接提供应用所需的 32 字节 `CONDUIT_JWT_SECRET` 而退出；这不影响已经提交并回查成功的 Flyway 事务。该进程只用于迁移，并非演示服务发版。

## 7. 文档与架构检查

- 已同步 `CLAUDE.md` §1.2 的租户约束，并在第 4 节记录不采用 RLS 的决策与重新评估条件。
- 按要求未修改已经随设计更新的 `docs/roadmap-saas.md`。
- 已检查 README 和现有 SVG。此次变更没有新增服务、改变部署拓扑、协议流向或组件关系，因此无需修改架构图。

## 8. VM 演示部署与烟测

- 部署目标：`192.168.126.128`
- 部署目录：`/opt/conduit-demo-tenant-isolation`
- 当前版本链接：`/opt/conduit-current -> /opt/conduit-demo-tenant-isolation`
- 部署方式：以当前 `codex/tenant-isolation` 工作区构建后端 JAR 和前端静态资源，使用 `BUILD_FROM=prebuilt` 构建 standalone 镜像。
- 数据处理：先对旧开发编排执行 `docker compose down`，**没有使用 `-v`**；PostgreSQL、Redis、InfluxDB 和 EMQX 数据卷全部沿用。旧发布目录 `/opt/conduit-demo-3770442` 保留，可用于回退。

执行 `docker compose up -d --build --remove-orphans --wait --wait-timeout 300` 成功，完整 standalone 编排启动。烟测结果：

| 检查项 | 结果 |
|---|---|
| Server readiness | HTTP 200，`{"status":"UP"}` |
| Web 首页 | HTTP 200 |
| 默认租户管理员登录 | HTTP 200 |
| 默认租户概览 | HTTP 200；产品 2、设备 110、在线 110 |
| Flyway | 最新版本 `5:true` |
| Redis 最新值 | 174 个设备键 |
| Redis在线状态 | 174 个设备键 |
| 应用启动日志 | 最近 3 分钟未发现 `ERROR` 或异常堆栈 |

构建说明：第一次组合构建命令错误地在 `conduit-ui` 目录运行 Maven，因该目录没有 `pom.xml` 立即失败，未生成后端产物；随后在仓库根目录重新执行 `mvn -B -DskipTests package` 并成功。前端使用当前环境 Node.js 20.19.2 构建成功；Vite 仅报告既有的大 chunk 警告，不影响部署。
