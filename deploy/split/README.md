# 拆分部署：EMQX 单独一台主机

EMQX 部署在一台主机，其余服务（PostgreSQL、Redis、InfluxDB、conduit-server、两个 transport、Web、模拟器、观测栈）部署在另一台。
用于把设备接入层的 CPU 与网络开销从应用主机上拆走：2026-10-30 在 8 核压测 VM 上实测，EMQX 与内核网络处理占忙碌 CPU 的约 70%
（见 `docs/test-reports/2026-10-30-cpu-breakdown-12k.md`）。

两台主机都用同一套服务定义，不复制：EMQX 主机以 `extends` 引用 `../dev/docker-compose.yml` 的 emqx；
应用主机在独立部署包上叠加 `app.yml`，停用本机 emqx、把 transport / 模拟器 / Prometheus 指向远端。

## 网络要求

| 方向 | 用途 | 端口 |
|---|---|---|
| 设备 → EMQX 主机 | MQTT 接入 | 1883 |
| 应用主机 → EMQX 主机 | transport 连 EMQX、会话监控、Prometheus 抓取 | 1883、18083 |
| **EMQX 主机 → 应用主机** | **每次设备连接的鉴权回调**（同步，不通则设备全部连不上） | server 的 API 端口（默认 8091） |

应用主机在 VMware NAT 网络后面时，NAT 里的虚拟机能主动连出去，但外部主机连不进来，上表最后一行不通。
两种解法，任选其一：

- 在 VMware「虚拟网络编辑器 → NAT 设置 → 端口转发」把宿主机的 8091 转发到虚拟机的 8091，并在宿主机防火墙放行入站 8091；
  EMQX 主机的 `CONDUIT_AUTH_URL` 填 `http://<宿主机局域网地址>:8091`。
- 把虚拟机网卡改为桥接，让它拿到与 EMQX 主机互通的地址。

## EMQX 主机

把仓库的整个 `deploy/` 目录放到 EMQX 主机上（`extends` 按 dev 文件所在目录解析配置模板与 ACL 的挂载路径）：

```bash
cd deploy/split/emqx
cp .env.example .env    # 填 CONDUIT_AUTH_URL；口令与应用主机 .env 中的同名值一致
docker compose up -d
docker compose --profile metrics up -d node-exporter   # 可选：本机 CPU / 内存
```

建议的宿主机内核参数（连接数上万时；以 root 写入 `/etc/sysctl.d/` 后 `sysctl --system`）：

```
fs.file-max = 2097152
net.core.somaxconn = 32768
net.ipv4.tcp_max_syn_backlog = 16384
net.core.netdev_max_backlog = 16384
```

容器内的文件描述符上限已在 dev 文件中设为 1048576（每条 MQTT 连接占一个；Docker 默认 1024 时约 1,000 台设备 EMQX 即崩溃重启）。

## 应用主机

在 `deploy/standalone/.env` 中补充与修改：

```
EMQX_REMOTE_HOST=<EMQX 主机地址>
BIND_ADDR=0.0.0.0              # 让鉴权回调能访问 server 的 API 端口
TRANSPORT_BUS_BATCH_SIZE=512   # 与 EMQX 主机的 EMQX_MAX_INFLIGHT=1024 配套，见下表
```

然后在 `deploy/standalone` 目录：

```bash
docker compose -f docker-compose.yml -f ../split/app.yml up -d
docker compose -f docker-compose.yml -f ../split/app.yml --profile metrics up -d   # 含观测栈
```

以后每条 `docker compose` 命令都要带上这两个 `-f`，否则会按单机方式在本机再起一个 EMQX。

## 容量参数

`emqx/.env.example` 给的是 2026-10-29 在 8 核压测 VM 上按守恒判据验证过的组合（11,500 条/秒无损），与其他部署包的默认值不同：

| 参数 | 位置 | 本包取值 | 其他包默认 | 说明 |
|---|---|---|---|---|
| `EMQX_MAX_INFLIGHT` | EMQX 主机 | 1024 | 512 | EMQX 推给 transport 每条连接、尚未确认的消息上限 |
| `EMQX_MAX_MAILBOX_SIZE` | EMQX 主机 | 10000 | 1000（EMQX 默认） | 在途消息堆在连接进程信箱里，必须显著大于在途窗口，否则 transport 连接被强杀 |
| `EMQX_MAX_MQUEUE_LEN` | EMQX 主机 | 100000 | 100000 | 覆盖 transport 主备切换窗口（约 17 秒）；驻留在连接进程堆内 |
| `EMQX_CONNECTION_MAX_HEAP_SIZE` | EMQX 主机 | 256MB | 256MB | 兜底保护，不为塞下更大的队列上调 |
| `TRANSPORT_BUS_BATCH_SIZE` | 应用主机 | 512 | 64 | 必须 ≤ `EMQX_MAX_INFLIGHT` |
| `TRANSPORT_MQTT_CONNECTIONS` | 应用主机 | 4 | 4 | transport 共享订阅的连接数 |

约束：`TRANSPORT_BUS_BATCH_SIZE ≤ EMQX_MAX_INFLIGHT ≪ EMQX_MAX_MAILBOX_SIZE`。

## 多套环境共用一台 EMQX

EMQX 开源版没有 RabbitMQ vhost 那样的命名空间。几个人各自的 Conduit 直接连同一个 EMQX 会互相干扰：transport 的 clientId
都是 `conduit-transport-*`，会把彼此的会话挤掉；共享订阅组同名，消息会被分到别人的 transport 上；鉴权回调只能配一个地址。
要共用，需要按环境各开一个监听端口，并在该监听上设置独立的挂载点（mountpoint，自动给主题加前缀）与独立的鉴权回调，
相当于 vhost。本包目前只配了一个监听，即一台 EMQX 对应一套环境。
