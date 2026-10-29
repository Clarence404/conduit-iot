# 压测脚本

对 Conduit IoT 做容量测试、压力测试与改造前后回归对比的脚本。

这些脚本在 2026-10-28/29 的压测中实际使用过，产出见
[`../../test-reports/2026-10-29-capacity-after-batching.md`](../../test-reports/2026-10-29-capacity-after-batching.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `loadgen.py` | 多进程 MQTT 发压器：建设备、按速率发布、统计 PUBACK 时延 |
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

`provision` 是幂等的：产品已存在则复用，重复执行只会继续建设备。

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
