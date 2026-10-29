# V0.2 待办（V0.1 评审遗留的次要事项）

2026-10-20 持久性验证更新：Redis 使用 `appendfsync always`；EMQX 5.10.5 强杀仍会丢已 PUBACK 的尾部消息，强制重启还观察到新建会话未恢复。V0.2-R2 已用设备侧可靠上报覆盖带 `id` 的设备，并增加强杀 broker 的 e2e；旧设备仍不具备该保证。另发现 InfluxDB 零字节 catalog 导致启动失败。详见 [实验与后续工作](durability-validation.md)。

## 后端：模拟器

- 模拟器重启后 EPimp 重置；provisioning 会重置其他运行中模拟器的密钥
- `sim-%03d` 设备名可能冲突
- `loginWithRetry` 对 401 也重试
- `ConduitApiClient.resolve()` 丢失 context path；响应缺字段时 NPE
- 配置未校验；无 shutdown hook
- slf4j-simple 为 compile scope（e2e 需排除，避免与 logback 冲突）
- `DemoProvisionerTest` 忽略 query string
- 四参构造的 10 分钟 completionTimeout 对长时间有限运行偏短；连接失败的线程断言形同虚设
- 退出处理里 catch Throwable 过宽

## 后端：部署与镜像

- 独立部署包 Java 镜像以 root 运行；`source` 阶段无 Maven 依赖缓存层（每次构建全量下载依赖）
- `BUILD_FROM=source` 需要宿主机联网（Maven 中央仓库、npm 源）；无外网时只能用 `prebuilt`
- 预编译前端的 MQTT 端口（`VITE_MQTT_PORT`）在打包时固定，宿主机改 `MQTT_PORT` 后「连接信息」显示的端口不变，需重新打包
- 开发环境包的 EMQX 配置为只读挂载，宿主机上的 `emqx/` 文件需对 uid 1000 可读；权限不对时 EMQX 启动失败
- entrypoint 中 sed 注入内部 token、HOCON 引号处理较脆弱
- EMQX 控制台密码仅首次启动生效（卷非空时忽略）
- 重启后的启动顺序
- API 端口同时暴露 `/internal/*` 与 actuator
- EMQX 健康检查较浅

## 后端：接入与处理（P1/P2）

- `TopicParser` 对 `$SYS` 节点为 null/空的处理
- `RawMessage` 的 `byte[]` 未做拷贝
- `claimStale` 时序测试余量偏小
- MQTT 适配器重新订阅路径缺少测试
- Spring 装配缺少冒烟测试
- 引导（bootstrap）并发启动存在竞态
- 身份缓存对字面量 `null` 的处理
- LIKE 通配符未转义
- 单批写入超过 InfluxDB 请求体上限（约 10 MiB）时返回 413，当前被当作"暂时不可用"原地无限重试，会卡住整条流水线（逐点回退的 O(N) 问题已随 V0.2-A 的部分写入解决）
- 多实例下的 claim 限制
- 禁用设备不会被 EMQX 踢下线
- 缺少 MAX-delivery 边界测试
- 每次重启会泄漏 consumer

## 后端：认证与安全

- 登录存在时序差异，可枚举用户名
- access token 在 15 分钟内不会被重新校验
- 无暴力破解防护
- refresh token 吊销（V1.0）
- Cookie 的 Secure 默认值（V1.0，随 TLS 一起）

## 前端

- client、router、store 之间存在循环 import（目前为懒加载，安全）
- `/auth/` 前缀判断脆弱
- 路由守卫的 `restore()` 绕过 single-flight
- Element Plus 全量引入，主 chunk 约 987kB
- 缺少 store / 路由守卫 / 组件测试
- `safeRedirect` 未拒绝控制字符与编码斜杠
- `load()` 无请求序号，存在竞态；翻页可能重复加载
- `formatDateTime` 对数字字符串的处理
- 对话框关闭时应清除密钥（`@closed` + `destroy-on-close`）
- `DeviceDetailView` 在 `deviceKey` 参数变化时应重新加载
- 轮询遇到 404 时静默
- 创建对话框在创建中可被 Esc / 关闭按钮打断
- `usePolling` 重叠测试偏弱
- 忙碌中的按钮无 loading 状态

## 测试

- Influx 同 ts 覆盖，重复写入无法检出

## V0.2-A 遗留（评审后延后）

- 无物模型时，设备上报名为 `time` / `tenant_id` / `product_key` / `device_key` 的测点会以误导性的 `FIELD_TYPE_CONFLICT` 进死信（应在解码时直接拒绝该名称）
- 测点名含制表符时整行被 InfluxDB 拒绝，死信原因不够明确
- 解码错误信息会带出报文中的个别 token / 测点名（仅 debug 日志）；可改为固定文案加行列号
- `DecoderRegistry` 遇到空名称时抛 NPE，启动失败信息不清晰
- INT 范围校验经 double 比较，超过 2^53 时有精度损失；TEXT 长度按 UTF-16 单元计算
- 并发删除属性时修改同一属性返回 500 而非 404
- `decimals` 传 `1.5` 被 Jackson 截断为 1 而非报错
- 导入 `mode` 非法时先于租户校验返回 400
- 物模型拒绝后又被 InfluxDB 拒绝（`FIELD_TYPE_CONFLICT`）时，死信不带 `rejected` 明细
- `MessageBus.deadLetter` 已不再被流水线使用，注释需更新
- 演示物模型导入失败会让模拟器退出（依赖 compose 重启）
- 产品详情：增删改属性后会重新加载产品，覆盖「基本信息」页未保存的编辑；`productKey` 变化时不重新加载；导出下载同步 `revokeObjectURL`
- 测试缺口：200 属性上限的并发测试、枚举标签的负例、null 值、缓存容量上限、模型标识与模拟器测点的一致性

## V0.2-B 遗留（评审后延后）

- 历史原始模式的查询不过滤"所选测点全为空"的行：很少上报的测点在 30 天范围内可能读出大量空行（应加 `"col" IS NOT NULL OR …` 条件）
- `date_bin` 按 1970 原点对齐，按跨度选桶时首尾可能多出一个桶（最多 1001 点）
- InfluxDB 的查询/规划错误同样返回 5xx，目前被映射为 503「时序库暂不可用」。2026-10-26 已在 VM 复现文件扫描超限（432 个 Parquet 文件），近期优先调整有界扫描参数、细分错误提示并标记失败后保留的旧曲线范围；分段查询归入 V0.3-E。见 [处理计划](superpowers/plans/2026-10-26-history-query-limits.md)，待实施。
- 物模型类型与列实际类型不一致时（如旧浮点列被定义为布尔）的展示与聚合
- 历史接口中 `IllegalStateException` / `IllegalArgumentException` 走 Spring 默认错误页而非 ProblemDetail
- 参数错误信息直接回显原始测点名（未截断）；标识符正则与保留名在 `telemetry` 与 `thingmodel` 各有一份
- 演示物模型 merge 导入会重新补回用户主动删除的演示属性
- 多实例部署时，新导入的物模型最多 60 秒后在其他实例生效，期间新测点会被 `FIELD_REJECTED`（本地 IDEA 与演示环境同时消费时可见）
- 图表：枚举值不连续或只有一个枚举项时纵轴刻度不理想；30 秒刷新会打断正在进行的框选
- 测试缺口：全新库上的 `InfluxSchema`、面板级页面隐藏/恢复、404 断言仅校验状态码

## 前端体验优化遗留（2026-10-15）

- 首页总览与产品列表的在线数、最近上报按"读出租户全部设备 + Redis 批量读取"统计；设备到 1 万台规模时改为增量计数（例如按产品维护在线计数或在线集合，在上下线事件中更新）。
- 最近 1 小时死信按死信流倒序扫描、最多 1 万条（全平台共用一条死信流），超过时计数封顶；需要精确统计时改为按租户/产品分流或写入计数器。
- 死信的 `pk` 取自设备主题，设备可以伪造其他租户的 productKey 来抬高对方的「最近 1 小时死信」；应由服务端按设备真实所属租户归属死信。
- 应用时钟与 Redis 流时间（死信流 ID）可能存在偏差，影响「最近 1 小时」的截止点。
- 迷你趋势线与历史图表只在窗口 resize 时重绘；容器尺寸变化（侧栏折叠等）应改用 ResizeObserver。
- 最新值 5 秒轮询在页面隐藏时不暂停。
- `?props` 在选择变化后不再同步，会变旧。
- 首页列表行是 role=button 而非真正的链接；首次加载失败时骨架屏不会消失。
- 产品列表没有关键字筛选（`GET /api/v1/products` 无 keyword 参数），产品变多时再加。
- 缺少产品计数（deviceCount/onlineCount）的跨租户测试。
- `index.html` 内联的主题预渲染脚本：以后加严格 CSP（`script-src`）时需要给它配 nonce 或 hash。
- `LatestValueStore` 解析最后上报时间用的 `Long.parseLong` 未做容错，某台设备的值损坏会让 `/overview` 返回 500。
- 缺少测试：首页轮询在页面隐藏时暂停、`/` 重定向到 `/home`；设备列表"保留已应用筛选"的翻页测试偏弱（没有断言请求了第 2 页）。
- `DeviceStats.Snapshot.onlineKeys` 是可变集合，应改为不可变。
- 产品详情页在路由参数 `productKey` 变化时不重新加载（同组件复用时显示旧产品）。

## 设备筛选与在线状态遗留（2026-10-15）

- 在线状态 TTL 兜底：EMQX 断连事件丢失时设备会一直显示在线；应加 lastSeen 超过 N 分钟则视为离线的兜底（lastSeen 已由数据路径写入，事件路径不写）。
- 设备列表 `online` / `reported` 筛选目前读出全部匹配设备的 Redis 状态再内存分页（O(设备数)）；设备到 1 万台规模时改为维护在线集合（Redis Set / ZSet）后直接分页，与首页统计的增量计数一并做。
- 语义差异：`reported` 依据设备时间戳（最新值 `__last`），`online`（数据驱动部分）依据 transport 的 receivedAt；设备时钟偏差大时两者可能不一致，界面上需留意。
- 数据驱动在线只在设备有上报时生效；仍在连接但长期不上报的设备在漏掉连接事件时依旧显示离线。
- EMQX 重启后客户端立即重连时，可能在 HTTP 鉴权资源就绪前完成连接，导致 transport 未获得超级用户、设备发布被授权拒绝（日志 `not_authorized` / `authorization_permission_denied`）；临时办法：EMQX 健康后通过 API 踢掉全部客户端让其重新鉴权。待查明 EMQX 5.8 在鉴权资源未就绪时的行为，并考虑 transport/模拟器在授权失败（SUBACK/PUBACK 错误码）时主动断开重连。V0.2-R 起 transport 在订阅被拒或断线后由选主协调器重新连接；模拟器的设备尚未处理（e2e 的设备遇 PUBACK 错误码会重连）。

## V0.2-C 遗留（2026-10-16）

- 改密码不会吊销该用户的其他会话；做法：令牌带 token 版本声明，续期时校验。
- 登录耗时可泄露用户名是否存在（用户名不存在时不做 BCrypt 比对）；可对不存在的用户做一次假比对抹平。
- 用户名唯一性区分大小写（`Admin` 与 `admin` 可同时存在）。
- 停用租户时清除设备身份缓存与并发读取之间存在竞态，最长由 5 分钟缓存 TTL 兜底。
- 删除租户不加锁，由外键约束兜底（并发新建产品或设备时删除会失败而不是留下孤儿数据）。
- 停用租户时已连接的设备不会被踢下线（需调用 EMQX API 踢连接）；其上报进入 `TENANT_DISABLED` 死信。
- 代入写操作留痕并入 V1.0 审计表；目前只有 server INFO 日志（`Impersonated write by platform admin …`）。
- 没有登录失败锁定（暴力破解防护）。
- 没有令牌吊销：禁用用户、停用租户、修改角色、重置密码对已签发的 access token 最长 15 分钟后才生效。
- 平台统计（平台首页、租户列表）每次读出全平台用户、设备与 Redis 在线状态，开销 O(设备数)；规模变大时与首页统计一起改为增量计数，租户列表只统计本页租户。
- 平台管理员只能在「库中没有平台管理员」时由环境变量创建；没有管理多个平台管理员的界面。
- HomeView 的租户首页行仍使用 `li role=button`，应改为真正的按钮或链接。
- 进入/退出租户或登出时，若此前发起的续期请求仍在途，其响应可能用旧的 refresh Cookie 覆盖新的（前端只丢弃了旧响应体，Cookie 由浏览器写入）；登出后刷新页面可能恢复会话。可在服务端为 refresh 令牌加版本号，或登出时让旧 refresh 失效。
- `AdminBootstrap` 按环境变量创建账号时未校验用户名规则（UsernamePolicy）。

## V0.2-R 遗留（2026-10-16）

- **【已解决，2026-10-19】transport 正常停止交接**：该问题于 EMQX 5.8.6/5.8.9 上发现，表现为持久会话补发错位，300 条约丢 4～7 条。升级到当前项目版本 5.10.5 后，专项实验 9 次结果全部正确，未再复现，因此关闭该遗留项。此结论不覆盖 Broker 进程强杀或客户机断电；5.10.5 在这些场景仍可能丢已 PUBACK 的尾部消息，见 [持久性验证](durability-validation.md)。

- 独立部署包的 `conduit-transport-2` 从 YAML 锚点继承了 `build:`，`docker compose up --build` 为两个 transport 各构建一个内容相同的镜像（第二次命中 BuildKit 缓存，耗时很短）。可改为后者 `build: !reset null` 并共用前者的固定镜像名，需先确认 `up` 时不会在构建前去拉取该镜像。
- 租户发现代码在 `finally` 里 `return`，可能吞掉 `Error`；下次改动该文件时一并修正。
- 评估 EMQX 持久共享订阅（磁盘持久会话已关闭，原先的互斥阻塞前提已消失）后改回「多实例共享订阅 + 持久会话」，提升接入吞吐；当前单活多备只有主实例消费。
- 主备切换窗口（最长一个续期间隔）内，旧主若未察觉失锁，可能与新主以同一 clientId 互相踢；协调器重连前先续期可止住，期间的重复消息由写库去重吸收。
- transport 健康检查状态恒为 UP，备实例与「主实例但连不上 EMQX」都只体现在 details（`role`、`connected`）里；需要告警时再细分。
- 模拟器每次启动都重置所模拟设备的密钥；累计量与游走状态只在内存中，重启后从合理初值重新开始，曲线在重启处不连续。
- 模拟器按单位与名称关键字识别测点，识别不了的按上下限随机游走；新增行业物模型时可能需要补关键字。
- e2e 的 EMQX 使用 20000～29999 中随机的固定宿主机端口（docker restart 后端口不变），极少数情况下端口被占用会换端口重试，最多 5 次。

## V0.2-R2 延后（2026-10-20）

- 设备详情展示「最近是否使用可靠上报」。可靠上报按报文是否带 `id` 自动启用，属静默降级：设备漏带 `id` 时平台不报错，界面提示能帮助发现，但会牵动总览与设备接口，本期不做。
- 平台侧按「设备 + id」去重。当前靠「同设备同 `ts` 覆盖」的存储幂等性，只有设备不带 `ts` 且回执丢失时才会出现重复点（值相同）；若实际出现设备既不带 `ts` 又频繁重启，再补去重。

## V0.2-R3 遗留（2026-10-21）

- **【暂不处理，2026-10-21 决定】EMQX 持久会话不可恢复时缺少兜底**。2026-10-21 开发环境实际发生：`conduit-transport` 的持久会话损坏，每次重连都使 EMQX 连接进程崩溃（`emqx_ds_replication_layer:subscribe` function_clause），接入中断约 20 小时且不自愈，EMQX 磁盘积压 3.3 GB，人工以 `cleanStart=true` 连接一次丢弃会话才恢复（详见 `docs/test-reports/2026-10-20-telemetry-replay.md` 第 3.1 节）。

  **2026-10-21 专项实验结论**（EMQX 5.10.5，每场景 3 轮，客户端配置与真实 transport 一致）：

  | 场景 | 结果 |
  |---|---|
  | 同 clientId 活体接管（即正常主备切换） | 3/3 正常恢复 |
  | 连续 5 次活体接管 | 3/3 正常恢复 |
  | 旧主进程被强杀后接管 | 3/3 正常恢复 |
  | 正常 DISCONNECT 后重连 | 3/3 正常恢复 |
  | **服务端 `emqx ctl clients kick`** | **3/3 会话损坏，且后续客户端全部无法订阅** |

  要点：①**主备切换不会损坏会话**，所以「同 clientId 互踢防双主」的设计本身没有风险，日常重启与部署是安全的；②人为 kick 必然损坏，且与是否有未确认消息、是否订阅 `$SYS` 都无关，推翻了 2026-10-16「在途消息绊倒恢复路径」的假设；③损坏后不自愈，表现统一为「CONNACK 成功但订阅被服务端掐断」。

  **仍未知**：2026-10-21 那次的确切触发原因——实验复现出的崩溃都在 `emqx_persistent_session_ds` 重放路径，与事故的 `emqx_ds_replication_layer:subscribe` 不是同一栈帧。未测：EMQX 重启时会话带在途状态、节点名或配置变更、损坏会话长时间后是否自愈。

  **决定**：暂不实现自动兜底。主备切换既然安全，该故障属低频边界场景；再次遇到时按「以 `cleanStart=true` 连接一次丢弃会话」人工恢复，并在那时重新评估。若要实现，判定条件用统一表征即可：连接成功但订阅连续失败 N 次，不必区分成因。

  **2026-10-26 重新评估**：演示 VM 的 EMQX 连接堆上限 32 MB 触发关闭后，旧持久会话出现确认序号异常、实时采集停滞；扩容到 256 MB 未自动恢复。用户选择保留旧会话、备份数据卷并切新 clientId 恢复实时采集，约 10.5 万条旧会话积压仍待独立处理。撤销「只有人为 kick 才值得处理」的低频判断，将会话停滞检测、告警、保留原会话的切换机制、备份恢复演练和积压处置列为后续可靠性工作。触发与序号异常间的因果关系仍需分析，详情见 `docs/test-reports/2026-10-26-telemetry-freshness.md`。

- **流水线每条消息 2 次 Redis 往返**（2026-10-28 定位）：`DeviceIdentityCache.find()` 的 GET 与 `LatestValueStore.update()` 的 EVALSHA 在逐条循环内，构成入库约 3,000 条/秒的上限。批量化方向：身份查找改一次 MGET、最新值改一次 pipeline 或多设备 Lua，可把往返由 `O(消息数)` 降为 `O(1)`/批。**必须保持 Lua 脚本按测点比较时间戳的语义**（旧数据不覆盖新值）。见 `docs/test-reports/2026-10-28-pipeline-redis-roundtrips.md`。

- **压测负载端需要升级**（2026-10-29 定位）：现有 Python + 600 线程的负载端在约 3,500 条/秒即出现线程颠簸，且目标越高实际打出越少（8,000 档 2,857、10,000 档 1,679），导致系统容量天花板测不出来（只知道 ≥ 6,600 条/秒）。改进方向：多进程替代多线程绕开 GIL，或多台机器并发发压。见 `docs/test-reports/2026-10-29-capacity-after-batching.md`。

## 2026-10-29 压测 VM 最大吞吐遗留

详见 [测试报告](test-reports/2026-10-29-vm-max-throughput.md)。

- **把 VM 上验证过的配置写成仓库默认值**（所有者决定暂缓）：EMQX `max_mailbox_size` 10000、`max_inflight` 1024、transport `batch-size` 512。
  这三项目前只在压测 VM 上，按仓库默认部署**无法复现**报告中的容量数字。写入前需确认约束 `batch ≤ inflight ≪ mailbox` 仍成立，
  且 `max_mailbox_size` 只调信箱、堆上限 256MB 不动（见 `CLAUDE.md` 第 4 节「方向调整」条）。
- 信箱 1000 与 transport 共享订阅的组合未测：拆成 4 条连接后单进程信箱压力已大幅下降，可能不再需要调大信箱。
- **海量连接的容量未测**：现有数字来自 600 条连接各发高频消息，不能换算成设备数。真实设备约 10 秒一报，
  10,000 条/秒对应约 10 万条连接，EMQX 维持连接的内存与调度、鉴权回调压力、在线状态写入量都需单独压测。
- 需要 Docker 的集成测试未在本轮运行（所有者决定不跟进）：`MqttProtocolAdapterIT`（含共享订阅退订旧过滤器的真实 broker 行为）、
  `ReliabilityE2EIT`、`LatestValueStoreIT`（含新增的乱序补传与 120 测点用例）、`PlatformOverviewApiIT`。
- 消费组中死消费者累积：流水线消费者名为 `server-${random.uuid}-序号`，每次重启都换新名，旧消费者留在消费组中
  （压测 VM 上已累计 20 余个），现在每次重启多留 4 个。不影响正确性（`claimStale` 会认领其遗留），但会无限增长，
  需在启动时清理无待确认消息的旧消费者，或改用稳定的消费者名。
- 可靠无损 11,500、硬件墙 16,000，墙已在 CPU：再往上需降低每条消息的 CPU 开销（EMQX 约占 VM 的 35～40%，其次是
  server、transport、Redis），或加核（所有者当前决定不加）。
- `max_inflight=512` 那组 A/B 实验跑在坏掉的发压器上（100 台设备静默鉴权失败），绝对值不可信，未用修复后的发压器重做。
- **transport 会话监控采集 `mailbox_len` 与 `reductions`**（看板缺一个经验证的「连接被杀前兆」）：EMQX 的
  `/api/v5/clients/{clientid}` 已返回这两个字段（2026-10-29 实测），`SessionSnapshot` 未解析。`mailbox_len` 是每条
  连接进程的信箱长度，超过 `max_mailbox_size` 即被强杀，是真正的前兆；`reductions` 求速率即该连接进程的忙碌度，
  也就是当天花一下午用 recon 手工测出的「单连接进程顶满单核」——有了它这个瓶颈在看板上一眼可见。
  加上后须照例用过载与健康两种数据验证其确实能区分，再标阈值。
