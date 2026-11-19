# 交接：拆分部署压测——每秒 1 条设备数上限与后续方向

- 状态：**已完成**。后续进展：2026-11-16 RPS 对照无收益，每秒 1 条 8,000～10,000 台 RPS 开 / 关均通过，6,000 台上限不再复现（[`2026-11-16-connect-storm-outage-replay.md`](../test-reports/2026-11-16-connect-storm-outage-replay.md)）；本交接的成果 2026-11-19 合并入 main。
- 原状态：**本轮网络对照与6500台上探已完成**（2026-11-02约22:36）。最终报告：[`2026-11-02-emqx-network-ab-results.md`](../test-reports/2026-11-02-emqx-network-ab-results.md)。bridge的6000台三轮全通过、6500台单轮通过；host的6000台三轮仅一轮通过，不推荐切换，不称已找到最大容量。
- 当前Git：`codex/emqx-host-network`，HEAD已由其他文档工作推进至`811e441`，本轮改动未提交/推送，`.lithe/`未动。实际VM应用镜像未升级，默认bridge；独立复核EMQX/InfluxDB健康、InfluxDB重启0，其他8个容器和模拟器均运行，无遗留发压进程。
- 更高档位、6500台重复和长时间浸泡属后续工作，本轮未执行。原空闲自动补测的网络对照目标已完成，应结束该自动续办，避免重复发压。复现命令、证据目录、WAL备份及未验证项均见最终报告；不要重复隔离WAL。

## 过程记录（以下为历史状态，以上述完成状态及实时核验为准）
- 22:28最新状态：6000台各模式累计三次已完成，bridge三次通过（P99 195.4/15.6/15.5ms），host仅一次通过（P99 1387.6/2994.4/15.4ms），第二次host掉线378台并有4次告警。无稳定优化收益，恢复bridge与全部服务后，立即启动 `network_ab.py --bridge-only --repeats 1 --hold 300 --levels 6500` 探测更高档位，会话85196；当前不得重复发压。两轮重复原始结果位于 `results/network-ab-20261102-215950/`，重建前事件日志已保存，新增桥接两档与末次host均无告警。
- 用户再次明确要求继续，已即时启动两轮6000台对照：`network_ab.py --repeats 2 --start-mode host --hold 300 --levels 6000`，会话81054。启动前确认无其他发压、EMQX为bridge/healthy、InfluxDB healthy且重启0。顺序host/bridge/bridge/host，加上19时首轮后每模式共三次。当前为用户直接要求的即时执行，不使用只适用于后台续办的键鼠空闲启动条件；自动续办看到此进程时不得重复运行。最终恢复状态须重新核对。
- 20:35 自动续办：输入空闲约38分钟；CPU实测65.3秒平均5.75%、峰值14%，符合条件。补齐无依赖后台CPU监测（排除本驱动进程树与VM运行时，但保留其他Python进程；其他后台负载连续两次≥15%即中止发压并恢复）、重建前保存相关告警日志、分核/proc/stat快照，以及 `--start-mode` 支持反转试验顺序。归属计算专项断言通过，当前非测试背景CPU约8.93%。
- 20:39重复对照已中止：`network_ab.py --require-idle --repeats 2 --start-mode host --hold 300 --levels 6000` 首档host触发连续两次非测试CPU≥15%保护，结果在 `docs/script/loadtest/results/network-ab-20261102-203949/`，有aborted标记，没有完成summary。不能计作host失败或通过，重复验证仍未完成。21:05复查：发压进程已退出，EMQX恢复bridge/healthy，8个暂停容器与模拟器均恢复，InfluxDB healthy/RestartCount=0。继续等待低背景负载，不自动放宽阈值。
- 最新状态（20:04）：用户明确要求立即继续并自行启动VM，已完成首轮6000台bridge/host对照。bridge通过（P99 195.4ms，三计数1,799,979相等）；host不通过（P99 1,387.6ms、2次过载告警，三计数1,799,980相等），两者掉线/拒绝0。本轮无性能提升证据，默认保留bridge。结果及限制见同日host-network报告；至少三轮重复尚未完成，不新增容量承诺。
- 收尾最新核实：EMQX恢复bridge、healthy，8个其他容器和模拟器均恢复运行；应用InfluxDB healthy、RestartCount=0，无遗留发压进程。不要重复执行WAL隔离（备份目录保留）。若后续自动续办，只在电脑空闲时进行，先采稳态分核/网卡/背景负载，重建前留存告警日志，再做有必要的重复对照；不可把host直接当作成功优化部署。
- 19时续办：用户明确要求继续，VM未启动时自行启动。已用VMware后台启动应用VM、SSH恢复，应用网卡RX=4096在重启后验证保留。发现InfluxDB因4个零字节WAL文件导致写入时同名冲突并反复重启；确认单容器写此数据卷后停库，将4个空文件隔离到数据卷 `recovery/empty-wal-20261102/`，没有删除有效WAL/历史数据。恢复后InfluxDB healthy、RestartCount=0。第一轮因数据库故障无法排空基线而中止，原有容器自动恢复；重新发起bridge/host各一轮6000台/300秒对照，待结果。此期间其他自动续办不得同时发压，应先检查network_ab/ladder运行状态。
- 17:19 自动检查：输入空闲约21分钟；Windows整机CPU采样64.7秒，平均3.82%、峰值8%，满足条件。已修正network_ab.py的UTF-8子进程输出、远端覆盖文件SHA-256幂等检查，并加入 `--require-idle`（开始前60秒CPU复验，运行中输入恢复即终止专属发压进程树并执行恢复）。启动未完成的6000档前，脚本复验CPU平均4.8%、峰值6.2%，随后SSH建立连接超时；没有进入暂停容器或切换网络步骤，没有启动发压，也无遗留发压进程。当前无法远程确认VM服务状态，不能把之前的恢复状态当成最新事实。等待依赖可达且再次满足空闲条件后续办，不自行启动虚拟机。
- 补充只读核实：EMQX主机Docker远程接口可达，EMQX仍在bridge；其8个非Conduit容器均在运行。仅应用VM的SSH不可达，模拟器/应用服务的当前状态无法复核；没有恢复动作需要补执行。
- 本轮执行：`network_ab.py --repeats 1 --hold 300 --levels 5000,6000` 已启动，bridge/5000档生成原始JSON，发出与PUBACK均1,499,971、掉线0、p99=223.2ms；发压机CPU平均71.4%、峰值100%。随后 `ladder.py` 在打印✅时触发GBK `UnicodeEncodeError`，未写最终summary。不能把未完成验收记为通过。
- 收尾已核实：无遗留network_ab/ladder发压进程；EMQX仍为bridge（未切host），8个临时暂停容器均恢复运行，模拟器已恢复。覆盖文件已上传到EMQX实际部署目录；下次运行须先哈希核对已存在文件，当前脚本会拒绝覆盖。
- 后续先给子进程统一UTF-8输出环境，完善脚本幂等和空闲/用户恢复操作检测，再继续未完成档位。结果在本地忽略目录 `docs/script/loadtest/results/network-ab-20261102-134130/`；勿删除或当作完整A/B证据。
- 最新实现：分支 `codex/emqx-host-network`，基于 `b19cd27`，尚未提交。新增 `deploy/split/emqx/docker-compose.host.yml` 和 `docs/script/loadtest/verify_host_network.py`；4 组真实 Compose 合并检查通过。报告见 [`2026-11-02-emqx-host-network.md`](../test-reports/2026-11-02-emqx-host-network.md)。
- 实时核对：应用 VM 的 Docker Compose 为 v5.6.1；EMQX 仍运行于 bridge 网络，实际部署目录与本文第 6 节旧的「两台同路径」假设不符。接手者从 `docker inspect` 的 `com.docker.compose.project.working_dir` 重新取得目录，勿直接把仓库文件上传到假定位置。未切换服务、未发压、未停其他容器。
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
