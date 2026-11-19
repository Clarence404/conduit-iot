# EMQX 可选 host 网络优化：配置验证

> 后续各三轮对照及6500台上探已完成，最终结论以
> [`2026-11-02-emqx-network-ab-results.md`](2026-11-02-emqx-network-ab-results.md) 为准。
> 下文保留当时的执行和中止记录，其中“尚未执行/正在运行”是历史状态。

- 日期：2026-11-02。
- 类型：部署配置回归验证；**不是容量测试，未产生新的 QPS/TPS 结论**。
- 补充：后续完成首轮6000台/每秒一条的bridge/host性能对照（下节）；未完成至少三轮的重复性验证，不发布新的容量上限。
- 代码：`codex/emqx-host-network`，基于 `b19cd27`；本轮改动未提交、未推送。
- 范围：新增 Linux EMQX 拆分部署可选 host 网络覆盖文件，默认 bridge 保持原样。
- 依据：`2026-11-01-ladder-1s-higher.md` 的 VM 收包软中断与缺缓冲丢包观测。

## 实际执行

### 空闲续办与防干扰验证（20:35起，进行中）

满足输入空闲约38分钟、65.3秒CPU采样平均5.75%/峰值14%后续办。
驱动补齐非测试后台CPU监测：对Windows进程CPU计数按逻辑核数归一化，
排除驱动及全部子进程、VM运行时，但保留无关的Python进程；连续两次非测试CPU≥15%则中止并恢复。
归属模型断言验证后代排除、无关Python保留与全机归一化，执行通过；实测非测试CPU约8.93%。
新增模式起始顺序选项、分核CPU快照与重建前告警日志留存。

已启动 `python -X utf8 -u docs/script/loadtest/network_ab.py --require-idle --repeats 2 --start-mode host --hold 300 --levels 6000`。
再次60秒预检CPU平均5.6%、峰值10.2%；host模式已回读healthy。顺序host/bridge/bridge/host，
与首轮合计各三次；结果目录为本地忽略的 `results/network-ab-20261102-203949/`。
该轮随后在首档host触发连续两次非测试CPU≥15%的保护，中止并自动恢复，没有生成完整summary。
此为受干扰中止，不记host通过或失败；重复对照仍未完成。
21:05独立核对无遗留发压进程，EMQX已恢复bridge/healthy，8个临时暂停容器及模拟器均恢复，
InfluxDB healthy/RestartCount=0。保留background JSONL及aborted标记，待更低背景负载再续办。

### 首轮实际性能对照（19:49～20:04）

命令：`python -X utf8 -u docs/script/loadtest/network_ab.py --repeats 1 --hold 300 --levels 6000`。
用户本轮明确授权继续及自行启动未运行的VM，故为即时执行，未使用后台空闲等待模式。
同一设备集、每台每秒1条、稳态300秒，先bridge后host；未刷新密钥、未改应用镜像。
原始JSON、summary、前后网络快照在本地忽略目录
`docs/script/loadtest/results/network-ab-20261102-194928/`。

| 指标 | bridge | host |
| --- | --- | --- |
| 已发出 = PUBACK = InfluxDB行数 | 1,799,979 | 1,799,980 |
| 入库TPS（现有阶梯脚本口径） | 5,979 | 6,000 |
| 掉线/首次拒绝/最终拒绝 | 0 / 0 / 0 | 0 / 0 / 0 |
| PUBACK P99 | 195.4ms | 1,387.6ms |
| 积压峰值 | 15,695 | 23,502 |
| 应用VM平均CPU | 51% | 37% |
| EMQX主机平均CPU（现有指标口径） | 57% | 59% |
| 发压机平均CPU / 峰值 | 77% / 95.2% | 52.5% / 81.5% |
| 连接强杀日志 / 过载告警 | 0 / 0 | 0 / 2 |
| 判据 | 通过 | 不通过：P99超1秒且有过载告警 |

辅助诊断：前后 `/proc/stat` 差值包含建连、稳态、排空和查询，不等同于稳态Prometheus均值。
该窗口bridge整机忙碌46.14%、softirq20.64%，host为50.28%、16.82%；
网卡各队列`pkts rx OOB`增量分别13,806与59,149。
软件路径占用下降并未转化为更好的用户可见延迟。两轮发压机CPU不同且每模式仅测一次，
无法据此断言host必然更慢或给出可靠收益百分比。
host过载告警的具体类型未在重建回bridge前另存日志，回退重建后容器旧日志不可直接重查，属于证据缺口。

结论：**本轮未取得性能提升，保留bridge默认，不推荐发布host模式。**
旧报告的6000台失败在本轮bridge未复现，进一步证明先前数字受环境状态影响，不能视作固定的平台上限。
未来对照应补宿主机背景负载记录、稳态分核收包指标与重建前完整日志，再做交替重复。

结束后脚本恢复bridge并回读healthy，8个其他容器及模拟器全部恢复运行；
独立核对InfluxDB healthy、RestartCount=0，无遗留发压进程。测试未推送提交。

命令（仓库根目录）：`python docs/script/loadtest/verify_host_network.py`。
Python 通过已有实验室 SSH 配置使用应用 VM 上的 Docker Compose v5.6.1。
仅上传三个部署模板和虚构凭据到临时目录，执行 `docker compose config --format json`；
不读取 VM 真实 `.env`，不创建/启动/重建服务。临时文件执行后清理。

| 配置 | 结果 |
| --- | --- |
| bridge、回环地址、默认端口 | 通过，保留两个端口映射和容器 sysctl |
| host、回环地址、默认端口 | 通过，清空端口映射/网络/sysctl，显式绑定监听端口 |
| bridge、全接口、自定义端口 | 通过，默认 bridge 兼容 |
| host、全接口、自定义端口 | 通过，MQTT/Dashboard 遵循自定义端口，SSL/WS/WSS 默认监听器关闭 |

合计 4 组通过，无失败、无跳过。另 `git diff --check` 通过。
执行过程中首次远程命令因 PowerShell 引号解析失败，未建立 SSH；改用单引号包裹 Python 后成功。

## 运行状态与未执行

### 后续试跑与暂停

19时用户要求继续并授权自行启动VM。应用VM确实关机，VMware后台启动后SSH与容器恢复；
`ethtool -g ens160` 回读RX=4096，补完应用VM接收环持久化的重启验证。
首轮续测未能排空基线，发现InfluxDB在写入时反复因WAL文件存在而关闭：
`00000425642.wal` 至 `00000425645.wal` 四文件均为0字节；只有一个InfluxDB容器使用该数据卷。
停库核对后将四文件移入数据卷的 `recovery/empty-wal-20261102/`，没有删除有效数据；
再次启动后healthy、RestartCount=0。故障轮未发压、未切host，恢复暂停的容器后重启对照。
VM启动前的历史数据完整性未核验，不能以恢复健康声称历史无损。

17:19 自动续办检查：键鼠空闲约21分钟，CPU采样64.7秒/11个样本，平均3.82%、峰值8%。
修正对照驱动的UTF-8输出和已上传覆盖文件SHA-256幂等核验；新增 `--require-idle`，
发压前再次检查CPU/输入空闲，发压中输入恢复会终止专属子进程树并执行服务恢复。
`python -X utf8 docs/script/loadtest/network_ab.py --help` 及 `git diff --check` 通过。
实际运行 `python -X utf8 -u docs/script/loadtest/network_ab.py --require-idle --repeats 1 --hold 300 --levels 6000`
复验CPU平均4.8%、峰值6.2%，但连接应用VM的SSH超时，在任何远程修改和发压之前退出。
因此6000档和host性能仍未测，不把此环境阻塞记作测试通过；远端服务恢复状态本次无法复核。

用户要求继续后启动 `python -u docs/script/loadtest/network_ab.py --repeats 1 --hold 300 --levels 5000,6000`。
首档bridge/5000完成发压：发出1,499,971、PUBACK 1,499,971、掉线0、首次/最终拒绝0，p99为223.2ms，
发压机CPU平均71.4%、峰值100%。但既有ladder在打印✅时因GBK输出抛出 `UnicodeEncodeError`，
没有生成最终summary，故不记整档验收通过。原始JSON与前置快照保存在本地忽略的results目录。

用户随后要求暂停，等待电脑CPU空闲再执行。已确认自动恢复逻辑执行完毕：桥接未变，
8个临时暂停容器恢复运行、模拟器恢复，无遗留发压进程。6000档与host模式均未执行。
下次须统一UTF-8子进程环境、核验远端已上传覆盖文件的幂等处理，并先满足CPU及键鼠空闲条件。

- 通过 Docker inspect 只读核对：EMQX 正在 bridge 网络中运行，未应用覆盖文件。
- 实际 EMQX 部署目录与旧交接所称的同路径假设不符，后续须重新取实时标签值。
- 未做 EMQX 容器启动、监听器实际状态、HTTP 鉴权、共享订阅恢复、监控或吞吐对照。
- 未修改 Linux 主机 sysctl、接收环、RPS/RFS、设备密钥和业务数据；未停其他服务。
- 不执行 Java/前端回归：未改应用代码，当前验证针对 Compose 合并语义。

## 演示与后续判据

使用既有压测产品、设备和 `ladder.py`，不另建业务种子、不重置设备密钥。
在实验室停发压、排空后，以两个 Compose 文件启用 host，回读网络模式和实际监听器，
再复测 5000/6000 台每秒一条，A/B 各至少三次；比较守恒、掉线、p99、softirq 与缺缓冲丢包。
host 模式的额外内部监听端口需核对宿主机防火墙。未取得结果前保留现有容量结论。

正式步骤与回退在 `deploy/split/README.md`，完整判据在
`docs/superpowers/specs/2026-11-02-emqx-host-network-design.md`。
当前任务未完成部分继续维护同一份 `docs/handoffs/2026-11-02-split-loadtest-ceiling.md`。

## 架构同步

已更新 `CLAUDE.md` 1.2 / 第 4 节与拆分部署说明，新增专项 spec。
检查 `README.md`、`architecture.svg`、`module-dependencies.svg`、`reliable-uplink.svg`：
本轮只增加未启用的可选网络配置，当前实际数据流、应用模块依赖和可靠上报语义不变，
因此保留现有图源，不把尚未部署的模式画成已实现的运行状态。
