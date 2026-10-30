# 2026-10-30 MQTT 鉴权按原因计数

## 被测范围与代码状态

- `conduit-server` 的 `MqttAuthService` 新增计数器 `conduit.mqtt.auth{result, reason}`（Prometheus 名 `conduit_mqtt_auth_total`），启动即注册全部 9 个序列。
- 看板「设备鉴权（次/秒）」叠加 `sum by (reason) (rate(conduit_mqtt_auth_total{result="deny"}[1m])) > 0`。
- 基线提交 `e495a3d`，改动在其上（随本报告一起提交）。

## 测试类型

功能验证（单元测试 + 压测 VM 上用已知结论的真实连接核对计数）。不是容量或压力测试。

## 结果

### 1. 单元测试

- 命令：`mvn -o test -Dtest=MqttAuthServiceTest`（JDK 21），先写测试、确认因构造器缺少 `MeterRegistry` 编译失败，再实现。
- `MqttAuthServiceTest` 2 个用例通过：9 种结论各计入对应原因（含 null 参数 → missing_credentials）；启动即注册 9 个序列且初值为 0。
- server 模块全部单元测试：`mvn -o test`，2,283 个，失败 0、错误 0、跳过 0。
- 未执行：`MqttAuthIT`（Testcontainers 集成测试），本次未运行；该 IT 只断言 HTTP 响应，构造器变化由 Spring 注入，行为未改。

### 2. 压测 VM 实测

server jar 以 HEAD 加本改动构建，上传到 VM 的 `artifacts/` 后 `docker compose build/up -d conduit-server`（旧 jar 备份为 `.bak-20261030`），健康检查 healthy 后，Prometheus 中 9 个序列全部出现、初值 0。

用 paho 对 EMQX 发起已知结论的连接（MQTT 5），45 秒后比对计数增量：

| 尝试 | 客户端看到 | 计数增量 |
|---|---|---|
| devices.json 中 2 台设备（密钥已过期） + 3 次故意错密钥 | Not authorized ×5 | `deny/wrong_secret` +5 |
| 不存在的设备 | Not authorized | `deny/unknown_device` +1 |
| clientId 与用户名不一致 | Not authorized | `deny/client_id_mismatch` +1 |
| `loadgen.py refresh` 后 2 台设备正确密钥 | Success ×2 | `allow/device` +2 |

EMQX `emqx_authentication_failure` 10 分钟增量 17，与 server 各拒绝原因之和 17（15 + 1 + 1）一致。

### 3. 意外发现（即该指标的用途）

refresh 期间 `wrong_secret` 额外增加 10 次，不是探测脚本发起的。EMQX 日志该时段的 `authentication_failure` 来源 `172.18.0.16`，即模拟器容器：`refresh` 重置了 600 台压测设备的密钥，其中 100 台同时由模拟器模拟（`SIM_MAX_DEVICES_PER_PRODUCT=100`），模拟器以旧密钥重连被拒。重启模拟器后其 274 台设备重新通过（`allow/device` 2 → 276），`wrong_secret` 停在 17 不再增长。此前这种冲突只能靠对数推断，现在看板上直接按原因可见。

## 部署与未验证项

- 已部署：VM 上 server 与看板 JSON（Grafana 自动重新加载）。
- Grafana 前端渲染：所有者登录后在浏览器中查看（范围 08:50–09:35）。「设备鉴权」图正常显示通过、被拒与 client_id_mismatch / unknown_device / wrong_secret 三个原因，时间与探测、复验吻合；「鉴权回调耗时」图正常，平均最大 9.54 ms，09:21 前后 600 台集中连接时最大值到 840 ms。
- 前端查看发现两处显示问题并已修正：①按原因的查询原用 `> 0` 逐点过滤，序列只剩非零点，图例「Last」把最后一个非零值当成当前值（显示 wrong_secret「当前 2.22 次/秒」，实际早已归零）；改为保留零值、以 `increase(...[$__range] @ end()) > 0` 只隐藏整个范围内未出现的原因，修正后各序列 Last 均为 0。②wrong_secret 与「被拒」同为红色、重叠时无法区分；改为每个原因固定颜色，「被拒」为红色虚线。修正后再次在浏览器中确认。
- VM 状态：模拟器已重启并恢复上报；devices.json 中前 100 台设备的密钥已被模拟器再次重置，下次压测前须先执行 `loadgen.py refresh`（照旧）。
