# 2026-10-30 拆分部署（EMQX 与应用分机）部署验证

## 被测范围与代码状态

- `deploy/split/emqx`（EMQX 主机，项目 `iot-mqtt`，容器 `IOT-MQTT`）与 `deploy/split/app`（应用主机，项目 `conduit`，逐个 extends 独立部署包的服务）。
- Prometheus 模板的 EMQX 抓取地址改为变量 `EMQX_METRICS_TARGET`。

## 测试类型

部署验证（功能），不是性能测试。

## 环境

本地两台 VMware NAT 网段内的虚拟机：EMQX 主机（8 核 7.7 GB，同时运行另一套无关的开发环境容器，只开放 Docker 远程 API）、应用主机（压测 VM，8 核 7.6 GB）。

## 步骤与结果

1. **容量参数可接受性**：用临时 EMQX 5.10.5 容器逐项试取最大值。`max_inflight` 65535 可启动、65536 启动失败（`expected 1..65535`）；`max_mqueue_len = infinity`、`max_packet_size = 256MB`、关闭 `force_shutdown` 均生效；`max_mailbox_size` 2^31、`max_heap_size` 4GB 均被接受（无上限）。TCP backlog 的环境变量确认生效于 `listeners.tcp.default`（先前的 grep 误看了 ssl 监听）。
2. **EMQX 主机**：在应用主机上以 `DOCKER_HOST=tcp://<EMQX 主机>:2375 docker compose up -d` 部署，部署文件放在两台主机的同一绝对路径。回读：`max_inflight = 65535`、`max_mqueue_len = infinity`、`force_shutdown.enable = false`、`ulimit -n` 1048576、`somaxconn` 65535、鉴权地址指向应用主机。
3. **应用主机**：由原独立部署切换，`docker compose --profile metrics up -d --remove-orphans`。只重建 transport ×2、模拟器、Prometheus（其余服务 extends 后配置不变，未重建），原本机 EMQX 作为孤儿移除；数据卷沿用。
4. **端到端**：

| 检查 | 结果 |
|---|---|
| transport | 一主一备，主实例 4 条连接全部连上远端 EMQX，健康 UP |
| EMQX 主机 | 连接 178（模拟器 174 + transport 4），共享订阅 4 |
| Prometheus | `emqx` 目标为远端 EMQX、up；其余目标 up |
| 数据 | 近 1 分钟入库 17.4 条/秒 = EMQX 收 17.4 条/秒 |
| 鉴权 | 近 3 分钟放行 190、拒绝 0 |

## 未执行 / 局限

- 未做性能测试。看板的整机面板只反映应用主机，EMQX 主机的资源需另行采集。
- EMQX 主机上同时运行无关的开发环境容器，压测时会相互争用资源。
