# 2026-10-30 压测 VM 12,000 条/秒 CPU 去向

## 被测范围与代码状态

- 代码：`7f83c6b`（server 含鉴权原因计数，模拟器排除压测产品）。VM 配置回读：EMQX `max_inflight` 1024、`max_mqueue_len` 100000、`max_mailbox_size` 10000、`max_heap_size` 256MB、`hash_clientid`；transport 批 512、4 连接共享订阅。与 2026-10-29 容量测试相同。
- 目的：容量已到硬件墙（2026-10-29 报告），要再提高吞吐须先知道每条消息的 CPU 花在哪里。

## 测试类型

压力测试（12,000 条/秒，接近 2026-10-29 的边缘档）+ CPU 剖析。

## 命令与方法

- `loadgen.py refresh`；`loadgen.py run --devices 600 --rate 20 --seconds 100 --workers 16`（发压机在 VM 外）。
- 负载第 25～85 秒对 VM 的 `/proc/stat`、`/proc/softirqs`、全部进程 `/proc/<pid>/stat` 各取一次快照求差（VM 无 pidstat/perf；cadvisor 未部署，Prometheus 无容器级 CPU）。

## 结果

### 1. 本次 12,000 条/秒失败

| 时刻 | EMQX 收（条/秒） | 投递给 transport | 入库 | transport 会话 mqueue | 信箱最大 | VM CPU |
|---|---|---|---|---|---|---|
| 10:25:00 | 11,386 | 5,072 | 4,952 | 55,743 | 792 | 95% |
| 10:25:30 | 11,393 | 3,323 | 9,634 | 150,008 | 3,189 | 97% |
| 10:26:00 | 11,038 | 3,831 | 3,640 | 137,044 | 183 | 91% |

实际供给约 11,400 条/秒（未达名义 12,000）。EMQX 向 transport 的投递只有 3,300～5,100 条/秒，积压进会话队列；EMQX 丢弃计数增加 1,266，失效日志 6 行，transport 两条连接在 10:25:23、10:25:30 重连。**按守恒判据为失败**（未做 InfluxDB 行数核对）。昨天 13,000 为 3 次 2 过，今天 11,400 失败，说明在墙附近单次结果波动很大，不能据单次下结论。

### 2. CPU 去向（11,400 条/秒，61 秒窗口）

整机 8 核：user 2.85、system 2.18、softirq 2.04、irq 0.21、idle **0.43**。softirq 以 NET_RX 为主（27,810 次/秒）。

| 进程 | 核 | 约 µs/条 |
|---|---|---|
| EMQX（beam.smp） | 2.69 | 236 |
| 内核软中断 + 硬中断（不计入任何进程） | 2.25 | 197 |
| conduit-server | 0.59 | 52 |
| transport 主实例 | 0.53 | 46 |
| ksoftirqd | 0.36 | 32 |
| Redis | 0.31 | 27 |
| InfluxDB | 0.16 | 14 |
| kswapd0 + kcompactd0 | 0.20 | — |

**EMQX 与内核网络合计约 5.3 核，占忙碌 CPU 的约 70%**；平台自身（server + transport + Redis + InfluxDB）约 1.6 核。即再往上，主要矛盾不在本项目代码，而在 MQTT 接入与网络栈。另见 kswapd/kcompactd 在跑：VM 可用内存约 1.9 GB、已用 swap 361 MB，存在内存压力。

### 3. 排除的一条误线索

负载结束后 `docker stats` 多次显示 EMQX 空闲时 CPU 55%～118%，而 EMQX 自报调度器利用率仅 0.013%。按线程采样 `/proc/<beam>/task/*/stat` 30 秒，beam 全部线程合计约 0.03 核——`docker stats` 的单次瞬时值是采样假象，EMQX 空闲时确实空闲，不存在空转耗 CPU 的问题。

## 未执行 / 局限

- 未做 InfluxDB 行数核对与重复运行；本报告不更新容量结论。
- 软中断无法分摊到「设备进入 VM 的流量」与「容器之间经 docker 网桥的流量」，需 perf 或对比实验（如 EMQX 改 host 网络）才能区分。
