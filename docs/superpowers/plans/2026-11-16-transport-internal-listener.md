# transport 内部监听器实施计划（2026-11-16）

- spec：[`2026-11-16-conduit-transport-internal-listener-design.md`](../specs/2026-11-16-conduit-transport-internal-listener-design.md)
- 状态：已完成（2026-11-16），验收见 [`2026-11-16-transport-internal-listener.md`](../../test-reports/2026-11-16-transport-internal-listener.md)；server 集成测试 280 个与 e2e 5 个场景于 2026-11-17 在 129 上补跑，全部通过。

## 全局约束

- 不上调 EMQX 连接进程堆上限（256 MB）。
- 设备侧的端口、鉴权、ACL 都不变。
- 测试先写失败用例。

## 任务

1. **server 鉴权**
   - `MqttAuthService.authenticate` 增加 `internalListener` 参数；新原因 `DEVICE_ON_INTERNAL_LISTENER`，计数器共 10 个序列。
   - `MqttAuthController` 读取请求头 `X-Conduit-Listener`。
   - 测试：`MqttAuthServiceTest`（单测，先失败后通过）；`MqttAuthIT` 新增用例。IT 依赖 Testcontainers，本环境无法运行，见测试报告。
2. **transport 默认值**
   - `MqttTransportProperties` 端口默认改为 1884，连接数默认改为 8；`application.yml` 同步修改。
   - 测试：`MqttTransportPropertiesTest`（先失败后通过），transport 模块全部单测通过。
3. **EMQX 配置模板与部署包**
   - `base.hocon.tpl` 新增 `listeners.tcp.internal`，带自己的鉴权链。
   - dev compose：加 zone 环境变量，发布 1884。
   - standalone：不发布 1884，transport 改连 `emqx:1884`，连接数 8。
   - split app：transport 连 `${MQTT_INTERNAL_PORT:-1884}`。
   - 四个 `.env.example` 加入新变量。
   - 先在 EMQX 5.10.5 临时容器上验证监听器与 zone 生效。
4. **e2e**：`E2eEnvironment` 同时映射内部端口（设备端口 + 10000），transport 连内部端口；在 profile `e2e` 下编译通过。
5. **文档**：`CLAUDE.md`、README、`deploy/split/README.md`（网络要求、升级时丢弃旧会话）、`architecture.svg` 标注。
6. **测试环境部署与验收**
   - 128 重建 EMQX，129 用新 jar 重建 server 与 transport。
   - 丢弃 4 个旧会话，回读 8 条连接都在 `tcp:internal` 上、`mqueue_max` 为 100,000。
   - 设备连 1883 成功、连 1884 被拒，计数 `device_on_internal_listener` 为 1。
   - 压测：断网补传（2.5 万台、断网 300 秒、每台每秒补 5 条）、测法四画像 2.5 万台、每秒 1 条 8,000 台。
