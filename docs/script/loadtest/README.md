# 压测脚本

对 Conduit IoT 做容量测试、压力测试与改造前后回归对比的脚本。

这些脚本在 2026-10-28/29 的压测中实际使用过，产出见
[`../../test-reports/2026-10-29-capacity-after-batching.md`](../../test-reports/2026-10-29-capacity-after-batching.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `loadgen.py` | 多进程 MQTT 发压器：建设备、刷新密钥、按速率发布、统计 PUBACK 时延 |
| `ladder.py` | 阶梯压测：逐档加设备、按守恒与稳定性判据自动判定；支持多台电脑一起发压 |
| `capacity_test.py` | 容量测试：逐级加压，找积压不增长的最大稳态速率 |
| `remote.py` | 压测机远程助手：SSH 执行、上传、查 Prometheus、数 InfluxDB 行数、数 EMQX 日志 |
| `config.example.json` | 配置模板 |
| `LOADGEN.md` | 发压器的设计说明（为什么是多进程） |

`config.json` 与 `devices.json` 含凭据，**已在 `.gitignore` 中，不得提交**。

## 准备

```bash
cd docs/script/loadtest
pip install paho-mqtt paramiko
cp config.example.json config.json     # 填入压测机地址、租户管理员账号、SSH 凭据
python loadgen.py provision --devices 600   # 建 600 台压测设备，密钥存入 devices.json
```

`provision --devices N` 把设备**补齐到 N 台**：产品已存在则复用，`devices.json` 中已有的设备保留，只并发新建差额
（`--concurrency`，默认 8），令牌过期自动重新登录，每 500 台落盘一次，中断后重跑会接着补。

## 海量低频设备（fleet 模式）

真实设备是每台隔几秒报一次（如 2 万台、每 10 秒一条），瓶颈在连接数而不是单连接速率，用 `--fleet`：

```bash
python loadgen.py run --devices 20000 --rate 0.1 --seconds 1800 --fleet
```

默认模式每条连接 `loop_start()` 一个线程，外加一对唤醒用的回环套接字（Windows 上占临时端口），几千台以上时发压机的
线程、内存与端口会先于服务端耗尽。fleet 模式每个进程（默认每 2,500 台一个）用单个事件循环 select 全部连接，
按 `--connect-rate`（每进程每秒，默认 200）匀速建连，`--connect-timeout`（默认 120 秒）内等齐 CONNACK。

**Windows 发压机的临时端口默认只有 16,384 个**（`netsh int ipv4 show dynamicport tcp`），超过约 1.6 万条连接会建连失败。
需以管理员身份扩大范围，压测后可改回默认：

```bash
netsh int ipv4 set dynamicport tcp start=10000 num=55535
netsh int ipv4 set dynamicport tcp start=49152 num=16384
```

## 每次压测前必须先刷新密钥

```bash
python loadgen.py refresh
```

**不跑这一步，测出来的可能是废数据。** 压测产品带物模型，旧版演示模拟器会把它当成演示产品
接管：`SIM_TENANTS=*` 使其扫描所有租户的物模型产品，并给每个产品的前
`SIM_MAX_DEVICES_PER_PRODUCT` 台设备重置密钥（压测机上是 100）。被重置的设备在
`devices.json` 里的密钥随即失效；反过来，`refresh` 也会作废模拟器手里的那 100 台，模拟器重连被拒。

2026-10-30 起模拟器默认 `SIM_EXCLUDE_PRODUCTS=压测产品`，不再碰压测产品，两边不再互相作废。
升级后先 `refresh` 一次即可；若 `.env` 把它配成空或改了压测产品名，冲突会回来。
看板「设备鉴权」图的「拒绝·wrong_secret」不为 0 即是密钥对不上。

失效的方式极其隐蔽——paho 的 `connect()` 只发起 TCP，CONNACK 在网络线程里才到，
**鉴权被拒不会抛异常**。脚本照样报「连接数 600」「客户端错误 0」，而那 100 台的
publish 全部堆进本地队列、一条也发不出去，断开时静默丢弃。

2026-10-29 为此付出过代价：名义目标 10,000/秒实际只喂进 8,333，EMQX 恰好收到 8,334，
于是被当成「约 8,300 条/秒的入口天花板」写进了测试报告与架构决定（见该日报告的更正）。

**识别特征：接入量永远恰好是名义目标的某个固定比例**（当时是 5/6）。真正的容量上限
是一个绝对值，不会随你给多少而按比例缩放。看到固定比值，先查负载端。

现在 `run` 会等 CONNACK 到齐，只要有设备未通过鉴权就直接报错退出（退出码 2），
不会再把结果算出来。

## 阶梯压测（推荐）

```bash
python ladder.py run --plan 2000,5000,8000,10000,12000,15000,18000,20000 --rate 1 --hold 300
```

另一种用法是**设备数固定、逐档提速**，专测消息吞吐（接入 QPS / 入库 TPS），计划见
[`2026-10-30-qps-tps-1k-devices-test-plan.md`](../../superpowers/plans/2026-10-30-qps-tps-1k-devices-test-plan.md)：

```bash
python ladder.py run --devices 1000 --rate-plan 2,5,8,10,12,15,18,20 --hold 300
```

上面第一条命令是每台设备每秒 1 条，逐档加设备；每档全部设备连上后稳态保持 `--hold` 秒，停止后等 30 秒排空，再自动判定，
首个不合格的档位即停。结果（每档一个 JSON）与 `summary.md` 写在 `results/<开始时间>/`（不入库，见 `.gitignore`）。

**判据**（任一不满足即不合格）：

1. 全部设备通过鉴权。首次被拒的设备会像真实设备一样 2 秒后重连一次，重连后仍被拒才算；首次被拒数照实列出。
2. 守恒：客户端收到 PUBACK 的条数 = InfluxDB 中压测产品在本档时间窗的**实际行数**，EMQX 丢弃与死信均为 0。
   压测产品只有发压器在报（模拟器默认排除它），所以行数就是真值；不用 Prometheus 中两个计数器相减——两端抓取时刻不同，
   且「入库」含模拟器流量，实测会差几百条。
3. 运行中没有设备掉线（按设备去重）。
4. PUBACK 时延 p99 ≤ 1 秒（`--p99-limit`）。时延是在全部已确认消息中等概率抽样的，不是只看开头。
5. 稳态结束时总线积压（lag + 待确认）与 transport 会话队列都不超过 5 秒的流量——超过说明入库跟不上、在越堆越多。
6. EMQX 日志中没有连接被强杀 / 队列满 / emfile，也没有过载告警（`alarm_is_activated`，如 `runq_overload`）。
7. 发压机 CPU 平均不超过 85%（Windows 上每档用自带 `typeperf` 自动采样）。发压机与被测 VM 同在一台物理机时，超过即说明测到的是
   物理机的上限，本档判为「结果不可信」并停止。

EMQX 与应用分机部署（`deploy/split`）时，在 `config.json` 中加 `"emqx_docker": "tcp://<EMQX 主机>:2375"`，
`ssh.env_file` 指向应用主机上部署包的 `.env`（读 InfluxDB token，就地使用、不回传）。

### 多台电脑一起发压

单台电脑发压时，发压程序与被测服务常抢同一台物理机的 CPU 和内存（本地 VM 压测实测宿主机可用内存降到 330 MB），
测到的会是发压机的上限。把负载分到几台电脑上：

1. 每台电脑：装 Python 3 与 `pip install paho-mqtt`；拷入本目录，以及 `config.json`、`devices.json`
   （含设备密钥，私下传递，不要提交或发到公开渠道）。`config.json` 的 `mqtt_host` 填这台电脑能连到的 EMQX 地址。
   EMQX 跑在某台电脑的 VMware NAT 网络里时，别的电脑连不进去：在那台电脑的 VMware「虚拟网络编辑器 → NAT 设置 → 端口转发」
   把宿主机 1883 转发到 EMQX 虚拟机的 1883，并在宿主机防火墙放行；或把 EMQX 虚拟机改为桥接网卡。
2. `devices.json` 至少要有 n × ceil(最大档位 / n) 台：`python loadgen.py provision --devices <台数>`（在任一台上建好再分发）。
3. 各电脑用**同一组** `--plan` / `--rate` / `--hold` / `--start-at`，各给不同的 `--shard`。第 k 台用 devices.json 中
   第 (k-1) × ceil(最大档位 / n) 台起的一段，互不重叠：

   ```bash
   python ladder.py run --plan 2000,5000,10000 --rate 1 --hold 300 --start-at 15:00 --shard 1/3
   python ladder.py run --plan 2000,5000,10000 --rate 1 --hold 300 --start-at 15:00 --shard 2/3   # 第二台
   python ladder.py run --plan 2000,5000,10000 --rate 1 --hold 300 --start-at 15:00 --shard 3/3   # 第三台
   ```

   各机按约定时刻同步换档（第 k 档在开始时刻 + k ×（建连预留 120 + hold + 间隔 60）秒开始），所以各电脑时钟要准
   （Windows 默认的网络对时即可）。多机模式只发压、不判定。
4. 结束后把各电脑的 `results/<时间>/` 目录拷到一台能 SSH 到应用主机的电脑上，合并判定：

   ```bash
   python ladder.py check results-a results-b results-c
   ```

## 跑一次容量测试

```bash
python capacity_test.py --levels 3000 4000 5000 6000 --devices 600 --seconds 180
```

逐级加压，每级 180 秒，输出每级的接入 QPS、入库 TPS、两个队列深度，最后给出
「可持续稳态容量」。默认会先停掉演示模拟器以取干净基线，结束后自动恢复
（不想停就加 `--keep-simulator`）。

## 只发压、不做判定

```bash
python loadgen.py run --devices 600 --rate 10 --seconds 180
python loadgen.py run --devices 600 --rate 16.7 --seconds 120 --workers 28
```

`--rate` 是**每台设备每秒**的条数，总目标 = 设备数 × rate。
`--workers` 默认取 CPU 核数。

## 读数的口径（最重要的一节）

### 速率证明不了不丢，计数才行

**合格的判据是计数守恒，不是速率峰值。** 过载时 EMQX 先 PUBACK 再丢弃，入库 TPS
看着照样不低，但消息已经少了一截。一次合格的容量测试要比对五个独立口径，全部相等：

| 口径 | 来源 |
|---|---|
| 客户端 publish 次数 | loadgen 的「实际发送」 |
| 客户端 PUBACK 数 | loadgen 的「已确认」 |
| EMQX 接收数 | `emqx_messages_received` 的**计数差**（不是 rate） |
| 流水线入库数 | `conduit_pipeline_processed_total{type="property"}` 的计数差 |
| **InfluxDB 实际行数** | 直接 SQL `count(1)`，不经过应用的内存计数器，最有分量 |

同时要求：`emqx_messages_dropped` 增量为 0、死信增量为 0、EMQX 失效日志
（`mailbox_overflow` / `maximum heap size` / `mqueue_is_full`）为 0。

截取失效日志必须按**本档起始时刻**锚定（`docker logs --since <VM 的 RFC3339 时刻>`），
用 `--since 12m` 会把上一档过载的日志数进来，把合格的档位误判成不合格。

### PUBACK 的含义

**客户端只能证明「平台已持久接收」（PUBACK），不能证明已入库。**

过载时 EMQX 会先向设备 PUBACK，再在订阅端队列满时丢弃——不携带消息 `id`
的设备会拿到确认而数据并未进入平台，且无从得知（2026-10-28 实测一轮丢弃 51,671 条）。

| 要回答的问题 | 看哪个数 |
|---|---|
| 接入 QPS | 服务端 `emqx_messages_received`，或客户端的 PUBACK 速率 |
| **入库 TPS（对外承诺只能用这个）** | 服务端 `conduit_pipeline_processed_total` |
| 是否超容量 | `conduit_transport_session_mqueue_len` 与消费组 `lag` 是否**持续单调上涨** |
| 数据多久能查到 | `conduit_pipeline_ingest_latency_seconds_bucket` 的分位数 |

## 三类测试（`CLAUDE.md` 的约定）

压测报告必须写明属于哪一类：

| 类型 | 目的 | 速率 |
|---|---|---|
| **容量测试** | 找积压不增长的最大稳态速率 | 逐级上调到队列持续增长，回退一档 |
| **压力测试** | 刻画失效模式与恢复能力 | 刻意过载，**必须同时核对失效日志** |
| **回归对比** | 改造前后比吞吐 | 固定在容量以下的同一速率 |

## 两条方法纪律（都是踩过坑换来的）

**一、速率由 Prometheus 的 `rate()` 计算，不要在外面对计数器做差分。**
抓取间隔 15 秒加上 SSH 往返耗时不固定，自行差分噪声极大——实测同一档位单点值
从 1,570 跳到 3,799，中位数被压到 2,400，据此一度误判结论有问题。

**二、判「容量以内」之前，先确认实际喂进去的量达到了目标。**
若负载端没打满，队列同样不涨，会把「客户端打不动」误判成「系统游刃有余」。
`capacity_test.py` 要求接入 QPS 中位达到目标的 85%，否则标注「结论不可用」。

还有一条操作纪律：**测量期间不要在压测机上做别的事**。曾因一边测一边推面板、
重启 Grafana，把中位数压到 397 而峰值是 3,624，整组数据作废。

## 已知限制

- 单机发压有上限；要再往上需要多台机器并发。
- `remote.py` 用口令登录 SSH，仅适用于实验室内的压测机；生产环境应改用密钥。
