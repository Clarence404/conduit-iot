# 压测负载生成器

对 Conduit IoT 发起 MQTT 上行压测，用于容量测试、压力测试与改造前后的回归对比。

## 为什么是多进程

上一版「每设备一个线程」在 600 线程时受 GIL 与调度开销限制，约 3,500 条/秒即出现颠簸，
而且**目标速率调得越高实际打出越少**（实测目标 8,000 打出 2,857、目标 10,000 只打出 1,679），
导致系统的真实容量天花板测不出来——测到的是负载端的极限。
多进程让每个进程独立持有一批设备与各自的 paho 事件循环，绕开 GIL。

## 用法

```bash
cp config.example.json config.json   # 填入压测机地址与租户管理员账号
pip install paho-mqtt

python loadgen.py provision --devices 600              # 建设备，密钥存入 devices.json
python loadgen.py run --devices 600 --rate 10 --seconds 180
python loadgen.py run --devices 600 --rate 10 --seconds 180 --workers 8   # 指定进程数
```

`config.json` 与 `devices.json` 含凭据，已在 `.gitignore` 中，**不得提交**。

## 读数的口径（重要）

客户端只能证明「平台已持久接收」（PUBACK），**不能证明已入库**。

过载时 EMQX 会先向设备 PUBACK，再在订阅端队列满时丢弃——
不携带消息 `id` 的设备会拿到确认而数据并未进入平台，且无从得知
（2026-10-28 实测一轮丢弃 51,671 条）。

因此：

- **接入 QPS** 看客户端的 PUBACK 速率，或服务端的 `emqx_messages_received`
- **入库 TPS** 必须看服务端的 `conduit_pipeline_processed_total` —— **对外承诺能力只能用这个**
- **是否超容量** 看 `conduit_transport_session_mqueue_len` 与消费组 `lag` 是否持续单调上涨

## 测试类型

按 `CLAUDE.md` 的约定，压测报告需写明属于哪一类：

| 类型 | 目的 | 速率 |
|---|---|---|
| 容量测试 | 找积压不增长的最大稳态速率 | 逐级上调直到队列持续增长，回退一档 |
| 压力测试 | 刻画失效模式与恢复能力 | 刻意过载，**必须同时核对失效日志** |
| 回归对比 | 改造前后比吞吐 | 固定在容量以下的同一速率 |

**判「容量以内」之前必须确认实际喂入达到了目标**（接入 QPS 中位 ≥ 目标的 85%）；
否则负载端没打满时队列同样不涨，会把「客户端打不动」误判为「系统游刃有余」。

## 已知限制

- 单机发压仍有上限；要再往上需要多台机器并发。
- 速率统计建议由 Prometheus 的 `rate()` 计算，不要在外部对计数器做差分
  （抓取间隔 15 秒 + 采样往返耗时不固定，自行差分噪声极大）。
