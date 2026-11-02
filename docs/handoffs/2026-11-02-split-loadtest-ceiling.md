# 交接：拆分部署压测——每秒 1 条设备数上限与后续方向

- 状态：**进行中，等待所有者拍板后继续**（2026-11-02 由上一位智能体交出）
- 最新报告：[`2026-11-01-ladder-1s-higher.md`](../test-reports/2026-11-01-ladder-1s-higher.md)
- 相关计划：[`2026-10-30-device-ladder-1s-test-plan.md`](../superpowers/plans/2026-10-30-device-ladder-1s-test-plan.md)（N 台 × 每秒 1 条）、
  [`2026-10-30-qps-tps-1k-devices-test-plan.md`](../superpowers/plans/2026-10-30-qps-tps-1k-devices-test-plan.md)（1,000 台逐档提速，**未执行**）
- 待办总表：`docs/backlog-v0.2.md` 的「2026-10-30 拆分部署压测遗留 / 待所有者拍板」第 4 条

## 1. 目标与授权范围

- 目标：在拆分部署（EMQX 一台 VM，应用一台 VM）下测出「每台设备每秒 1 条」能承载的最大设备数，并找出瓶颈。
- 已获授权：在两台本地压测 VM 上发压、读取指标与日志、临时停启 EMQX 主机上的 AcuCloud 容器（测完必须恢复）。
- **未获授权、须先问所有者**（见第 5 节）：
  1. 推送 `e557f41`；
  2. 改 EMQX 网络模式或开 RPS 等降低收包开销的改动；
  3. 换物理机或分机重测；
  4. 执行 1,000 台逐档提速计划。
- 不做：不改平台代码；不连接生产环境；不把 IP、口令、令牌写进仓库。

## 2. 当前状态（核实时间 2026-11-01 22:10 前后，接手后须重新核实）

| 项 | 状态 |
|---|---|
| 分支 | `main`。`origin/main` 在 `49c69d5`；本地多一个**未推送**提交 `e557f41`（报告、backlog、CLAUDE.md 决定记录），推送前须征得所有者同意 |
| 工作区 | `.lithe/` 是其他工具生成的目录，不要动、不要提交；`docs/script/loadtest/results-1s-*` 是本地原始结果，已加入 `.gitignore` |
| EMQX 主机（下文称「128」） | `deploy/split/emqx`，容器 `conduit-emqx`；采样容器 `conduit-probe128` 按所有者要求保留；8 个 AcuCloud 容器（influxdb_v1/v2/v3、minio、postgres、rabbitmq、redis、xxl-job）**已恢复运行**；网卡 ens33 接收环 4096，由 systemd 单元 `nic-ring-rx.service` 开机设置（已 enable，**未经重启验证**） |
| 应用主机（下文称「129」） | `deploy/split/app`，部署目录见 `config.json` 的 `ssh.env_file`；网卡 ens160 接收环 4096，已写入 NetworkManager 连接（`ethtool.ring-rx`，**未经重启验证**） |
| EMQX 配置 | 鉴权回调超时 10 秒（`de5a237`，已部署并回读确认）；其余为 `split/emqx/.env.example` 的最大容量参数 |

## 3. 已完成与证据

| 设备数（每台每秒 1 条） | 结论 | 掉线 | p99 | 128 CPU | 128 缺缓冲丢包 | 报告 |
|---|---|---|---|---|---|---|
| 5,000 | ✅ | 0 | 15 ms | 42% | 0 | [ring-timeout-retest](../test-reports/2026-10-30-ring-timeout-retest.md) |
| 6,000 | ❌ | 818 | 2.5 s | 78% | 约 14.3 万 | [ladder-1s-higher](../test-reports/2026-11-01-ladder-1s-higher.md) |
| 6,500 | ❌ | 548 | 4.0 s | 88% | 约 23.6 万 | 同上 |
| 8,000 | ❌ | 1,977 | 8.0 s | 93% | 未采 | 同上 |

- 三档不合格都**没有丢失已确认的消息**：InfluxDB 行数 ≥ 设备收到的确认数。不合格的原因是掉线与 p99。
- **瓶颈结论**：6,000 台时 128 的 CPU 有 60%～84% 花在内核 softirq，EMQX 用户态只占 10%～15%（约 1 核）。vmxnet3 收包集中在队列 0，其中断次数约为其他队列的 6 倍，对应的 `ksoftirqd` 达到 82%。软中断跟不上就会缺缓冲丢包，丢包引发重传和重连，负载随之陡增。
- 这是本地虚拟化网络的上限，**不能**当作 EMQX 或平台的容量。
- 发压机 CPU 约 50%，129 不超过 38%，两者都不是瓶颈。
- 同日已完成：两台 VM 的接收环 4096 持久化，`de5a237`、`49c69d5` 已推送。

## 4. 未执行与不确定项

- 1 万～2 万台档：未执行，因为阶梯在第一个不合格档即停。
- 两台 VM 的接收环在**重启后**是否仍为 4096：未验证。下次重启后执行 `ethtool -g ens33`（128）和 `ethtool -g ens160`（129），确认 `Current hardware settings` 的 RX 为 4096。
- 6,000 档的 CPU 构成只采了 3 次（每次 10 秒），用的是会话临时脚本，没有入库。临时脚本里计算丢包增量的部分有 bug（打出负数），已改为用前后两次 `ethtool -S` 的 `pkts rx OOB` 相减，报告中的丢包数取自后者。
- vmxnet3 收包集中在队列 0 的原因（VMware Workstation 的 RSS 是否生效）没有查明。

## 5. 待所有者拍板（接手后先问，不要自行执行）

1. 推送 `e557f41` 吗？
2. 要不要先降低 128 的收包开销再重测 6,000 / 8,000 台？
   - 上一位智能体的建议：先试 EMQX 改宿主机网络（`network_mode: host`，去掉 Docker 桥接与 DNAT），改动最小，只动测试环境；
   - 其次是给 ens33 开 RPS/RFS，把收包分散到 8 个核。
   - 另一个选项是直接换物理机，或另找一台电脑发压。
3. 要不要执行「1,000 台设备逐档提速」的 QPS / TPS 计划？

## 6. 接手后的操作方法

**凭据与地址**：全部在发压机本地的 `docs/script/loadtest/config.json` 中（已 gitignore），字段说明见 `config.example.json`。设备密钥在同目录的 `devices.json`。不要把这两个文件的内容贴进仓库或对话。InfluxDB token 由 `ladder.py check` 在应用主机上就地读取 `.env`，不回传。

**先核实现状**（在 `docs/script/loadtest` 下）：

```bash
git -C ../../.. log --oneline -3 && git -C ../../.. status --short
python -c "import remote;c=remote.connect();print(remote.run('docker ps --format \"{{.Names}} {{.Status}}\"',c=c)[1]);c.close()"
```

预期：129 上有 conduit-server、conduit-transport-1/2、conduit-redis、conduit-influxdb、conduit-postgres 等容器均为 Up。

**操作 128**：`remote.run` 在 129 上执行命令，经 `docker -H <config.json 的 emqx_docker>` 控制 128。

- 查看容器：`docker -H <emqx_docker> ps`
- 进入 128 宿主机命名空间执行命令（ethtool、/proc/stat、systemctl 等）：

```bash
docker -H <emqx_docker> run --rm --privileged --pid=host --net=host --entrypoint sh nginx:1.27.4-alpine -c "nsenter -t 1 -m -u -n -i -- sh -c '<命令>'"
```

**压测前置**（见计划第 3 节）：

1. 宿主机空闲 CPU 低于 15%。
2. 停 128 上的 8 个 AcuCloud 容器：`docker -H <emqx_docker> stop influxdb_v1 influxdb_v2 influxdb_v3 minio postgres rabbitmq redis xxl-job`。
3. 回读 EMQX 运行配置：`docker -H <emqx_docker> exec conduit-emqx emqx ctl conf show mqtt`。
4. 记录两台 VM 的 `pkts rx OOB` 基线：`ethtool -S ens33` / `ethtool -S ens160`。

**跑某一档**：

```bash
python ladder.py run --plan 6000 --rate 1 --hold 300 --out results-1s-6000
```

每档约 7 分钟，结果与判定写入 `--out` 目录的 `summary.md`。

**收尾（必须）**：

```bash
docker -H <emqx_docker> start influxdb_v1 influxdb_v2 influxdb_v3 minio postgres rabbitmq redis xxl-job
```

其余容器（包括 `conduit-probe128`）保持现状。在 `docs/test-reports/` 写新报告，并同步 backlog 第 4 条、CLAUDE.md 第 4 节的决定记录和本交接。

**回滚**：

- 若试了 EMQX 宿主机网络，恢复方法是用 `deploy/split/emqx` 的原编排在 128 上 `docker compose up -d`，部署目录在两台机上为同一绝对路径，见 `deploy/split/README.md`。
- 若开了 RPS，向 `/sys/class/net/ens33/queues/rx-*/rps_cpus` 写回 `0`。
