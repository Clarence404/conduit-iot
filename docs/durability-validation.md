# 基础服务部署与持久性验证（2026-10-19）

## 当前部署

- 主机：`192.168.126.128`，CentOS 7，4 vCPU、约 8 GB 内存。
- Docker Engine 26.1.4（静态二进制），Compose 2.29.7；Docker 已设开机启动。
- 部署目录：`/opt/conduit/deploy/dev`，项目名 `conduit`。
- PostgreSQL 16.8：15432；Redis 7.4.2：16379；InfluxDB 3.12.0-core：8096；Explorer 1.11.0：8888；EMQX 5.10.5：1883 / 18083。
- 服务口令随机生成，保存在服务器上述目录的 `.env`（权限 600），本机副本为 `deploy/dev/.env`，不提交。
- 本次 `.env` 设置 `EMQX_IMAGE=emqx/emqx:5.10.5`。开发包、独立部署包和测试镜像现已统一为 5.10.5；这修复了版本配置不一致，不代表 Broker 崩溃持久性问题已解决。
- 鉴权回调：`http://192.168.126.1:8080`。应用没有部署到虚拟机，需在开发机启动 server，并使用同一内部 token 后才能完成设备和 transport 接入。
- Redis 已采用 `appendonly yes`、`appendfsync always`、`no-appendfsync-on-rewrite no`。开发与独立部署共用这份服务定义。
- 本次镜像报告 EMQX Enterprise，Developer / Development 社区许可，到期日 2029-03-01。记录镜像自报信息，不代表已完成许可条款或到期行为评估。

## 问题状态

| 问题 | 当前状态 | 依据 |
|---|---|---|
| EMQX 5.8.6/5.8.9 正常交接时补发错位、丢少量消息 | **已解决** | 升级到当前项目版本 5.10.5 后，专项实验 9 次结果全部正确，未再复现 |
| EMQX 5.10.5 Broker 进程强杀后丢已 PUBACK 的尾部消息 | **可靠上报覆盖** | Broker 限制仍存在；带 `id` 的设备收到平台业务回执前保留本地副本并重发 |
| EMQX 5.10.5 客户机强制重启后新建持久会话未恢复 | **可靠上报覆盖** | Broker 限制仍存在；设备重连后重新订阅回执并重发待确认消息 |
| Redis 已确认写入在客户机强制重启时丢失 | **已处理** | 改为 `appendfsync always` 后三轮均未丢；仍受底层存储是否兑现刷盘约束 |

“正常交接已解决”和“Broker 崩溃的底层持久性限制仍存在”是两个不同故障边界；后者现在由选择可靠上报的设备端副本覆盖，而不是由 EMQX 本身解决。

## 方法与边界

测试使用独立的 `conduit-durability-*` 容器、命名卷与 26379 / 26380 / 21883 端口，未向正式服务写入测试数据。控制程序在 Windows 开发机运行，故障前在开发机保存并 fsync 已确认消息的对账记录。

每轮先写入两个测试 Redis，各 2000 条带独立 `seq` 的 Stream 数据，每次等待 XADD 回复。MQTT 先创建持久订阅，收到 SUBACK 后正常离线，等待 2 秒；发布 500 条 QoS 1 消息，每条等待成功的 PUBACK。之后执行故障。恢复时不重新订阅，检查 CONNACK 的 Session Present 和已取回的消息序号。

- `process`：`docker kill -s KILL` 后重新启动测试容器。客户机内核和页缓存仍在。
- `guest-reset`：MQTT 发布结束后，再向两个 Redis 各写入 100 条并等待回复；核验虚拟机 machine-id 后执行 `echo b > /proc/sysrq-trigger`，不主动 sync、不卸载文件系统，丢弃客户机页缓存。正式基础服务也随虚拟机重启。
- 客户机强制重启不能模拟 VMware 宿主机、物理磁盘或控制器断电。结论限于本次虚拟机与存储栈，不能证明任意硬件故障下零丢数。
- MQTT 会话在故障前创建不久；会话未恢复时，500 条是“本轮经原会话未能取回”，并未证明所有消息实体均已从底层存储消失。没有另测长期稳定会话的重启恢复。
- 每轮恢复后等待最多 35 秒取回 MQTT 消息。结果是可观测恢复情况，非无限等待后的断言。

## 故障结果

以下为每轮缺失数量；Redis process 每轮 2000 条，guest-reset 每轮 2100 条；MQTT 每轮 500 条。

| 故障 | 轮次 | Redis everysec 缺失 | Redis always 缺失 | MQTT 未取回 | MQTT 会话恢复 |
|---|---:|---:|---:|---:|---|
| 强杀进程 | 1 | 0 | 0 | 15（486～500） | 是 |
| 强杀进程 | 2 | 0 | 0 | 124（377～500） | 是 |
| 强杀进程 | 3 | 0 | 0 | 0 | 是 |
| 客户机强制重启 | 1 | 99 | 0 | 0 | 是 |
| 客户机强制重启 | 2 | 100 | 0 | 500 | 否 |
| 客户机强制重启 | 3 | 100 | 0 | 500 | 否 |

将控制程序整理为仓库中的通用 CLI 后，额外运行一轮强杀验证：两种 Redis 均保留 2000 条；MQTT Session Present 为 true，500 条中取回 358 条，尾部 142 条未取回。该轮单列在 JSON 的 `cli_validation` 字段，不并入最初三轮。

逐轮对账与基准原始数据见 [JSON 记录](experiments/2026-10-19-durability-results.json)。`target/ops/` 下另有开发机原始对账文件；服务器的基准 CSV 位于 `/opt/conduit/durability-results/redis-benchmark.csv`。

**结论：Redis 改为 always 有明确依据；EMQX 5.10.5 仍不能保证 PUBACK 对应的消息已经持久化。** Broker 强杀时已经能复现尾部数据丢失，不必等到真正断电才能证伪这个保证。

## EMQX 源码证据

核对官方 `e5.10.5` 标签：

- [`emqx_persistent_message:store_message/1`](https://github.com/emqx/emqx/blob/e5.10.5/apps/emqx/src/emqx_persistent_message.erl)：提交消息时使用 `#{sync => false}`。
- [`emqx_ds_buffer:enqueue_call_or_cast/2`](https://github.com/emqx/emqx/blob/e5.10.5/apps/emqx_durable_storage/src/emqx_ds_buffer.erl)：false 分支使用 `gen_server:cast`，不会等待存储完成；默认 egress flush interval 为 100 ms。
- [`emqx_ds_builtin_local:flush_buffer/4`](https://github.com/emqx/emqx/blob/e5.10.5/apps/emqx_ds_builtin_local/src/emqx_ds_builtin_local.erl)：批量提交使用空 options。
- [`emqx_ds_storage_bitfield_lts:write_batch_opts/1`](https://github.com/emqx/emqx/blob/e5.10.5/apps/emqx_durable_storage/src/emqx_ds_storage_bitfield_lts.erl)：默认 RocksDB write options 为空，没有要求同步 fsync。

这里的“异步排队”和“底层刷盘”是两个边界；仅把刷新间隔调低不能消除二者，也不能把 `sync => true` 自动等同于物理落盘。

后续修复需要单独选择：采用有可验证落盘确认语义的 Broker 版本/实现，或增加设备本地持久队列与应用级回执，直到平台持久化后才删除设备缓存。当前没有热改第三方 Broker，也没有通过清空旧会话来掩盖恢复失败。

## Redis 性能

故障测试结束后单独运行基准：容器内 `redis-benchmark`，每组 10000 条 XADD，256 字节 payload，pipeline=1，1 / 16 个连接，每组 3 轮。表中取三轮吞吐及各轮 P99 的中位数。

| 策略 | 并发连接 | 吞吐（条/秒） | P99（毫秒） |
|---|---:|---:|---:|
| everysec | 1 | 27855 | 0.087 |
| always | 1 | 5643 | 0.383 |
| everysec | 16 | 103093 | 0.559 |
| always | 16 | 41667 | 0.655 |

always 单连接吞吐约下降 80%，16 连接约下降 60%。当前演示负载远低于这个基准，但这不是 Conduit 端到端吞吐测试，也没有覆盖 AOF rewrite、长时间负载或宿主机断电。

[Redis 官方文档](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)说明 always 在每次写入批次执行 fsync；本次实测支持采用该策略，但硬件是否兑现刷盘仍依赖下层存储。

## InfluxDB 恢复事件

客户机强制重启期间，正式 InfluxDB 出现零字节文件 `data/node0/catalog/v3/logs/00000000000000000005.catalog`，报 `buffer too short: expected at least 64 bytes, got 0`，持续启动失败。当时尚未接入业务应用。

停止 InfluxDB 后，将完整 catalog 目录备份到 `/opt/conduit/recovery/2026-10-19/catalog-before-recovery`，确认末尾文件长度为零，再将该文件移出到同目录的 `00000000000000000005.catalog.empty`，保留其他文件与数据卷。重新启动后健康检查通过。此为本次新环境的人工恢复记录，不是可对任意业务库自动执行的修复规则。

这表明端到端断电可靠性还需覆盖 InfluxDB 的 WAL、catalog 与文件存储恢复，不能在 EMQX / Redis 两项之外宣告验证完成。

### 写入落盘边界（2026-10-20 专项调查）

核对 v3.12.0 源码：`no_sync` 默认为 `false`（`influxdb3_types/src/http.rs`、`influxdb3_server/src/http.rs`），写入走 `WalObjectStore::write_ops`（`influxdb3_wal/src/object_store.rs`）等待 `object_store.put_opts` 返回；但 `object_store` 0.12.5 的 `LocalFileSystem::put_opts`（`src/local.rs`）只做 `write_all` 加 `rename`，**没有 `sync_all`**。因此 2xx 的含义是「字节已进入操作系统页缓存、文件已改名」，真正的丢数据窗口是内核回写窗口（Linux 默认约 30 秒），不是 WAL 的 1 秒刷新间隔。

实测：默认设置下强杀进程三轮均 2000/2000 幸存；`no_sync=true` 时 2000 条只剩 1380。因此默认值必须保持不变，不能为了吞吐改用 `no_sync=true`。

这个缺口无法在 Conduit 侧消除：缺的 fsync 在 `object_store` 内部。

### 恢复手段：遥测重放

上述缺口现在有了恢复路径，但它不等于修复。`XACK` 只把消息移出待确认列表，条目本身仍留在 Redis 上行流中直到被 `MAXLEN` 裁剪（默认约 100 万条，可回溯时长随流量变化，当前演示负载约 16 小时），而 Redis 配置为 `appendfsync always`。因此平台管理员可以在「系统管理 → 数据重放」按时间范围把流中仍保留的原始报文重新写入（单次最多 6 小时，两端各多留约 1 分钟，因为条目 ID 取自 Redis 服务端时钟）。重放幂等、不更新在线状态、不重复写死信。

边界：重放只能找回仍在总线中的数据，超出保留范围的丢失无法恢复；InfluxDB 自身的写入仍不是持久的，下次断电同样会丢回写窗口内的数据，只是之后可以补回。步骤见 README「运维：InfluxDB 断电后恢复」。

## 复现入口

工具位于 [`docs/script/durability`](script/durability)：

1. 在可中断的专用虚拟机启动 `compose.yml`；控制程序跨机器运行时将 `TEST_BIND_ADDR` 设为虚拟机私网地址。测试 Broker 无应用鉴权，只在测试网开放。
2. 控制机使用 Python 3.12，执行 `pip install -r docs/script/durability/requirements.txt`；通过环境变量 `CONDUIT_SSH_PASSWORD` 提供密码，使用已核验的 SSH ED25519 公钥 base64 字段传给 `--host-key`。
3. `python docs/script/durability/verify.py process --host <vm> --host-key <verified-key> --rounds 3`。
4. 在明确允许整台虚拟机中断后，以 `guest-reset` 替代 `process`，并加 `--expected-machine-id <verified-machine-id>`。该模式会立即重启整台客户机，不能与业务或基准同时运行。
5. 故障测试全部结束后，在虚拟机运行 `sh docs/script/durability/benchmark.sh`。脚本输出 CSV；故障脚本输出观测结果并保存 JSON，不以“发现丢数”作为程序异常退出。
6. 测试结束停止测试容器；命名卷和结果保留用于检查。正式服务应恢复为四个 healthy、Explorer running，`/health` 与 Explorer HTTP 200。
