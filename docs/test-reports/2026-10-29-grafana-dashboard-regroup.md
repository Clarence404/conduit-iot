# 2026-10-29 性能看板重排与「守恒差」校准

## 被测范围与代码状态

- 看板 `deploy/dev/grafana/dashboards/conduit-bottleneck.json`（「Conduit 性能瓶颈观测」，Grafana 第 11 版）。
- 改动：总览按「吞吐 / 有没有丢 / 余量」三行重排；新增守恒差；信箱与 reductions 改为占上限的百分比（新增变量 `max_mailbox_size`）；速率图加压测 VM 容量参考线；三类事件标注（服务重启、EMQX 重启、transport 主备切换）；EMQX 组加设备鉴权与鉴权回调耗时；共享订阅数、消费者数移入对应分组；JVM 线程数保留。
- 代码未改，只改看板。基线提交 `6cb11ac`。

## 环境

压测 VM（8 vCPU，独立部署包 + `metrics` profile），配置为 VM 上的实际值（`max_mailbox_size` 10000、`max_inflight` 1024、transport 批 512，与仓库默认不同，见 CLAUDE.md「部署漂移」条）。发压用 `docs/script/loadtest/loadgen.py`，600 条连接；实验期间停模拟器、先执行 `loadgen.py refresh`。

## 测试类型

看板查询正确性验证 + 用已知答案的受控实验校准守恒差（两次小负载，均在容量以下，不是容量或压力测试）。

## 结果

### 1. 全部查询回看 12 小时

52 个面板 97 条查询逐条在 Prometheus 上执行（`$__range` 取 30m、`$max_mailbox_size` 取 10000），全部有数据；全为 0 的 3 条均为死信与测点拒绝，今天确实未发生。数据源 uid 只有 `prometheus`，面板无重叠。

### 2. 守恒差：第一版口径被实验否定

第一版用 `increase()` 与 server 进程内的入库计数：

| 实验 | 真值 | 第一版读数 |
|---|---|---|
| 正常 6,000 条/秒 × 120 秒 | 无丢失（InfluxDB 行数 = 发送数） | 0.000% |
| 3,000 条/秒 × 90 秒，中途停 server 40 秒 | 发送 270,000，InfluxDB 270,000，无丢失 | 3.3%～15.6% |

原因：`increase()` 按窗口两端外推，速率陡变时偏几个百分点；server 重启时进程内计数丢掉最后一次抓取后的那截（实测假报 15.6%）。而真实丢失（下午 1.55%～4.8%）落在这个误差里，无法区分。另发现 server 停止时入库计数序列消失，第一版整条表达式为空。

### 3. 守恒差：定稿口径

改为全部用 EMQX / Redis 中重启不归零的计数器、两端原始值相减：设备上行（QoS 1）+ EMQX 上下线计数 − 消费组已读出 − lag 变化 − 会话积压变化。上下线计数必须加回：transport 把上下线事件也写进总线，未加时空闲区间恒差 −1,215 / −1,748，加上后为 0。

| 窗口 | 设备上行 | 守恒差 | 真值 |
|---|---|---|---|
| 三次实验全程 20:13–20:31（两端空闲） | 1,534,985 | −1 | InfluxDB 1,531,696，无丢失 |
| 闲时 18:00–20:00 | 197,280 | 0 | — |
| 16:20–16:45 | 2,786,627 | −912（重投） | — |
| 17:10–17:45 过载 16,000 条/秒 | 2,903,600 | **391,593** | InfluxDB 2,496,701，实少约 40.7 万 |
| 终点落在负载中 | — | 不计算 | — |

负载中 EMQX 与 Redis 抓取时刻不一致，差值整体平移「速率 × 约 9.4 秒」（6,000 条/秒时 ±5.6 万、3,000 条/秒时 ±2.8 万），与丢失无关。因此只在时间范围两端都空闲（设备上行 < 200 条/秒）时出数，否则显示「负载中不计算」。阈值：黄 500、红 2,000 条。

### 4. 发现：EMQX 丢弃计数漏掉绝大部分丢失

17:10–17:45 那次实少约 40.7 万条，而原「丢弃」格子用的 `emqx_messages_dropped` 增量为 0。逐个查 EMQX 丢弃类计数器，只有 `emqx_delivery_dropped_queue_full` 计到 5,190 条；EMQX 日志有一次连接进程 `maximum heap size reached` 强杀，以及被限流折叠的 5,189 条 `mqueue_is_full`。随强杀进程丢失的消息 EMQX 不计数。处置：「EMQX 丢弃」改为 `messages_dropped + delivery_dropped`，并在说明中写明它不含强杀丢失、要看守恒差。

另：此前按 `check_oom_shutdown` 单一关键词搜强杀日志得 0 条，实际有，须用 `check_oom_shutdown|maximum heap size|mqueue_is_full|mailbox_overflow`。

### 5. 事件标注回看 12 小时

服务重启 16 处、EMQX 重启 5 处、主备切换 19 处，均与今天的部署、重建与故障注入时段吻合（例如 20:01 重建 `conduit-transport-2`、14:3x 故障注入切换）。

### 6. 其他口径修正

- 接入 QPS 改用 `emqx_messages_qos1_received`：`emqx_messages_received` 含 transport 发给设备的 QoS 0 回执，实测恰为入库数的 2 倍。
- 鉴权拒绝取 EMQX `emqx_authentication_failure`：server 对拒绝按 EMQX HTTP 鉴权协议回 200 + `result: deny`，HTTP 指标分不出放行与拒绝。

## 部署与未验证项

- 已部署到压测 VM，Grafana 数据库确认加载第 11 版（3 个标注、1 个变量、分组展开状态正确），Grafana 日志无报错。
- **未验证**：未经 Grafana 前端实际渲染（无法代为登录），需所有者打开确认。容量参考线 11,500 / 13,000 只适用于压测 VM。
- 实验期间停过两次 server，模拟器已恢复运行。

## 命令

受控实验与核对脚本在会话临时目录（`validate_conservation.py`、`audit_dashboard.py`、`gen_dashboard.py`），InfluxDB 行数按 `select count(1) from property where product_key = <压测产品> and time` 窗口查询，token 在 VM 上就地读取。
