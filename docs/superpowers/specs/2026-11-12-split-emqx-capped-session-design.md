# 拆分部署 EMQX 会话改回有上限，并补积压与内存告警

- 日期：2026-11-12
- 决定人：所有者（「改回有上限再复测；加告警；提交推送」）
- 起因：[`2026-11-12-stress-to-failure.md`](../../test-reports/2026-11-12-stress-to-failure.md)
  - 拆分部署 EMQX 会话三项此前取最大值：窗口 65535、队列不限、关闭强杀。
  - 结果：每秒 25,000～30,000 条时，积压把整台 EMQX 主机内存耗尽，进程被 Linux OOM killer 杀掉两次，约 932 万条已确认消息丢失。
  - 整个过程中 transport 健康检查一直为 UP，没有任何告警。

## 1. 配置改动（`deploy/split/emqx/.env.example`）

| 项 | 旧值 | 新值 | 理由 |
|---|---|---|---|
| `EMQX_MAX_INFLIGHT` | 65535 | 1024 | 须满足 `batch-size(512) ≤ max_inflight ≪ max_mailbox_size(10000)`；1024 是 2026-10-29 压测 VM 验证过的组合 |
| `EMQX_MAX_MQUEUE_LEN` | infinity | 50000 | 满了丢弃新消息并计入 `mqueue_dropped`。先试 100000（与其他部署包一致），复测时连接堆先超限被强杀，改为 50000 |
| `EMQX_FORCE_SHUTDOWN_ENABLE` | false | true | 单连接信箱 / 堆超限时只杀该连接，EMQX 进程与其他连接不受影响 |

- 信箱 10000、堆 256MB（实际按 1.5 倍即 384MB 触发）、连接队列与报文上限保持不变。
- 有上限后，内存上界约为「会话数 × 单连接堆上限」：transport 4 条会话 × 384MB ≈ 1.5GB，远低于 EMQX 主机的 7.7GB。
- 队列 5 万的依据：
  - 实测每条排队消息在堆里约 0.1～1.9 KB。按最坏值算，常驻约 96MB，为堆上限的四分之一，给垃圾回收时的成倍膨胀留出余量。
  - 4 条会话合计可缓冲 20 万条，能覆盖总速率约 11,700 条/秒以内的主备切换窗口（约 17 秒）。
- 代价：过载时不再「先全部收下」，而是在队列满后丢弃，或强杀单条 transport 连接、丢它的积压。
- 不选的方案：只给 EMQX 容器设内存上限。容器被杀与整机 OOM 的效果相同，积压照样全丢，只是不波及主机上的其他进程。

## 2. 告警（`conduit-transport` 会话监控）

告警都**只记 WARN、发布仪表，不改变健康检查**，与既有约定一致：只有投递停滞置 DOWN。

1. **积压绝对值**：单个会话 `mqueue_len ≥ mqueue-warn-len`（默认 50000，0 关闭）时告警，明细 `mqueueBacklogHigh`。
   - 原有的「队列水位」按容量的 80% 算；队列不限时 EMQX 报告的容量为 0，该项失效。绝对阈值在任何配置下都有效。
2. **EMQX 主机内存**：transport 主实例轮询 `GET /api/v5/nodes`，内存占比 ≥ `node-memory-warn-ratio`（默认 0.8）时告警。
   - 明细放在健康检查 `leader.details.session.emqxNode` 下。
   - 仪表：`conduit.transport.emqx.memory.used.bytes` / `.total.bytes` / `.used.ratio`。
   - EMQX 把内存报告为带单位的字符串（如 `"1.22G"`），按 1024 进位解析，精度两位小数，足够判断水位。
   - 多节点时取占比最高的节点。
   - 采样失败、或本实例转为备实例时，仪表置 NaN。

配置项：`conduit.transport.monitor.mqueue-warn-len`（环境变量 `CONDUIT_TRANSPORT_MONITOR_MQUEUE_WARN_LEN`）、`conduit.transport.monitor.node-memory-warn-ratio`（`CONDUIT_TRANSPORT_MONITOR_NODE_MEMORY_WARN_RATIO`）。

## 3. 验证

- 单元测试：`conduit-transport` 共 113 个，包括新增的节点内存解析、阈值、NaN 清零、组汇总，以及 Spring 绑定。
  - 绑定测试是部署时踩坑后补上的：record 多了便捷构造器以后，规范构造器必须标 `@ConstructorBinding`。
- 部署回读：
  - EMQX：`max_inflight = 1024`、`max_mqueue_len = 50000`（第二轮为 100000）、`force_shutdown.enable = true`。
  - transport：健康检查出现 `emqxNode`，仪表有值。
- 复测：同一方法逐档提速，见测试报告第 8 节。
  - 队列 10 万：15,000 条/秒起连接被堆超限强杀、积压整段丢失。
  - 队列 5 万：零强杀、零掉线，15,000 条/秒以内无损，超出部分按队列满丢弃且可计数。

## 4. 架构影响

transport 原本就经 EMQX REST API 轮询会话，本次只多读一个接口，模块职责、拓扑与数据流不变，架构图无需修改。

## 5. 推广到全部部署包（2026-11-13）

- 所有者决定：会话上限统一，独立部署、拆分部署与开发环境包取同一套值。
- 依据：[`2026-11-13-standalone-session-limits.md`](../../test-reports/2026-11-13-standalone-session-limits.md)。
  - 独立部署按旧默认（信箱 1000、队列 10 万）在每秒 8,000 条时就被 `mailbox_overflow` 强杀；
  - 改为信箱 10000、队列 5 万后零强杀，15,000 条/秒以内无损，与拆分部署相同。
- 改动：
  - `deploy/dev/docker-compose.yml`：队列默认 50000，新增 `EMQX_FORCE_SHUTDOWN__MAX_MAILBOX_SIZE: ${EMQX_MAX_MAILBOX_SIZE:-10000}`。独立部署经 extends 继承。
  - `deploy/split/emqx/docker-compose.yml`：删去自带的信箱定义（原默认 1000），改由 dev 文件统一定义。
  - 三个 `.env.example`：队列 50000，新增 `EMQX_MAX_MAILBOX_SIZE=10000`。
  - Grafana 看板「强杀线」变量默认改为 10000。
- 不变：窗口与批（独立部署与开发包 512 / 64，拆分部署 1024 / 512）、堆上限 256MB、开启强杀（EMQX 默认即开启）。
- 分布式部署按路线图放到 V0.8，届时沿用这套单连接上限，单独压测容量。
