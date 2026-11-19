# 拆分部署：EMQX 单独一台主机

把 Conduit 拆到两台主机：EMQX 一台，其余全部服务一台。用于把设备接入层的 CPU 与网络开销从应用主机上拆走——
2026-10-30 在 8 核压测 VM 上实测，EMQX 与内核网络处理占忙碌 CPU 的约 70%（`docs/test-reports/2026-10-30-cpu-breakdown-12k.md`）。

## 两台主机各跑什么

| 主机 | 目录 | Compose 项目 / 容器 | 服务 |
|---|---|---|---|
| EMQX 主机 | `deploy/split/emqx` | 项目 `conduit-emqx`，容器 `conduit-emqx` | EMQX；可选 node-exporter（`--profile metrics`） |
| 应用主机 | `deploy/split/app` | 项目 `conduit` | PostgreSQL、Redis、InfluxDB、InfluxDB Explorer、Mailpit、conduit-server、两个同时工作的 transport（身份池多活）、conduit-web、模拟器；可选观测栈 Prometheus / Grafana / node-exporter / redis-exporter（`--profile metrics`） |

两边都不复制服务定义：`emqx/` 以 `extends` 引用 `deploy/dev` 的 emqx，`app/` 以 `extends` 逐个引用 `deploy/standalone` 的服务，
只写拆分后不同的部分（连远端 EMQX、去掉对本机 emqx 的依赖）。因此**两台主机上都要有完整的 `deploy/` 目录**
（`deploy/package.sh` 打的包或仓库检出均可；应用主机用预编译产物时还需 `deploy/standalone/artifacts/`）。

## 网络要求

| 方向 | 用途 | 端口 |
|---|---|---|
| 设备 → EMQX 主机 | MQTT 接入 | 1883 |
| 应用主机 → EMQX 主机 | transport 连内部监听器；会话监控与 Prometheus 访问 REST API | 1884、18083 |
| **EMQX 主机 → 应用主机** | **每次设备连接的鉴权回调**（同步，不通则设备全部连不上） | server 的 API 端口（默认 8091） |

1884 是只供 transport 使用的内部监听器：server 会拒绝设备连它，但仍建议防火墙只对应用主机放行。

两台都是同一 VMware NAT 网段内的虚拟机时互相直连即可。若应用主机在 NAT 后、EMQX 主机在 NAT 外，最后一行不通：
在 VMware「虚拟网络编辑器 → NAT 设置 → 端口转发」把宿主机端口转发到应用主机的 8091（并在宿主机防火墙放行），
或把应用主机改为桥接网卡。

## 部署步骤

两边 `.env` 中以下口令必须相同：`CONDUIT_INTERNAL_TOKEN`、`CONDUIT_EMQX_API_KEY`、`CONDUIT_EMQX_API_SECRET`、`EMQX_DASHBOARD_PASSWORD`。

### 1. EMQX 主机

```bash
cd deploy/split/emqx
cp .env.example .env
# 必改：CONDUIT_AUTH_URL=http://<应用主机地址>:8091；四个口令与应用主机一致
docker compose up -d
docker exec conduit-emqx emqx ctl status            # 运行中
docker exec conduit-emqx emqx ctl conf show mqtt    # 回读：max_inflight = 1024、max_mqueue_len = 50000、shared_subscription_strategy = round_robin
docker exec conduit-emqx emqx ctl conf show zones   # 回读：transport zone 的 max_mqueue_len = 15000、max_mailbox_size = 200000
```

配置模板与 ACL 从 `deploy/dev/emqx/` 只读挂载，需对容器内 emqx 用户（uid 1000）可读（`chmod -R a+r deploy`）。
EMQX 主机只开放 Docker 远程 API、没有登录方式时，可在应用主机上执行 `DOCKER_HOST=tcp://<EMQX 主机>:2375 docker compose up -d`：
compose 在本机解析文件，但挂载路径指向 EMQX 主机的磁盘，所以两台主机上的 `deploy/` 必须放在**同一个绝对路径**。

### 2. 应用主机

```bash
cd deploy/split/app
cp .env.example .env
# 必改：EMQX_REMOTE_HOST=<EMQX 主机地址>，以及全部「必须改」的口令（四个与 EMQX 主机一致）
docker compose up -d --remove-orphans
docker compose --profile metrics up -d          # 可选：观测栈
```

`.env.example` 由 `deploy/standalone/.env.example` 改出，只有标注「拆分部署」的几处不同；修改独立部署包的 `.env.example` 时同步这里。

**从单机独立部署切换过来**：项目名与数据卷相同，数据沿用。把原 `deploy/standalone/.env` 复制为 `deploy/split/app/.env`，
补上 `EMQX_REMOTE_HOST`、确认 `BIND_ADDR=0.0.0.0`，再执行上面的 `up -d --remove-orphans`——本文件不定义 emqx，
本机原有的 EMQX 容器作为孤儿被移除。以后在应用主机上一律在 `deploy/split/app` 目录执行 `docker compose`；
回到 `deploy/standalone` 目录执行会按单机方式在本机重新起一个 EMQX。

### 3. 验证

```bash
# 应用主机：两个 transport 都已连上远端 EMQX（allocation.connected = true），held 之和为 poolSize（默认 64）、各约一半
for c in conduit-transport-1 conduit-transport-2; do
  docker exec $c bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3' | tail -1
done
# EMQX 主机：transport 的 64 个共享订阅（都在 tcp:internal 上）与模拟器设备都在
docker exec conduit-emqx emqx ctl broker stats | grep -E "^connections.count|subscriptions.shared.count"
```

### 4. 从 1883 升级到内部监听器（2026-11-16）

EMQX 的会话参数在创建时固定，transport 改连 1884 后会接管原来在 1883 上创建的会话，队列上限仍是旧 zone 的值。升级后在空闲时把旧会话丢弃一次，transport 会立即按新 zone 重建：

```bash
for i in '' -1 -2 -3; do docker exec conduit-emqx emqx ctl clients kick conduit-transport$i; done
```

丢弃时会话里尚未投递的消息会丢失，所以要在设备流量低的时候做；第 4～7 条连接是新 clientId，不受影响。

### 5. 从单活多备升级到身份池多活（2026-11-19）

新版 transport 的两个实例同时工作，经 Redis 租约分摊 64 个 MQTT 身份（原来只有主实例连 8 条）。旧版靠选主锁持有全部身份，
**新旧版本不能混跑**，否则会互相踢掉对方的连接。设计见
[`2026-11-17-conduit-transport-multi-active-design.md`](../../docs/superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md)。
停机空档内的消息排在 EMQX 会话队列中，选设备流量低的时候做。

1. 应用主机：停掉全部旧 transport。

   ```bash
   cd deploy/split/app
   docker compose stop conduit-transport conduit-transport-2
   ```

2. EMQX 主机：`.env` 按新的 `.env.example` 设 `EMQX_TRANSPORT_MAX_MQUEUE_LEN=15000`、`EMQX_SHARED_SUBSCRIPTION_STRATEGY=round_robin`，重建 EMQX 并回读。
   重建会断开设备，内存会话不跨重建保留。

   ```bash
   cd deploy/split/emqx
   docker compose up -d --force-recreate emqx
   docker exec conduit-emqx emqx ctl conf show mqtt  | grep shared_subscription_strategy   # round_robin
   docker exec conduit-emqx emqx ctl conf show zones | grep -A3 max_mqueue_len             # transport zone 为 15000
   ```

3. EMQX 主机：丢弃 8 个旧会话（旧会话的队列上限在创建时固定；第 2 步若已重建 EMQX，会话已不存在，命令报 not found 可忽略）。

   ```bash
   for i in '' -1 -2 -3 -4 -5 -6 -7; do docker exec conduit-emqx emqx ctl clients kick conduit-transport$i; done
   ```

4. 应用主机：换上新版产物（`deploy/package.sh` 打的包或新检出），`.env` 设 `TRANSPORT_POOL_SIZE=64`，删除旧的 `TRANSPORT_MQTT_CONNECTIONS`；Redis 中若残留旧键 `conduit:transport:leader` 可删除（新版不再使用）。启动两个 transport：

   ```bash
   docker compose up -d --build conduit-transport conduit-transport-2
   ```

5. 回读：两个实例的 `held` 之和为 64、各 32，`members` 为 2，`poolSize` 为 64；EMQX 共享订阅数为 64。

   ```bash
   for c in conduit-transport-1 conduit-transport-2; do
     docker exec $c bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3' | tail -1
   done
   docker exec conduit-redis redis-cli GET conduit:transport:pool-size                        # 64
   docker exec conduit-emqx emqx ctl broker stats | grep subscriptions.shared.count            # EMQX 主机执行
   ```

### 6. 调大身份池 K

实例数超过 K × 0.5 时 transport 记 WARN，提示调大 K。K 存在 Redis，只能调大，不需要重启：

1. 先按「新 K × `EMQX_TRANSPORT_MAX_MQUEUE_LEN` × 每条 1.9～5.4 KB」估算全部积压时 EMQX 占用的内存，核对 EMQX 主机内存；需要时同步调小单连接队列，保持总量不变。
   每条的占用取实测值：1.9 KB 是 2026-11-12 压测中 30 秒采样的最大值（`docs/test-reports/2026-11-12-stress-to-failure.md`）；5.4 KB 是 2026-10-28 队列填满 10 万条、会话进程堆达 513 MB 被强杀时的折合值，当时载荷仅约 60 字节（`docs/test-reports/2026-10-28-capacity-and-bottleneck.md`）。载荷更大时占用更多。默认 64 × 1.5 万条约需 1.7～4.9 GB。
2. 写入新值。各实例在下一轮检查（≤ 2 秒）读到并抢占新身份，新身份按 zone `transport` 的参数建会话、自动加入共享组：

   ```bash
   docker exec conduit-redis redis-cli SET conduit:transport:pool-size 128
   ```

3. 同步把应用主机 `.env` 的 `TRANSPORT_POOL_SIZE` 改成新值（只在 Redis 中没有该键时用作初始值）。回读各实例 `poolSize` 与 `held`。

缩小 K 暂不支持（transport 读到更小的值只记 WARN）：多出的会话要先排空才能移除。
`TRANSPORT_POOL_SIZE=1` 是回退开关（单连接、普通订阅），从 1 改回多身份前要先 `redis-cli DEL conduit:transport:pool-size`。

## 可选：Linux 宿主机网络对照

`emqx/docker-compose.host.yml` 提供去掉 Docker 桥接与 DNAT 的可选模式，目标是降低收包路径开销。
默认仍是桥接。2026-11-02 的6000台/每秒一条各三轮对照中，bridge三轮通过、host仅一轮通过，
**没有取得稳定性能提升证据，暂不推荐启用**。不能据此断定host普遍较慢；bridge另有6500台首轮通过，
但未重复、不称最大容量，详见[最终报告](../../docs/test-reports/2026-11-02-emqx-network-ab-results.md)。
仅用于 Linux Docker Engine，要求 Docker Compose ≥ 2.24.4（使用 `!reset` 清除继承配置）。

在 EMQX 主机的实际部署目录中执行，先确认没有压测或设备上报，并排空消息：

```bash
docker compose -f docker-compose.yml -f docker-compose.host.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps emqx
docker inspect conduit-emqx --format '{{.HostConfig.NetworkMode}}'
docker exec conduit-emqx emqx ctl status
docker exec conduit-emqx emqx ctl listeners
```

`NetworkMode` 应为 `host`。MQTT、transport 内部监听器（2026-11-16 起）与 Dashboard 显式监听 `.env` 的
`BIND_ADDR`、`MQTT_PORT`、`MQTT_INTERNAL_PORT`、`EMQX_DASHBOARD_PORT`；原先没有发布的 SSL/WS/WSS 默认监听器关闭。数据卷、节点名、鉴权及共享订阅不变。
宿主机网络没有端口映射保护，**其他已配置监听器以及 Erlang 分布式/RPC 端口也需核对宿主机防火墙**；
不得仅凭 `ports` 已清空就认定暴露面相同。切换前确认端口占用；启用的自定义监听器需单独审查。

该模式清除容器级 `sysctls`，不会自动修改宿主机全局参数。记录宿主机
`net.core.somaxconn`、`net.ipv4.tcp_max_syn_backlog`，连接建立压力须与稳态消息处理分别分析。
EMQX 重建会断开设备，内存会话不会跨重建保留；不能在发压中直接切换，否则会污染守恒判据。

回到桥接（同样先停止发压并排空）：

```bash
docker compose -f docker-compose.yml up -d --no-deps emqx
docker inspect conduit-emqx --format '{{.HostConfig.NetworkMode}}'
```

后续启停必须始终使用选定模式对应的文件组合，否则 Compose 会把网络模式切回去。
配置回归检查：`python docs/script/loadtest/verify_host_network.py`，在仓库根运行；
依赖已有实验室 SSH 配置，仅向应用 VM 上传模板及虚构凭据做解析，不启动容器、不读取运行中的 `.env`。
实验顺序、验收与演示复现见
[`2026-11-02-emqx-host-network-design.md`](../../docs/superpowers/specs/2026-11-02-emqx-host-network-design.md)。

## 容量参数

EMQX 主机的连接队列与报文上限取 EMQX 5.10.5 接受的最大值（2026-10-30 所有者要求）；会话三项（窗口、队列、强杀）
2026-11-12 改回有上限的取值，见下方「为什么会话三项不取最大值」。每项都用临时 EMQX 容器验证过能启动且生效。

| 参数（EMQX 主机 `.env`） | 本包取值 | EMQX 默认 | 其他部署包 | 说明 |
|---|---|---|---|---|
| `EMQX_MAX_INFLIGHT` | 1024 | 32 | 512 | EMQX 推给每条连接、尚未确认的消息上限；须 ≥ 应用主机批大小，且远小于信箱上限 |
| `EMQX_MAX_MQUEUE_LEN` | 50000 | 1000 | 50000 | 每个会话的内存队列，满了丢弃并计入 `mqueue_dropped`。10 万条在单连接堆上限内放不下，过载时连接被强杀（2026-11-12 实测） |
| `EMQX_FORCE_SHUTDOWN_ENABLE` / `EMQX_MAX_MAILBOX_SIZE` / `EMQX_CONNECTION_MAX_HEAP_SIZE` | true / 10000 / 256MB | true / 1000 / 32MB | true / 10000 / 256MB | 单个连接信箱或堆超限时只强杀该连接 |
| `EMQX_TCP_BACKLOG` / `EMQX_SOMAXCONN` | 65535 | 1024 / 4096 | 默认 | 大批设备同时重连时的连接队列 |
| `EMQX_MAX_PACKET_SIZE` | 256MB（上限） | 1MB | 默认 | 单个 MQTT 报文上限 |
| 文件描述符 | 1048576 | — | 1048576 | 在 dev 文件中设定，每条 MQTT 连接占一个 |

应用主机：`TRANSPORT_BUS_BATCH_SIZE=512`（2026-10-29 验证值）、`TRANSPORT_POOL_SIZE=64`（身份池，两个实例共享）。
批大小不得超过 EMQX 的在途窗口；身份池对应的总缓冲为 K × `EMQX_TRANSPORT_MAX_MQUEUE_LEN`（64 × 1.5 万 ≈ 96 万条），调大 K 见上文第 6 步。

**为什么会话三项不取最大值**：2026-10-30 起曾取窗口 65535、队列不限、关闭强杀。2026-11-12 压测到崩溃时，
每秒 25,000～30,000 条下积压一路涨到整台主机内存耗尽，EMQX 被 Linux OOM killer 杀掉两次，内存中的积压全部丢失
（约 932 万条已确认消息），所有设备一起断开（`docs/test-reports/2026-11-12-stress-to-failure.md`）。
队列先取 10 万复测：EMQX 不再被杀，但 transport 订阅连接的堆先于队列满就超限、被反复强杀，15,000 条/秒起即丢数据。
改为 5 万后，过载只表现为队列满后丢弃新消息，全程零强杀、零掉线，15,000 条/秒以内无损，丢弃数有 `mqueue_dropped` 计数可查。
2026-11-13 起，会话上限（队列 5 万、信箱 10000、开启强杀、堆 256MB）统一用于全部部署包，由 `deploy/dev/docker-compose.yml` 定义，本包只在 `.env` 中取同样的值（`docs/test-reports/2026-11-13-standalone-session-limits.md`）。

**告警**：transport 会话监控对每个会话的积压（默认 ≥ 5 万条，`CONDUIT_TRANSPORT_MONITOR_MQUEUE_WARN_LEN`）与
EMQX 主机内存（默认 ≥ 80%，`CONDUIT_TRANSPORT_MONITOR_NODE_MEMORY_WARN_RATIO`）记 WARN，并发布仪表
`conduit_transport_session_mqueue_len`、`conduit_transport_emqx_memory_used_ratio`。两者只告警、不改变健康检查。

## 观测

应用主机的 Prometheus 经 `EMQX_METRICS_TARGET`（由 `EMQX_REMOTE_HOST` 自动拼出）抓取远端 EMQX，看板的 EMQX 面板照常可用。
看板上的整机 CPU / 内存面板只反映应用主机；EMQX 主机的资源用 `docker stats conduit-emqx` 或其上的 node-exporter 另看
（未接入看板：整机面板不按主机区分，混入后会把两台机器的 CPU 合并计算）。

## 一台 EMQX 只对应一套 Conduit

EMQX 开源版没有 RabbitMQ vhost 那样的命名空间。几套 Conduit 直接共用一台 EMQX 会互相干扰：transport 的 clientId 都是
`conduit-transport-*`，会把彼此的会话挤掉；共享订阅组同名，消息会被分到别人的 transport 上；鉴权回调只能配一个地址。
