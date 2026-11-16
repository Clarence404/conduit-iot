# transport 独立内部监听器与 zone 设计（2026-11-16）

- 状态：所有者已确定方向（根治断网补传丢数），参数取方案 B，内部监听器只许 transport 连接。
- 起因：[`2026-11-16-connect-storm-outage-replay.md`](../../test-reports/2026-11-16-connect-storm-outage-replay.md)。2.5 万台设备断网 5 分钟后同时补传，补传洪峰让 transport 订阅连接的 EMQX 进程信箱超过 10,000 条，连接被 `force_shutdown` 强杀，内存会话随之销毁，少了 25 万条。

## 1. 目标与不做的事

**目标**
- 设备侧怎么冲，transport 的订阅连接都不会因信箱溢出被强杀。
- 补传洪峰先由 EMQX 端的会话队列缓冲下来，再由 transport 按入库能力慢慢消化。

**遵守的既有约定**
- 不上调 EMQX 连接进程的堆上限（256 MB，2026-10-29 所有者决定）。信箱上限允许按实测调大。
- 设备接入方式、端口 1883、鉴权、ACL 都不变。

**不做**
- 设备监听器的建连限速和每连接消息限速（另案）。
- 磁盘持久会话（2026-10-28 已决定关闭）。
- 换用其他消息总线。

## 2. 设计

### 2.1 EMQX：新增内部监听器与 zone

| 项 | 设备监听器 `tcp:default` | 内部监听器 `tcp:internal`（新增） |
|---|---|---|
| 端口（容器内） | 1883 | 1884 |
| zone | `default` | `transport` |
| 信箱上限 `force_shutdown.max_mailbox_size` | 10,000（不变） | **200,000**（`EMQX_TRANSPORT_MAX_MAILBOX_SIZE`） |
| 会话队列 `mqtt.max_mqueue_len` | 50,000（不变） | **100,000**（`EMQX_TRANSPORT_MAX_MQUEUE_LEN`） |
| 堆上限、在途窗口等其余参数 | 全局值 | 继承全局值（不上调） |
| 鉴权 | 全局 HTTP 鉴权链 | 监听器自己的 HTTP 鉴权链，回调多带请求头 `X-Conduit-Listener: internal` |

**配置位置**
- 监听器与它的鉴权链写在 `deploy/dev/emqx/base.hocon.tpl`。鉴权链中带内部 token，与全局鉴权使用同一个占位符渲染。
- zone 的两个参数走 compose 环境变量 `EMQX_ZONES__TRANSPORT__…`。

已在 EMQX 5.10.5 上验证（2026-11-16，用临时容器）：
- 监听器启动在 1884，监听器级鉴权链生效；
- `transport` zone 的 mqueue 与信箱取覆盖值，在途窗口与堆上限继承全局值；
- `default` zone 不受影响。

### 2.2 server：内部监听器只许 transport

**处理流程**
- `MqttAuthController` 读取请求头 `X-Conduit-Listener`，取值为 `internal` 即视为内部监听器。
- `MqttAuthService.authenticate` 增加参数 `internalListener`。若来自内部监听器、且用户名不是 transport 用户名，就拒绝，新原因记为 `device_on_internal_listener`。
- transport 仍然可以连设备监听器，用于回退和兼容老部署。

**计数器**：`conduit.mqtt.auth` 启动时注册的序列由 9 个变为 10 个。

**为什么设备伪造不了这个请求头**：它是 EMQX 配置里写死的，只在内部监听器的鉴权链上出现；设备无法控制 EMQX 发给 server 的回调请求头。

### 2.3 transport 与部署包

**transport**
- `conduit.transport.mqtt.port` 的默认值由 1883 改为 1884，即环境变量 `EMQX_PORT` 默认 1884。
- `connections` 的默认值由 4 改为 8。总缓冲为 8 × 10 万 = 80 万条，大于实测洪峰积压（约 70 万条）；每条连接的堆约 120 MB，在 256 MB 以内。

**部署包**

| 包 | 内部端口的暴露方式 | transport 连接地址 |
|---|---|---|
| dev | `${BIND_ADDR}:${MQTT_INTERNAL_PORT:-1884}:1884`，供 IDE 中的 transport 连接 | `localhost:1884` |
| standalone | 不发布到宿主机，只在 compose 网络内使用 | `emqx:1884` |
| split EMQX 主机 | 发布 `${MQTT_INTERNAL_PORT:-1884}`，供应用主机连接；部署说明要求防火墙只对应用主机放行 | — |
| split 应用主机 | — | `${EMQX_REMOTE_HOST}:${MQTT_INTERNAL_PORT:-1884}` |

**其他**
- e2e：`E2eEnvironment` 同时映射 1883 与 1884，transport 连 1884，设备连 1883。
- 升级：transport 以同一组 clientId 改连 1884。EMQX 的会话与监听器无关，所以会接管原会话、补发排队消息。新增的第 4～7 号连接首次使用新的 clientId。

## 3. 风险

- EMQX 内存：8 条连接最坏情况下各 120 MB，约 1 GB。128 共 7.7 GB，测试时观察内存。
- 洪峰积压超过 80 万条时，仍会按 `queue_full` 丢弃，并计入丢弃计数器。可靠上报的设备会重发；不带 `id` 的设备依然会丢，补传节奏规范另案处理。

## 4. 验收

1. **单测**：server 鉴权在内部监听器上拒绝设备、放行 transport；计数器 10 个序列。transport 配置默认值变更。
2. **回归**：server、transport 模块全部测试通过。
3. **压测**：
   - 断网补传同一场景（2.5 万台、断网 300 秒、每台每秒补 5 条）：守恒，transport 连接 0 次被强杀，EMQX 丢弃为 0。
   - 测法四画像 2.5 万台复测通过。
   - 每秒 1 条的 8,000 台复测通过。
4. **文档**：README、`CLAUDE.md`、架构图（标注内部监听器）、部署说明、本 spec 与 plan 同步更新。
