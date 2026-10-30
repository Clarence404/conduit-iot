# 压测脚本

对 Conduit IoT 做容量测试、压力测试与改造前后回归对比的脚本。

这些脚本在 2026-10-28/29 的压测中实际使用过，产出见
[`../../test-reports/2026-10-29-capacity-after-batching.md`](../../test-reports/2026-10-29-capacity-after-batching.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `loadgen.py` | 多进程 MQTT 发压器：建设备、刷新密钥、按速率发布、统计 PUBACK 时延 |
| `capacity_test.py` | 容量测试：逐级加压，找积压不增长的最大稳态速率 |
| `remote.py` | 压测机远程助手：SSH 执行、上传、查 Prometheus |
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
