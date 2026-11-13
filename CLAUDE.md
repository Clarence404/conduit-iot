# Conduit IoT 项目说明（给 Claude 与开发者）

**命名约定**
- 对外：产品名 `Conduit IoT`，仓库 `Clarence404/conduit-iot`。
- Maven：根父工程 `dev.conduit:conduit-iot`，展示名 `Conduit IoT`；子模块父引用必须一致，展示名以 `Conduit IoT ::` 开头。
- 其余技术标识沿用 `conduit`：Java 包名、子模块 artifactId、服务名、配置前缀、部署目录、数据卷。
- 旧命名 `Conduit` / `conduit-parent` 以当前 POM 为准。
- 产品定位变化时，同步检查 GitHub About 等对外简介。

**产品目标**：面向工业、能源、园区与设备运营的生产级多租户 IoT SaaS。
- 设备经 MQTT 接入，数据经消息总线进入服务端，按物模型处理后用于存储、规则、告警、控制与开放集成。
- 生产就绪状态以阶段验收与测试报告为准。本文件记录整体设计与编码约定。

**维护规则**
- 每次升级都要写 `docs/superpowers/` 下的 spec / plan。
- 决定性质的约定同步写进本文件对应章节：技术选型、模块职责、数据语义、接口、界面、部署。并在第 4 节追加一行。
- 与新决定冲突的旧描述直接改掉，不留过期内容。
- 讨论中确定的后续方向，即使暂不实施，也要当时写进 `docs/roadmap-saas.md` 或对应 spec。

**智能体交接（必须）**
- 跨智能体或跨任务未完成的工作，按 [`docs/handoffs/README.md`](docs/handoffs/README.md) 在 `docs/handoffs/` 维护同一任务的一份交接。
- **交接文件只能放在 `docs/handoffs/`**，放错的直接移入。
- 内容：已完成、未完成、验证证据、当前 Git 与部署版本、下一步、凭据的安全获取方式。不写口令、令牌、私钥。
- 接手者先核对实时状态。完成后标记「已完成」并链接报告与提交。
- 交接不替代 spec、roadmap、架构文档和测试报告。

**架构同步（必须）**
- 范围：凡影响模块职责与依赖、拓扑、数据流、协议与接口、存储、租户隔离、可靠性、部署方式的改动。
- 必须在同一变更中更新架构文档和图。至少检查：
  - `README.md` 架构说明
  - `docs/images/architecture.svg`
  - `docs/images/module-dependencies.svg`
  - 本文件第 1 节
  - 对应 spec / plan
  - 涉及可靠上报时检查 `docs/images/reliable-uplink.svg`；涉及后续路线时更新 roadmap
- 交付说明列出已更新的架构文件；无需修改时说明原因。
- 架构图只描述已实现状态，规划能力须标注。
- 图一律用支持亮暗主题的 SVG，同一架构只维护一个源文件，各处用 Markdown 引用。改图后自检文字、间距、连线与引用。

## 1. 整体设计

**新功能演示（必须）**
- 每个新功能同时交付：可操作的演示数据、入口和预期现象，不交付空页面。
- 关系数据写进版本化 Flyway 迁移或明确的 demo 初始化脚本（如 `deploy/demo/*.py`）；动态数据由模拟器或可重复脚本生成。
- 初始化必须幂等，不覆盖人工数据、密码和密钥，只在演示环境 / 演示租户启用。
- 覆盖主要场景、权限、状态与缺测边界。自测结果写入 `docs/test-reports/`；生成不了的部分要报告原因。
- 同一要求也记录在全局 `%USERPROFILE%/.codex/AGENTS.md`。

### 1.1 组件与数据流

![Conduit 当前系统架构：设备接入、同步鉴权、异步处理与存储](docs/images/architecture.svg)

**上行链路**
1. 设备经 MQTT 接入 EMQX，EMQX 同步回调 `conduit-server` 鉴权。
2. 主 `conduit-transport` 把上报组装为 `RawMessage`，写入 Redis Streams `conduit:stream:up`。
3. server 以消费组拉取，解码、校验后写入 InfluxDB 3 的 `property` 表和 Redis 最新值。

**存储与前端**
- Redis 还存在线状态与设备身份缓存。
- PostgreSQL 存租户、用户、组织、产品、设备、邀请、会话。
- Vue 前端调用同一 server 的 REST 接口。邀请邮件经 `InvitationNotificationChannel` 发往 SMTP，演示环境由 Mailpit 收取。

![Conduit 租户内组织模型与设备归属](docs/images/organization-model.svg)

| 模块 | 职责 |
|---|---|
| `conduit-common` | `MessageBus` 接口与 Redis Streams 实现、协议无关的 `RawMessage` |
| `conduit-transport` | 协议接入层，单活多备（Redis 选主）。主实例开 `connections` 条（默认 4）内存持久会话连接，以共享订阅分摊 EMQX 投递；把报文攒成微批写入总线；经 EMQX REST API 按连接监控会话；不做业务解析 |
| `conduit-server` | EMQX 鉴权回调、总线消费流水线（解码 → 物模型校验 → 时序库 / 最新值）、REST API、多租户与用户、邀请 / 会话、租户内组织与设备归属 |
| `conduit-simulator` | 演示设备模拟器与可靠上报参考实现：自动建演示产品 / 设备，按物模型上报；收到业务回执才删待确认消息，超时重发、队列满背压 |
| `conduit-ui` | Vue 3 + TypeScript + Element Plus + ECharts |
| `conduit-e2e` | `-Pe2e` 端到端测试：Testcontainers + 本地 jar，五个故障场景不丢数据 |
| `deploy/` | `dev/` 开发基础服务、`standalone/` 独立部署、`split/` EMQX 与应用分机、`demo/` 演示初始化脚本、`package.sh` 打包 |

### 1.2 关键设计决定

#### 租户、组织与权限

**多租户**（V0.2-C，10-22 加固；隔离方案定为不采用 RLS，见第 4 节）

*租户过滤*
- `product`、`device`、`product_property` 等业务表带 `tenant_id`，由 MyBatis-Plus 租户拦截器按令牌中的租户自动过滤。
- `tenant`、`app_user` 不过滤；按租户查用户必须显式带 `tenant_id`。
- 关联表用 `(tenant_id, …)` 组合外键，数据库层拒绝跨租户关联。

*跨租户访问*
- 跨租户 Mapper 只能放在 `dev.conduit.server.tenant.system` 包，并标注 `@InterceptorIgnore`。
- 调用必须经 `TenantContext.callAsSystem(purpose, …)`，每次进入记一行 INFO。
- 一个事务只服务一个租户；已绑定租户的事务不得切换租户或进入系统模式。

*Redis / InfluxDB 访问*
- 必须先经受租户过滤的 PostgreSQL 查询拿到设备，再用查出的 `deviceKey` 读写，禁止直接用请求参数中的 `deviceKey`。
- `conduit:auth:{deviceKey}` 是唯一的平台级例外：缓存身份必须携带可信的 `tenantId`，后续处理只采用该值。

*角色与账号规则*
- 角色：`PLATFORM_ADMIN`（`tenant_id` 为空，数据库约束保证）、`TENANT_ADMIN`、`TENANT_VIEWER`。
- 用户名 3～32 位，字母开头（字母、数字、`_-.`），全平台唯一。
- 密码固定为 8～64 位、含字母和数字，BCrypt 存储。不支持租户级密码策略、锁定或 MFA（已决定不实施）；**登录失败不限流**。
- 租户编码 `^[a-z][a-z0-9-]{1,31}$`，不可修改。

*平台管理员与租户保护*
- 启动时若没有平台管理员，按 `CONDUIT_PLATFORM_ADMIN_USERNAME`（默认 `sysadmin`）/ `_PASSWORD` 创建；密码为空只记 WARN。
- 默认租户 `default` 不能停用或删除。删除租户要求先没有产品和设备，用户随之删除。
- 不能删除、禁用自己或修改自己的角色；本租户最后一个启用的管理员受保护（在租户行锁内检查）。
- 停用租户时清该租户设备的身份缓存；之后 MQTT 鉴权拒绝该租户设备，已连接设备的上报进死信 `TENANT_DISABLED`。

**会话与令牌**（V0.2-C + V0.3-B3）

*令牌*
- JWT access 15 分钟；refresh 7 天，放 HttpOnly Cookie。
- 声明：`sub`、`tid`（平台管理员没有）、`role`、`typ`、`sid`、`av`；代入租户时加 `imp`，需改密码时加 `mcp`。

*每次请求的校验*
- 以 PostgreSQL 的 `user_session` 与 `app_user.authz_version` 校验：禁用、改权、登出、撤销会话在下一请求即失效。
- refresh 在同一会话内按哈希比较并轮换；生产环境拒绝旧版无 `sid` 的令牌。

*强制改密码*
- 适用：新用户、首个管理员、被重置密码。
- 此时除 `GET /auth/me`、`PUT /me/password` 与登录、刷新、登出外，一律返回 403 `PASSWORD_CHANGE_REQUIRED`。

*代入租户*
- 平台管理员经 `POST /platform/tenants/{code}/enter` 进入租户（停用租户返回 409），经 `POST /auth/exit-tenant` 退出；两者各记一行 INFO。
- 代入期间每个写请求由 `ImpersonationLogFilter` 记一行 INFO，不记请求体；代入期间不能改密码。
- 续期时若目标租户已停用或删除，退回平台管理员自己的令牌。

**邀请**（V0.3-B3）
- 只存一次性 token 的 SHA-256；48 小时过期，可撤销，只能接受一次。
- 经 `InvitationNotificationChannel` 发送（首个实现为 SMTP）；邮件发送失败则回滚，未配置通道则拒绝创建。
- **只支持租户级管理员 / 只读两种角色，这是最终形态**。需要细粒度范围的成员，接受邀请后由管理员到用户页配置。
- 演示数据：`deploy/demo/identity-demo.py`；详见 `docs/identity-governance.md`。

**组织模型**（V0.3-B1，Flyway V6 / V7）
- `tenant` 是合同、计费、资源与安全边界。
- `customer` 是租户内的业务归类，不是子租户。
- `project` 是主要授权范围，可选关联客户；`site` 必属一个项目；`asset` 必属一个站点，最多 8 层嵌套。
- 产品是租户级共享模板。设备必须属于一个站点，可选关联同站点的资产；组合外键拒绝跨租户、跨站点关联。
- V6 为每个租户建不可删除的默认项目与默认站点；新建租户在同一事务中创建两者。
- V7 只为 4 个演示租户创建 `demo-*` 组织，并把演示设备分到主设备组；不覆盖已有组织或人工归属。
- Redis 键与 InfluxDB 标签不含组织维度。

**项目 / 站点授权**（V0.3-B2，详见 `docs/authorization.md`）
- 权限模型：5 个预置角色、自定义角色、租户 / 项目 / 站点三级绑定。
- 执行链：`PermissionGateFilter → AuthorizationService → 租户 / 范围 / 分页拦截器`。列表、计数、详情与写入都先在 SQL 中限制范围；访问 Redis / InfluxDB 前必须查到已授权设备。
- 业务请求每次回源 PostgreSQL，撤权下一请求即生效。
- 兼容旧账号：未接管的账号沿用旧 role；保存绑定后设 `authz_managed=true`，空绑定不回落到旧角色；最早启用的未接管管理员视为兼容 OWNER。
- 约束：至少保留一名启用的 OWNER（授权替换在租户行锁内执行）；`tenant.manage` 只属于内置 OWNER；自定义角色有绑定时不能删除，停用立即生效。
- 项目管理员只能委派本项目的权限。
- 模式 `CONDUIT_AUTHORIZATION_MODE`：`shadow`（默认，只观察，按旧 `SecurityConfig` 路径矩阵放行）、`enforce`、`read-only`。持久化审计待 B4。

![项目与站点授权链路](docs/images/authorization-flow.svg)

#### 遥测语义与查询

**缺测与零值（全局强制，详见 `docs/telemetry-value-semantics.md`）**
- null 或测点缺省表示该时刻没有上报；0、false、枚举 0 都是有效值。
- 禁止缺测补零：无样本的聚合桶为 null，曲线在缺口处断线，不前值填充、不插值。
- 查询失败另报错误，不返回全零。最新值保留最后一个有效样本的时间戳。

**物模型与解码**
- 属性类型：DOUBLE / INT / BOOL / TEXT / ENUM。逐测点校验，不合格测点剔除并记 `FIELD_REJECTED`。
- 没有物模型的产品：数值一律按浮点处理。
- 物模型缓存 60 秒，编辑后在事务提交后失效。
- 解码器 SPI `PayloadDecoder`（`standard-json`、`flat-json`），按产品选择，名称重复则启动失败。

**时序库**
- InfluxDB 3 Core 3.12.0，自写 HTTP 客户端（`/api/v3/write_lp`、`/api/v3/query_sql`）。
- 所有产品共用表 `property`，tag 为 `tenant_id` / `product_key` / `device_key`，每个测点一列，保留 30 天。
- 同名测点在全表必须同一类型。

**历史查询** `GET /api/v1/devices/{deviceKey}/history`（V0.2-B）

*参数与降采样*
- 单设备 1～8 个测点，跨度 1 分钟～30 天，默认最近 1 小时。
- 每个测点 ≤ 1000 点时返回原始点；否则从固定档位（1s～12h）中选使桶数 ≤ 1000 的最小桶，用 `date_bin`（原点 1970）聚合。数值取 avg / min / max，布尔与枚举取桶内最后值。

*安全与错误码*
- SQL 参数化；列名只用 `InfluxSchema` 确认存在的标识符。
- InfluxDB 不可达或返回 5xx → 503；其他查询错误 → 500，不暴露 SQL。
- 扫描文件数超限（报文同时含 `parquet files` 与 `file limit`）→ 400 + `HISTORY_SCAN_LIMIT`。

*可查询跨度*
- 由 `INFLUXDB3_QUERY_FILE_LIMIT` 决定：Core 默认 432，本项目设 5000。
- 原因：`--gen1-duration` 只接受 1m / 5m / 10m，默认 10m 已是最大，固定每天 144 个文件；Core 不做 compaction，标签过滤也不减少文件数。
- 30 天需要 4320 个文件，故取 5000；30 天为外推，未实测。更长跨度靠分段查询，留到 V0.3-E。
- InfluxDB 容器设 `mem_limit`。

*前端*
- 查询失败时保留旧曲线，并常驻提示旧曲线的真实时间范围，不弹 toast；首次加载失败显示可重试的空状态。
- ECharts 6.1.0 经 `echarts/core` 按需引入；每测点一张联动小图，框选缩放后重新查询。

**遥测重放**（V0.2-R3，平台管理员）
- 接口：`POST` / `GET` / `DELETE` `/api/v1/platform/telemetry/replay`，用于 InfluxDB 丢数后从 Redis 上行流恢复。
- 参数 `{from, to}`：毫秒、左闭右开，跨度不超过 6 小时，否则 400。
- 同时只允许一个任务，运行中再次提交返回 409；任务状态只在内存。
- 执行：按流条目 id 区间 `XRANGE`，每批 500 条，批间检查取消；可恢复错误最多重试 8 次（约 90 秒）。
- **不更新在线状态，不写死信**；写入幂等，可与正常消费并行。
- 条目 id 取 Redis 服务端时钟，运维选窗口时两端各多留约 1 分钟。可回溯范围约为流 `MAXLEN` 100 万条。
- 实现：共用 `TelemetryIngestor`（来源 `LIVE` / `REPLAY`，指标带 `source` 标签）与 `Backoff`；`MessageBus.range` 只读，不进消费组。
- 前端入口：「系统管理 → 数据重放」。

#### 消息链路与可靠性

**消息总线**：经 `MessageBus` 接口，当前实现为 Redis Streams（消费组、`XPENDING` 认领、死信流 `conduit:stream:dead`）。将来换 RabbitMQ / Kafka 时只换实现。

**投递语义：至少一次**

*服务端*
- 下游写入成功后才 ack。
- 可恢复错误（沿异常 cause 链判断）原地退避重试 1s→30s，不消耗投递次数。
- 意外错误：该条进死信 `PROCESSING_ERROR`。超过 5 次投递：进死信 `MAX_DELIVERY_EXCEEDED`。
- 死信一律先写记录再 ack。

*接入侧*
- EMQX 用**内存持久会话**；磁盘持久会话已关闭，积压下会静默楔死。
- transport 写入总线成功后才回 PUBACK。
- transport 离线期间，消息排在会话 mqueue 中，重连后补发。
- 默认 `EMQX_MAX_MQUEUE_LEN` 50000：mqueue 与连接堆上限（256MB，实际按 1.5 倍触发）共享内存，**调大前必须确认 `heap_size` 余量，不得上调堆上限**。2026-11-12/13 实测：过载时 10 万条放不下，连接先被堆超限强杀；5 万条零强杀。全部部署包默认已为 5 万。
- 队列溢出会丢弃并计入 `dropped_msgs`；EMQX 重启会丢未投递的消息。

**端到端可靠上报**（V0.2-R2）

*启用条件*
- 上报顶层带 1～128 字符的字符串 `id` 即启用；此时 `ts` 必填，重发时保持不变。

*回执*
- transport `XADD` 成功后，向 `conduit/{productKey}/{deviceKey}/down/ack` 发 QoS 0 `{"id","ts":receivedAt}`。
- 含义是「平台已持久接收」，不是「已入库」。
- 回执失败不影响 PUBACK；设备收不到回执就原样重发。真实设备必须把待确认队列持久化。

*幂等与旧设备*
- 幂等依靠同设备同 `ts` 覆盖，尚无按 `id` 去重。
- 不带 `id` 的设备只有逐跳 QoS 1 保证，EMQX 重启或队列溢出时会丢。

**transport 单活多备与共享订阅**（V0.2-R；共享订阅 10-29）

*连接与会话*
- 主实例开 `conduit.transport.mqtt.connections` 条连接（默认 4），`cleanStart=false`，会话保留 86400 秒，订阅选项 `retainHandling=DO_NOT_SEND`。
- 连接数大于 1 时订阅共享订阅 `$share/conduit-transport/conduit/+/+/up/#`。
- 原因：EMQX 每条连接是一个单核封顶的 Erlang 进程，单连接时全平台上行挤过同一进程，信箱持续增长直至 `mailbox_overflow` 被杀（recon 实测）。

*clientId*
- 第 0 条为 `conduit-transport`，第 i 条为 `conduit-transport-i`。
- **必须保留 `conduit-` 前缀**，transport 超级用户鉴权依赖它。

*从单连接升级*
- 第 0 条接管旧会话、补发后，**退订旧的非共享过滤器** `conduit/+/+/up/#`。
- EMQX 回 `NO_SUBSCRIPTIONS_EXISTED` 视为成功；其他原因码由协调器重试。

*分发与回退*
- 上下线事件 `$SYS/...` 只由第 0 条订阅。
- 分发策略 `mqtt.shared_subscription_strategy = hash_clientid`，单设备保序。**键在 `mqtt` 下**，写成 `broker` 下会被静默忽略。
- `connections=1` 即回退开关：旧 clientId、普通订阅、不退订。

*成员离线*
- 共享订阅成员离线时，落给它的消息排进其会话、重连后补发，不改派。
- 因此主备切换时新主以同一组 clientId 接管即可。若只有一条长时间断开，约 1/K 流量会滞留。

*选主*
- Redis 键 `conduit:transport:leader`，由 Lua 抢锁：键不存在则写入；键已是本实例 id 则续期，视为抢到。续期与释放同样比对 id。
- ttl 15s，续期间隔 5s，抢锁间隔 2s；只有主实例连接 EMQX。
- 续期失败立即断开并转为备用；正常停止时释放锁（`stop_grace_period: 20s`）。
- 不用 HiveMQ 自动重连：由协调器先确认仍持锁再重连；SUBACK 含错误码视为失败。
- Redis 命令超时 2s。

*日志与健康检查*
- 日志：首次失败 WARN，后续重试 DEBUG，恢复与角色变化 INFO。
- 健康明细 `components.leader.details`：`role` / `instanceId` / `connected`；`connected` 只在已连接且订阅获批后为 true。

*已知限制*
- Broker 自身崩溃仍可能丢失已确认消息，见 `docs/durability-validation.md`。

**transport 微批与会话监控**（10-28）

*参数规则*
- 约束：**`batch-size ≤ max_inflight ≪ max_mailbox_size`**。
  - 窗口应为批的若干倍：两者相等就成了停等协议；批大于窗口则永远攒不满，每批白等满 `batch-delay` 5ms。
  - 在途消息堆在连接进程的信箱里，越过 `max_mailbox_size` 即被强杀。
- 默认值：`max_inflight` 512、`batch-size` 64，`max_mailbox_size` 10000（2026-11-13 起由 dev 文件统一定义；EMQX 自身默认 1000 在独立部署每秒 8,000 条即强杀）。
- 会话上限全部署包统一：队列 5 万、信箱 10000、开启强杀、堆 256MB。分布式部署（V0.8）沿用。
- 拆分部署的值：EMQX 主机 `max_inflight` 1024，会话上限同上（2026-11-12 由最大值改回有上限，见第 4 节）；应用主机 `batch-size` 512。

*会话监控*
- 主实例经 EMQX REST API 按连接轮询会话指标。告警七项：丢弃、队列水位、积压绝对值（默认 ≥ 5 万条，队列不限时仍有效）、会话内存、投递停滞、持久会话被意外启用、批大于窗口。
- 另轮询 `/api/v5/nodes`，EMQX 主机内存占比 ≥ 80%（默认）时告警，仪表 `conduit.transport.emqx.memory.*`，明细在健康检查的 `emqxNode` 下。
- **只有「投递停滞」把健康检查置为 DOWN**，任一连接停滞即判 DOWN。
- 仪表带 `client` 标签。另暴露 `mailbox.len` 与 `reductions`，只暴露、不参与健康判定。

**e2e**（`ReliabilityE2EIT`）

*环境*
- Testcontainers 临时环境，EMQX 使用内存持久会话，宿主机端口取 20000～29999 间的随机固定值，容器重启后端口不变。
- 本地以 jar 启动 1 个 server 和 2 个 transport；口令每次随机生成。

*场景*
- 杀 server、杀主 transport、杀全部 transport、重启 EMQX、可靠上报途中强杀 EMQX，各发 500 条。
- 断言：计数恰好、无死信、无待确认。重启 EMQX 的两个场景使用带 `id` 的设备。

*运行*
- 需设置 `E2E_DEV_HOST`。先执行 `mvn -B -DskipTests install`，再执行 `mvn -B verify -Pe2e -pl conduit-e2e`。

**服务端流水线吞吐**

*消费者*
- `PipelineWorker` 启动 `conduit.pipeline.workers` 条（默认 4）虚拟线程，每条是消费组中的一个消费者（`consumer-序号`）。
- 下游都不依赖顺序：最新值按测点比较 ts，InfluxDB 按（序列, ts）幂等，在线状态有 `since` 守护。

*批与 WAL*
- `conduit.pipeline.batch-size` 默认 20000；`-1` 表示不限，但会无界占内存。
- 每批只写一次 InfluxDB，写入要等 WAL 刷盘（默认 1s）。**不要缩短刷盘间隔**：实测 100ms 使批变小、吞吐反降 36%。

*写入切块与错误分类*
- 按 `conduit.influx.max-request-bytes`（默认 8 MiB，须小于 InfluxDB 的 10 MiB 上限）切块发送。
- **413 与 400 不可恢复**，不得原地重试；否则会无限重发同一载荷而永久停摆。

*Redis 批量化*
- 身份用一次 `MGET` 预取；最新值一次 pipeline 提交，`latest-update.lua` 原样复用。
- `LatestCoalescer` 剔除同批内被后续消息完全覆盖的点。
- 批量遇不可恢复错误时退回逐条处理，只让出错的那条进死信。

*指标*
- `conduit.pipeline.stage{stage=total|identity|decode|influx|latest|ack}`、`conduit.pipeline.batch.size`。

**容量结论**（守恒判据，见 `docs/script/loadtest/README.md`）

*单机部署压测 VM（8 vCPU，600 连接）*
- **可靠无损 11,500 条/秒**；13,000 为边缘；16,000 撞到硬件墙。

*拆分部署（本地两台 8 vCPU VM 与发压机同在一台物理机）*
- 每台每 10 秒 1 条：2.5 万台无损。
- 每台每秒 1 条：5,000 台通过，6,000 台起掉线。瓶颈是 EMQX 主机 VM 的内核收包 softirq，EMQX 自身约 1 核。

*真实设备画像（拆分部署，对外容量口径）*
- 画像：每条 30 个测点；间隔 70% 每 60 秒、25% 每 15 秒、5% 每秒；一半设备带 `id` 可靠上报。
- 2.5 万台 30 分钟、1 万台 2 小时浸泡均无损，内存与积压不随时间增长；两台 VM 的 CPU 不到 30%，上限未测到。
- 本机发压可信上限约 2.5 万连接（5 万连接需发压端约 13 核）；更大规模需分机发压。
- 连接本身有开销：同为 5,000 条/秒，5 万连接比 1,000 连接的 EMQX CPU 高约 1.7 倍，内存每连接约 70 KB。

*口径限制*
- 本地数字反映虚拟化网络的上限，**不得外推到物理机或云主机**。
- 吞吐不能直接换算为设备数，因为连接数本身有开销。

**MQTT 鉴权**
- EMQX 用 HTTP 回调鉴权；按协议，拒绝也回 200 + `result: deny`。
- 鉴权结论计入 `conduit.mqtt.auth{result, reason}`，启动时就注册全部 9 个序列。
- EMQX 端：`request_timeout` 10 秒，8 条长连接。
- server 端：`server.tomcat.keep-alive-timeout: 30m`、`max-keep-alive-requests: -1`。否则 Tomcat 关闭空闲连接的瞬间，EMQX 复用该连接会失败，并直接判为未授权。

**在线状态**

*存储*
- Redis hash `conduit:presence:{deviceKey}`（online / since / clientIp / source / lastSeen），由 `presence-apply.lua` 原子写入。

*两个来源*
- EMQX 上下线事件是权威来源：使用 EMQX 时钟，只接受 `eventTime >= since` 的事件。
- 流水线在数据落库并 ack 后，按设备每批写一次（尽力而为）。

*数据来源的规则*
- 数据永远不推进 `since`：无记录 → 置在线；已在线 → 只刷新 lastSeen；已离线 → 仅当 `ts > since + 10 秒` 才复活。
- `source=data` 产生的记录，其 `since` 不可信，EMQX 事件总是生效。
- 上线事件不带 IP 时保留原 IP。

**演示数据**
- Flyway V4：4 租户 / 8 产品 / 174 设备 / 36 属性。按 code 与名称复用，升级不重复。种子设备的密钥是不可用的占位哈希，由模拟器重置。
- 动态数据（在线状态、最新值、曲线）由模拟器生成。

**模拟器**

*设备发现*
- `SIM_PLATFORM_PASSWORD` 非空时，用平台管理员发现 `SIM_TENANTS` 中启用租户的物模型产品，每个产品最多 `SIM_MAX_DEVICES_PER_PRODUCT` 台设备，并重置其密钥。
- `SIM_EXCLUDE_PRODUCTS`：默认 `压测产品`，在读物模型、重置密钥之前跳过，避免与压测脚本互相作废密钥；配成空即不排除。
- 失败处理：单个租户失败只记 WARN；单台设备失败只跳过该设备。

*数据生成*
- 按物模型规则生成，满足上下限与小数位；日曲线按系统时区。
- 光伏模型（含 eday / etotal / pv / solar / 辐照度等测点）：功率按日照曲线，夜间为 0，并累加成日发电量。
- 有多个功率测点时取最大的一个，不求和。

#### 界面与总览

**首页总览** `GET /api/v1/overview`
- 返回：产品数、属性数、设备数（启用 / 禁用 / 在线）、最近上报的 5 台设备、各产品设备数，以及最近 1 小时按 `reason` 分组的死信（最多 1 万条）。
- 计数由 `DeviceStats` 统一计算。Redis 不可用时相关字段为 null，前端显示「—」。

**平台吞吐**：`/api/v1/platform/overview` 的 `throughput` 字段。
- `ThroughputSampler` 自带守护线程，每 5 秒采样一次，算 60 秒窗口速率；不启用全局 `@EnableScheduling`。
- 口径：server 本进程，重启归零。
- 采样不足两次时为 `null`（显示「采样中」）；真实零流量为 `0`。

**设备列表筛选** `GET /api/v1/devices`
- 参数：productKey、keyword、`online`、`status`、`reported`（RECENT / STALE / NEVER）、`createdFrom` / `createdTo`（毫秒，左闭右开）。非法值返回 400。
- `online` 与 `reported` 依赖 Redis，走内存过滤与分页：开销 O(设备数)，Redis 不可用时返回 503。
- 前端把已应用的筛选经 `router.replace` 同步到 query。

**前端框架**

*侧栏*
- 分组侧栏按身份给出菜单；可折叠，宽度 < 820px 自动折叠；有浅色、深色两种风格。

*顶栏*
- 包含面包屑、外观设置、租户名、用户菜单。
- 代入租户时固定显示提示条；需改密码时弹出不可关闭的对话框。

*路由*
- `meta.scope` / `meta.requires` 由守卫统一处理。
- 浏览器标题为「<页面或对象名> · Conduit」。`/` 重定向到 `/home`，未知地址显示 404。

*主题与偏好*
- 主题：浅色 / 深色 / 跟随系统，深色基于 Element Plus 暗色变量。
- 偏好存 `localStorage` 的 `conduit.ui`，读写失败时用默认值。ECharts 随主题重建。

*设备详情*
- 信息卡 + 指标卡片：前 8 个数值测点画 1 小时迷你趋势线。
- 点击卡片进入 `?tab=history&props=<标识>`。

*外壳与品牌*
- 应用外壳恰好一屏高，只有主区滚动；表格行高约 44px，1080p 屏下 10 行列表无需滚动。
- 品牌标志为 `ConduitLogo`（字母 C 信号），favicon 为 `public/favicon.svg`。

#### 日志与部署

**日志**
- Java 服务写文件日志，目录为 `CONDUIT_LOG_DIR`（默认 `logs`），每个服务一个 `<服务名>.log`。
- 按天与大小滚动：单文件 50MB、保留 14 天、总量 1GB。
- simulator 使用 logback-classic。测试写 `target/test-logs`；e2e 只输出到控制台。
- Docker 中共用卷 `conduit-logs`，第二个 transport 写入其 `transport-2` 子目录。

**部署包**

*通用*
- 项目名均为 `conduit`（split 的 EMQX 主机为 `conduit-emqx`），数据卷同名，同一宿主机上各包二选一。
- `deploy/dev`：PostgreSQL、Redis（AOF `appendfsync always`）、InfluxDB、InfluxDB Explorer、EMQX 5.10.5。
  - EMQX 配置模板只读挂载，以 `sed` 渲染。
  - `CONDUIT_AUTH_URL` 必填。
  - `CONDUIT_EMQX_API_KEY` / `_SECRET` 必填：EMQX REST API 只认 API key，不认控制台账号。

*`deploy/standalone`*
- 以 `extends` 引用 dev 的服务；其 `.env` 含 dev 的全部必填变量，`CONDUIT_AUTH_URL=http://conduit-server:8080`。
- 应用服务：conduit-server、两个 transport（服务名 `conduit-transport` / `conduit-transport-2`，YAML 锚点复用）、conduit-web、模拟器、Mailpit。
- Java 服务设容器内存上限：server 1500m、transport 512m、simulator 384m；InfluxDB 2g。

*镜像*
- 构建上下文为仓库根目录：`java.Dockerfile`（`eclipse-temurin:21.0.6_7-jre`）与 `web.Dockerfile`（`nginx:1.27.4-alpine`）。
- `BUILD_FROM=prebuilt|source` 选择预编译产物或源码构建。
- Docker 宿主机无外网时：开发机执行 `deploy/package.sh`，从干净检出编译，打包为 `target/conduit-standalone-<短提交>.tar.gz`。

*`deploy/split`*
- EMQX 主机：`split/emqx`，extends dev 的 emqx。连接队列与报文上限取最大值；会话三项有上限（窗口 1024、队列 5 万、开启强杀）。不得改回「队列不限 + 关强杀」：过载时积压会耗尽整机内存，EMQX 被系统杀掉、积压全丢（2026-11-12 实测）。
- 应用主机：`split/app`，逐个 extends standalone 的服务；`EMQX_REMOTE_HOST` 指向 EMQX 主机，`depends_on: !override`（需 Compose ≥ 2.24.4）。
- 两边的 `deploy/` 须在同一绝对路径。
- 鉴权回调是 EMQX → 应用主机的同步调用。EMQX 开源版没有 vhost，一台 EMQX 只对应一套环境。

*容器名（`container_name`）*
- 单实例服务不带 `-1` 后缀：`conduit-postgres`、`conduit-redis`、`conduit-influxdb`、`conduit-emqx`、`conduit-server`、`conduit-web`、`conduit-simulator`、`conduit-mailpit`、`conduit-prometheus` 等。
- transport 为 `conduit-transport-1` / `-2`。注意这与 MQTT clientId 是两套命名。

*EMQX 运行参数*
- `ulimits.nofile` 1048576：Docker 默认 1024 时，约 1,000 台设备即 `emfile` 崩溃。
- 关闭磁盘持久会话；`singleton` 发现策略，节点名固定为 `emqx@127.0.0.1`，因此控制台改过的密码会恢复为 `.env` 中的值。

*观测栈*（profile `metrics`）
- 组件：Prometheus v3.5.0、Grafana 11.6.1、node-exporter、cadvisor、redis-exporter。各设 `mem_limit`，合计约 1GB；Prometheus 保留 7 天，上限 2GB。
- 抓取方式：
  - server 指标走 `/internal/metrics`，校验 `X-Conduit-Internal-Token`，与鉴权回调同一令牌。
  - transport 直接抓 `/actuator/prometheus`；`/actuator/**` 除 health 外仅平台管理员可访问。
  - InfluxDB 以 `--disable-authz health,ping,metrics` 免鉴权暴露 `/metrics`。
- 看板 `conduit-bottleneck.json` 由脚本生成，数据源 uid 读自预置文件。

### 1.3 版本路线

当前路线见 `docs/roadmap-saas.md`：V0.3 SaaS 基座与安全边界 → V0.4 设备生命周期与控制 → V0.5 规则引擎与告警 → V0.6 资产与仪表盘 → V0.7 开放平台 → V0.8 云原生与高可用 → V0.9 商业化 → V1.0 SaaS GA。

V0.3 分为 6 个子项目：A 租户隔离、B 组织与 RBAC、C 租户生命周期、D 计量配额、E 资源治理，F 工程与运维可与其他子项目并行。每个子项目都要有 spec 与 plan；延后事项记在 `docs/backlog-v0.2.md`。

### 1.4 SaaS 升级约束（规划）

- **V0.3**：访问隔离与资源隔离同时建设，包括租户状态机、客户 / 项目边界、资源路由、配额、公平调度、查询预算、用量事件；CI/CD、基础指标、备份恢复验证同步交付。
- **V0.4**：RPC、配置、OTA、凭据轮换复用统一批量任务中心；指令须定义幂等、有效期与执行状态。超时或取消不等于设备未执行。
- **V0.5**：明确物模型版本、单位精度、数据质量、时间语义、去重与乱序；重放默认不触发外部副作用。
- **V0.6**：保留期、降采样、归档与跨存储删除。
- **V0.8**：集群 HA 与租户迁移。
- **V0.9**：在租户控制面与计量口径之上完成商业闭环。
- **容量与 99.9% 可用性**：均为待验证目标，验收须固定负载、硬件、SLI 与统计口径。
- **多实例前提**（方向性约束）：进程内状态只能是可重建的缓存，跨实例协调走 Redis 锁、消费组或数据库，写操作幂等。已知不满足的一项：遥测重放任务状态只在单进程内存中。

## 2. 编码约定

### 2.1 注释（必须）

- 每个类都要有中文注释：Java 的类、接口、record、枚举写 Javadoc；TS 模块写文件头注释；Vue 组件在 `<script setup>` 顶部写注释。
- 重点方法要写注释：public 方法、service / controller 方法、纯函数、带分支逻辑的方法。说明用途、重要参数与返回值、异常与边界、副作用。简单 getter 可以省略。
- 注释写「做什么、为什么」，不复述代码。改代码时同步更新注释。

### 2.2 其他约定

- **技术栈**：Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway。包名 `dev.conduit.<模块>`，按业务分包。
- **测试**：先写失败测试（TDD）。集成测试用 Testcontainers；前端用 Vitest + `@vue/test-utils`。
- **测试报告**：每次实际执行测试后，在 `docs/test-reports/YYYY-MM-DD-<主题>.md` 新建或更新报告。
  - 必须写明：被测范围与代码版本、环境、实际命令、结果与耗时、跳过与阻塞项、警告及日志位置；失败和无效尝试也要如实记录。
  - 没执行的测试不得写成「通过」。交付时给出报告链接。
- **压测报告**：
  - 必须写明测试类型：容量测试、压力测试（必须核对失效日志）或回归对比。
  - 同时记录接入 QPS（EMQX 确认的条数）与入库 TPS（InfluxDB 实际行数）。**对外承诺只用入库 TPS**，因为过载时 EMQX 会先 PUBACK 再丢弃。
  - **验收用计数守恒**：客户端 PUBACK 数 = InfluxDB 行数，且丢弃、死信、失效日志均为 0。
  - **压测与排障前必须回读运行中的实际配置**，不以仓库内容为准。
  - 本地 VM 压测前，确认宿主机不紧张，并核对网卡的 `pkts rx OOB` 增量。
- **界面**：
  - 文案用中文。分页表格默认每页 10 条（可选 10 / 20 / 50 / 100），统一用 `useTable`。
  - 组件：`PageHeader`、`FilterBar`、`OnlineDot`、`el-tag`、`RelativeTime`；空状态用 `el-empty` + 引导按钮，首次加载显示骨架屏。
  - 入口要显眼：列表有操作列、全局面包屑、产品与设备可互相跳转。详情页的标签页与 query `tab` 同步。
  - 样式只用 CSS 变量（`--el-*` / `--cd-*`），深色主题下必须可读。
  - 新页面在 `router/meta.ts` 的 `ROUTE_META` 中登记名称、面包屑、菜单、`scope`、`requires`。
  - 写操作按钮按 `usePermission().can(...)` 与行级 permissions 显示；只读身份隐藏而不是禁用。
- **演示与测试数据**：用有意义的中文名称，测试结束清理自己创建的数据。
- **日志**：
  - SLF4J 参数化。消息链路上 DEBUG 每条消息或每批一行，逐字段细节放 TRACE；构造字符串的日志要加级别判断。
  - 绝不记录设备密钥、口令、JWT、内部 token、Authorization。payload 只在 TRACE 输出，并截断到 256 字符。
  - 管理操作各记一行 INFO，不记请求体与口令。
- **提交**：Conventional Commits，只署名不带邮箱，不加任何 AI 署名行。
- **仓库公开**：不提交 IP、本机路径、用户名、口令、token。本地配置与部署产物已在 `.gitignore`。

## 3. 本地开发

见 README「开发」。

**基础服务与应用**
- Docker 宿主机只跑 `deploy/dev`（`docker compose up -d`）。
- server、transport、simulator、UI 在 IDEA 中运行，运行配置为 `Conduit (local all)`。
- `deploy/dev/.env` 的 `CONDUIT_AUTH_URL` 指向开发机。

**`application-local.yml` 必须配置**
- `conduit.transport.monitor.enabled: false`，否则缺少 EMQX API key 时 transport 启动失败。
- `conduit.bootstrap.platform-admin-password`，才会创建 `sysadmin`。
- 不要覆盖 `client-id` 或 `session-expiry-seconds`。

**排查与验收**
- 本地日志在仓库根目录 `logs/`，排查问题先看这里。
- 验收用测试和对本地整套服务做 API 检查。只有所有者要求时才部署独立部署包。

## 4. 决定记录

每条一行：日期、决定、出处。被推翻的决定只保留最终结论，并注明推翻的原因。

**架构与可靠性**
- 2026-10-14 V0.1：Spring Boot 3 + EMQX 5 + Redis Streams 总线 + PostgreSQL 16 + Redis 7；Vue 3 前端；transport / server 两进程；HTTP 鉴权回调；从 V0.1 起带 `tenant_id`（`2026-10-14-conduit-v0.1-design.md`）。
- 2026-10-15 V0.2-A：时序库为 InfluxDB 3 Core；物模型只做属性；解码器 SPI。可恢复错误沿 cause 链判断；`conduit-connector` 改名为 `conduit-transport`。
- 2026-10-16 V0.2-R：transport 单活多备（Redis 锁）；e2e 改为 Testcontainers + 本地 jar；模拟器支持多租户与多产品（`2026-10-16-conduit-v0.2r-reliability-design.md`）。同期选用的磁盘持久会话已于 10-28 关闭，「放弃共享订阅」已于 10-29 推翻。
- 2026-10-19：统一为 EMQX 5.10.5，旧版交接补发错位未再复现；Redis AOF 改为 `always`。
- 2026-10-20 V0.2-R2：带 `id` 的端到端可靠上报 + QoS 0 业务回执；e2e 增加强杀 EMQX 场景（`2026-10-20-conduit-reliable-uplink-design.md`）。
- 2026-10-20 V0.2-R3：遥测重放（`2026-10-20-conduit-telemetry-replay-design.md`），10-21 验收通过。
- 2026-10-27：流水线吞吐 = 批大小 ÷ WAL 1 秒，修复为大批量 + 按字节切块，413 / 400 改判为不可恢复（`076ec17`）。
- 2026-10-28：**关闭 EMQX 磁盘持久会话**，因积压下会静默楔死，且重连重放超堆形成死循环；改为内存持久会话。`mqtt.retry_interval` 对持久会话无效，勿重走此路（`2026-10-28-conduit-emqx-session-wedge-fix-design.md`）。
- 2026-10-28：transport 改为微批写总线。批与窗口的规则几经实测修正，最终定为 `batch-size ≤ max_inflight ≪ max_mailbox_size`，默认 512 / 64；会话监控六项告警。
- 2026-10-28：流水线 Redis 往返批量化（MGET + pipeline），确立「批量失败退回逐条」的模式；Java 容器设内存上限；Redis 保持 `appendfsync always`。
- 2026-10-29：transport 共享订阅（默认 4 条连接，`hash_clientid`），依据是 recon 测得单连接进程单核封顶；负载下强杀主 transport 零丢失（`65ad9a2`，`2026-10-29-conduit-transport-shared-subscription-design.md`）。同日早先「8,000 条/秒天花板、暂不实施」的结论源于发压器测量假象，已作废。
- 2026-10-29：`LatestCoalescer` 同批合并最新值，`latest` 阶段 1,856ms → 98ms（`141bdd6`）。
- 2026-10-29：transport 会话监控加信箱长度与 reductions，只暴露，不参与健康判定。
- 2026-10-30：MQTT 鉴权按原因计数；模拟器排除压测产品；EMQX `nofile` 1048576；Tomcat keep-alive 修复鉴权回调偶发失败（`69ac212`）；鉴权超时由 5 秒放宽到 10 秒（`de5a237`）。
- 2026-11-12：拆分部署 EMQX 会话三项由最大值（窗口 65535、队列不限、关强杀）改回有上限（1024 / 5 万 / 开启强杀），因压测到崩溃时积压耗尽整机内存、EMQX 被 OOM 杀两次、约 932 万条已确认消息丢失；队列先试 10 万，连接被堆超限反复强杀，15,000 条/秒起即丢，改 5 万后零强杀，15,000 条/秒以内无损，超出部分按满丢弃并可计数；transport 增加积压绝对值与 EMQX 主机内存告警（`2026-11-12-split-emqx-capped-session-design.md`、`2026-11-12-stress-to-failure.md`）。
- 2026-11-13：会话上限推广到全部部署包（队列 5 万、信箱 10000、开启强杀、堆 256MB），信箱改由 dev 文件统一定义；依据是独立部署按旧默认（信箱 1000）每秒 8,000 条即被强杀，改后零强杀（`2026-11-13-standalone-session-limits.md`）。分布式部署放到 V0.8 单独压测；断电后 InfluxDB 循环重启、总线截断丢数据、积压计数虚高记入 backlog。

**租户、组织与身份**
- 2026-10-16 V0.2-C：三种角色、JWT 声明、`SecurityConfig` 路径矩阵、强制改密码、平台接口与代入、停用租户（`2026-10-16-conduit-v0.2c-tenants-users-design.md`）。
- 2026-10-22：租户隔离用拦截器 + 组合外键 + 受控系统通道（`2026-10-22-conduit-tenant-isolation-design.md`）。10-27 复核：**不采用 RLS，不再定期重提**；唯一触发条件是出现真实的跨租户泄露。
- 2026-10-26 V0.3-B1 / B2：组织骨架（V6 / V7）与项目 / 站点授权（`2026-10-26-conduit-v0.3b-organization-rbac-design.md`、`docs/authorization.md`）。
- 2026-10-27 V0.3-B3：服务端会话、授权版本、一次性邀请。租户密码策略、锁定、MFA **不实施**，也不预留字段。邀请携带范围角色**不实施**。B 阶段只剩 B4 持久化审计。

**遥测与界面**
- 2026-10-15：界面刷新（统一框架、暗色模式、列表套件、首页总览）、设备列表筛选、数据驱动在线状态、日志落盘、InfluxDB Explorer；ECharts 由 5.6.0 升至 6.1.0（XSS 公告 GHSA-fgmj-fm8m-jvvx）。
- 2026-10-26：全局遥测语义「null = 未上报，0 = 有效值」；聚合返回完整时间桶，空桶为 null，前端断线。
- 2026-10-27：历史扫描上限的根因是两个 InfluxDB 默认值相乘；`INFLUXDB3_QUERY_FILE_LIMIT=5000`，错误码 `HISTORY_SCAN_LIMIT`，分段查询留到 V0.3-E。
- 2026-10-29：平台首页「实时吞吐」，口径为本进程、重启归零。

**部署、观测与流程**
- 2026-10-16：部署包定为 `deploy/dev` 与 `deploy/standalone`，`package.sh` 打包；旧的 `build-local.sh`、`sync-to-host.sh`、`infra/`、`full/` 已删除（`2026-10-16-conduit-deploy-packages-design.md`）。
- 2026-10-20：产品名 Conduit IoT，Maven 根工程 `dev.conduit:conduit-iot`；SaaS 路线以 `docs/roadmap-saas.md` 为准，演示数据须从正式生产基线中剥离（`2026-10-20-conduit-demo-seed-design.md`）。
- 2026-10-20：架构图统一为单一 SVG 源文件，并确立架构同步规则；每次实际测试都要留报告。
- 2026-10-21：V0.3 拆为 A～F 六个子项目；CI 暂不建设，理由见 roadmap。
- 2026-10-26：新功能必须带可复现的演示数据。
- 2026-10-28：性能观测栈（profile `metrics`）；server 指标走 `/internal/metrics`。
- 2026-10-29：看板按数据流重排，增加守恒差面板。
- 2026-10-29：压测改用计数守恒判据；压测前必须回读运行配置（部署漂移曾导致决定写错）。
- 2026-10-29：所有者确定目标为「当前压测 VM 上不崩溃的最大吞吐」，信箱上限可按实测调大，堆上限不得上调。
- 2026-10-30：拆分部署包 `deploy/split`；容器名全部固定，EMQX 统一为 `conduit-emqx`（`2026-10-30-split-deployment.md`）。
- 2026-11-02：交接文件只放 `docs/handoffs/`，全局 `~/.codex/AGENTS.md` 已同步。

**方法教训**
- 2026-10-28：Redis `commandstats` 会把 Lua 内部调用一并计数；模型与实测不符时，先怀疑模型。
- 2026-10-28：容量参数必须同时核对吞吐与失效日志。只看吞吐时漏掉了 inflight 1024 超过信箱上限 1000 导致的强杀。
- 2026-10-29：发压器测量假象。模拟器重置了压测设备的密钥，而 paho 连接时被拒绝鉴权也不报错。识别特征：接入量恰好是名义目标的固定比例。
- 2026-10-29：观测改动必须走通展示端到数据源的完整链路；预警阈值须用过载与健康两组历史数据验证后才能标红。
- 2026-10-29：「命令成功但未生效」一律以回读结果为准，例如配置键写错层级会被静默忽略。

**压测结论**（详见各报告）
- 2026-10-29 单机部署压测 VM：可靠无损 11,500 条/秒，16,000 为硬件墙（`2026-10-29-vm-max-throughput.md`）。
- 2026-10-30 拆分部署：
  - 每 10 秒 1 条：2.5 万台无损（`2026-10-30-device-ladder-split.md`、`2026-10-30-ring-timeout-retest.md`）。
  - 早先的掉线源于宿主机 CPU 紧张导致虚拟网卡丢包，而非连接半开（`2026-10-30-auth-timeout-investigation.md`）。
  - 压测 VM 一律用 vmxnet3 网卡，接收环 4096（已持久化）。
- 2026-11-12 拆分部署 1,000 台设备逐档提速：可靠无损入库 5,000 条/秒；8,000 条/秒共 3 次、2 过 1 不过（不过的一次仅因约 13 秒的 `runq_overload` 告警），均守恒、零掉线，属边缘，p99 在 37～766 毫秒间波动。四种压测方法（固定连接提速、固定频率加设备、同速率换连接形态、真实画像验收）见 `2026-11-12-loadtest-methods-plan.md`，对外容量以真实画像验收为准。另查明：旧报告的「发压机 CPU」实为宿主机整机 CPU，发压进程在 8,000 条/秒下只用约 2.2 个核；`ladder.py` 改为分别记录发压进程、整机与 VMware 的 CPU，可信度判据拆为「最忙发压进程 ≤ 单核 85%」与「整机平均 ≤ 85%」（`2026-11-12-qps-tps-1k-devices.md`）。
- 2026-11-12 压力测试（拆分部署，1,000 台逐档提速至崩溃）：队列不限时 25,000 条/秒起 EMQX 被 OOM 杀；改为队列 5 万后至 30,000 条/秒 EMQX 与连接均不被杀，无损上限 15,000 条/秒，拐点为 EMQX 主机 CPU（`2026-11-12-stress-to-failure.md`）。对外仍按 5,000 条/秒可靠。
- 2026-11-13 独立部署（1 台 8 vCPU，all in one）压力测试：信箱 10000、队列 5 万时零强杀，无损上限 15,000 条/秒，瓶颈为 EMQX 与后端共用的 CPU（`2026-11-13-standalone-session-limits.md`）。
- 2026-11-13 测法三、四（拆分部署）：同为 5,000 条/秒时连接数越多 EMQX CPU 与内存越高；真实画像（30 测点、混合间隔、半数可靠上报）2.5 万台 30 分钟与 1 万台 2 小时浸泡均无损，压测产品扩到 30 个测点（`2026-11-13-loadtest-methods-3-4.md`）。
- 2026-11-01：每秒 1 条的设备数上限为 5,000～6,000 台，瓶颈是 EMQX 主机 VM 的内核收包 softirq，属本地虚拟化网络的上限。后续方向待所有者决定，见 `docs/handoffs/2026-11-02-split-loadtest-ceiling.md`。
