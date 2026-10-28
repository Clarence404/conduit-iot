# 2026-10-28 EMQX 会话楔死修复验证（e2e 与全量回归）

## 被测范围与代码状态

- 范围：关闭 EMQX 磁盘持久会话、改用内存持久会话（`max_mqueue_len=100000`、`max_inflight=32`）后，e2e 五个故障场景与全量回归。
- 基线提交：`2070936`（分支 `fix/emqx-session-wedge`），在其上调整 `E2eEnvironment`、`ReliabilityE2EIT`（提交见 git 历史 `test(e2e): 按内存持久会话调整重启 EMQX 场景`）。
- 本次 e2e 改动：Testcontainers 的 EMQX 环境变量改为 `EMQX_DURABLE_SESSIONS__ENABLE=false`、`EMQX_MQTT__MAX_MQUEUE_LEN=100000`、`EMQX_MQTT__MAX_INFLIGHT=32`；场景 4「重启 EMQX」改用带 id 的可靠上报设备（与场景 5 共用 `runReliableScenario`），断言不变（计数恰好、无死信、无待确认）；`TRANSPORT_MONITOR_ENABLED=false` 未改动。

## 环境

Windows 11 本机，JDK 21（liberica-21.0.8）；Docker 为虚拟机远程 API；EMQX 5.10.5 / Redis / PostgreSQL / InfluxDB 3 由 Testcontainers 启动，server、两个 transport 为本地 jar 进程。

## 实际命令与结果

```
mvn -B -DskipTests install                 # BUILD SUCCESS
E2E_DEV_HOST=<本机在虚拟网内的地址> mvn -B verify -Pe2e -pl conduit-e2e
mvn -B -o verify
```

### 无效尝试（如实记录）

第一次 e2e 把 `E2E_DEV_HOST` 设成了 Docker 虚拟机自身的地址。该变量的含义是「Docker 宿主机访问本机的地址」（EMQX 回调本机 server 的 `/internal/mqtt/auth`），设错导致 EMQX 回调不到 server、transport 持续 `NOT_AUTH`，`@BeforeAll` 等主实例超时，**0 个场景执行**（`ConditionTimeoutException`，总耗时 2:01）。这是环境变量配置错误，与代码无关。改为本机在虚拟网内的地址后重跑。

### e2e（第二次，有效）

| 项 | 结果 |
|---|---|
| EmqxConfigTest | 2 通过 |
| 场景 1 强杀 server | 通过 |
| 场景 2 强杀主 transport | 通过 |
| 场景 3 两个 transport 都结束约 20 秒 | 通过 |
| 场景 4 重启 EMQX（带 id 设备） | 通过 |
| 场景 5 强杀 EMQX（带 id 设备） | 通过 |

`ReliabilityE2EIT` 5 项 0 失败 0 跳过，耗时 203.8 s；整个命令 BUILD SUCCESS，3:28 min。未放宽任何断言或超时；未出现因 transport 5ms 攒批导致的超时。

### 全量回归 `mvn -B -o verify`

BUILD SUCCESS，3:50 min。各模块（单测 + 集成测试）：common 9+20、transport 81+16、server 255+268、simulator 82+4，合计 **735 项，0 失败 0 错误 0 跳过**。相对本次修复开始前的基线 699 项多 36 项（新增用例来自前序任务，本次提交只改 e2e 与报告）。

### 补充：场景 4/5 的设备侧重发条数（排除「碰巧通过」）

为确认场景 4 不是因重启时恰好无在途消息而空过，`ReliableE2eDevice` 新增重发计数（同一条消息第一次之外的发送），`runReliableScenario` 在发送结束时以 INFO 记录，不加断言。重跑 e2e（同命令，`E2E_DEV_HOST` 为本机虚拟网地址）：五个场景全部通过（`ReliabilityE2EIT` 5 项，205.7 s，BUILD SUCCESS 3:33）。

| 场景 | 设备侧重发条数 |
|---|---|
| 4 重启 EMQX（优雅重启） | 20 |
| 5 强杀 EMQX（SIGKILL） | 20 |

两个场景都真实触发了设备重发，场景 4 不是空过。两个数字恰好相同（10 台设备 × 每台 2 次），本次未进一步分析原因，也未多次运行评估波动，样本仅一次。

## 跳过或阻塞项

- 压测复验（3000 条/秒 × 180 秒）：见下节，不在本次执行范围。
- 前端 Vitest 不在 `mvn -B -o verify` 范围，未执行。

## 非阻塞警告

- e2e 模块打包时 `JAR will be empty - no content was marked for inclusion!`（既有，无影响）。

## 日志位置

`conduit-e2e/target/e2e-logs/<时间戳>/`（server、transport-a、transport-b）；失败的第一次为 `20261028-115656`。

## 压测复验

由控制方另行执行，结果待补。
