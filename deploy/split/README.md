# 拆分部署：EMQX 单独一台主机

把 Conduit 拆到两台主机：EMQX 一台，其余全部服务一台。用于把设备接入层的 CPU 与网络开销从应用主机上拆走——
2026-10-30 在 8 核压测 VM 上实测，EMQX 与内核网络处理占忙碌 CPU 的约 70%（`docs/test-reports/2026-10-30-cpu-breakdown-12k.md`）。

## 两台主机各跑什么

| 主机 | 目录 | Compose 项目 / 容器 | 服务 |
|---|---|---|---|
| EMQX 主机 | `deploy/split/emqx` | 项目 `iot-mqtt`，容器 `IOT-MQTT` | EMQX；可选 node-exporter（`--profile metrics`） |
| 应用主机 | `deploy/split/app` | 项目 `conduit` | PostgreSQL、Redis、InfluxDB、InfluxDB Explorer、Mailpit、conduit-server、两个 transport（一主一备）、conduit-web、模拟器；可选观测栈 Prometheus / Grafana / node-exporter / redis-exporter（`--profile metrics`） |

两边都不复制服务定义：`emqx/` 以 `extends` 引用 `deploy/dev` 的 emqx，`app/` 以 `extends` 逐个引用 `deploy/standalone` 的服务，
只写拆分后不同的部分（连远端 EMQX、去掉对本机 emqx 的依赖）。因此**两台主机上都要有完整的 `deploy/` 目录**
（`deploy/package.sh` 打的包或仓库检出均可；应用主机用预编译产物时还需 `deploy/standalone/artifacts/`）。

## 网络要求

| 方向 | 用途 | 端口 |
|---|---|---|
| 设备 → EMQX 主机 | MQTT 接入 | 1883 |
| 应用主机 → EMQX 主机 | transport 连 EMQX、会话监控与 Prometheus 访问 REST API | 1883、18083 |
| **EMQX 主机 → 应用主机** | **每次设备连接的鉴权回调**（同步，不通则设备全部连不上） | server 的 API 端口（默认 8091） |

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
docker exec IOT-MQTT emqx ctl status            # 运行中
docker exec IOT-MQTT emqx ctl conf show mqtt    # 回读：max_inflight = 65535、max_mqueue_len = infinity
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
# 应用主机：transport 主实例已连上远端 EMQX（leader.connected = true）
docker exec conduit-conduit-transport-1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3' | tail -1
# EMQX 主机：transport 的 4 条共享订阅与模拟器设备都在
docker exec IOT-MQTT emqx ctl broker stats | grep -E "^connections.count|subscriptions.shared.count"
```

## 容量参数

EMQX 主机按所有者要求（2026-10-30）取 EMQX 5.10.5 接受的最大值，压测后再调整。每项都用临时 EMQX 容器验证过能启动且生效。

| 参数（EMQX 主机 `.env`） | 本包取值 | EMQX 默认 | 其他部署包 | 说明 |
|---|---|---|---|---|
| `EMQX_MAX_INFLIGHT` | 65535（上限，65536 启动失败） | 32 | 512 | EMQX 推给每条连接、尚未确认的消息上限 |
| `EMQX_MAX_MQUEUE_LEN` | infinity | 1000 | 100000 | 每个会话的内存队列 |
| `EMQX_FORCE_SHUTDOWN_ENABLE` | false | true | true | 不再按信箱 / 堆强杀单个连接；信箱与堆两项保留为重新开启时的取值（10000 / 256MB） |
| `EMQX_TCP_BACKLOG` / `EMQX_SOMAXCONN` | 65535 | 1024 / 4096 | 默认 | 大批设备同时重连时的连接队列 |
| `EMQX_MAX_PACKET_SIZE` | 256MB（上限） | 1MB | 默认 | 单个 MQTT 报文上限 |
| 文件描述符 | 1048576 | — | 1048576 | 在 dev 文件中设定，每条 MQTT 连接占一个 |

应用主机：`TRANSPORT_BUS_BATCH_SIZE=512`、`TRANSPORT_MQTT_CONNECTIONS=4`（2026-10-29 验证值）。transport 对这两项没有上限，
只要求批大小不超过 EMQX 的在途窗口。

**代价**：关掉强杀、队列不限之后，过载不再表现为「单条连接被杀、丢一部分消息」，而是积压持续增长直到 EMQX 整个节点内存耗尽、
所有设备一起断开。压测时盯 EMQX 主机的内存（`docker stats IOT-MQTT`）。transport 会话监控的「队列水位」告警也不会触发：
队列不限时 EMQX REST 接口报告的 `mqueue_max` 为 0（实测），监控在上限为 0 时跳过该项。

## 观测

应用主机的 Prometheus 经 `EMQX_METRICS_TARGET`（由 `EMQX_REMOTE_HOST` 自动拼出）抓取远端 EMQX，看板的 EMQX 面板照常可用。
看板上的整机 CPU / 内存面板只反映应用主机；EMQX 主机的资源用 `docker stats IOT-MQTT` 或其上的 node-exporter 另看
（未接入看板：整机面板不按主机区分，混入后会把两台机器的 CPU 合并计算）。

## 一台 EMQX 只对应一套 Conduit

EMQX 开源版没有 RabbitMQ vhost 那样的命名空间。几套 Conduit 直接共用一台 EMQX 会互相干扰：transport 的 clientId 都是
`conduit-transport-*`，会把彼此的会话挤掉；共享订阅组同名，消息会被分到别人的 transport 上；鉴权回调只能配一个地址。
