# 2026-10-30 模拟器排除压测产品

## 被测范围与代码状态

- `conduit-simulator`：新增 `SIM_EXCLUDE_PRODUCTS`（名称或 productKey，逗号分隔，默认 `压测产品`，配成空表示不排除），`TenantDiscovery` 在读物模型与重置密钥之前跳过被排除的产品。
- 独立部署 compose 增加 `SIM_EXCLUDE_PRODUCTS: ${SIM_EXCLUDE_PRODUCTS-压测产品}`，`.env.example`、README、压测 README 同步。
- 基线提交 `64a9fc9`，改动随本报告一起提交。

## 测试类型

功能验证（单元测试 + 压测 VM 上双向核对密钥不再互相作废）。不是容量或压力测试。

## 结果

### 1. 单元测试

- 先写测试，确认因缺少 `excludedProducts` 与 `discover` 第 5 个参数而编译失败，再实现。
- 新增 `TenantDiscoveryTest.excludedProductsAreSkippedBeforeAnySecretIsReset`：按名称与按 productKey 各排除一个产品，结果为空，且没有读这两个产品的物模型、没有任何 `reset-secret` 请求。
- 新增 `SimulatorConfigTest.excludedProductsDefaultToTheLoadTestProduct`：默认 `压测产品`；逗号列表去空白、去空项；显式空串为不排除。
- `mvn -o test`（conduit-simulator，JDK 21）：84 个，失败 0、错误 0、跳过 0。

### 2. 压测 VM

部署：VM compose 定点插入一行（备份 `.bak-20261030`），VM `.env` 未设置该变量即取默认；换 `conduit-simulator-exec.jar`（旧 jar 备份）后 `build`/`up -d simulator`。回读：容器内 `SIM_EXCLUDE_PRODUCTS` 为正确的 UTF-8「压测产品」（`od -c` 核对字节）。

模拟器日志：`Product pmzp2t5z7amy4ev8 (压测产品) in tenant default is in SIM_EXCLUDE_PRODUCTS, not simulated and secrets untouched`，模拟设备数由 274 降为 174（少的正是压测产品的 100 台）。

| 步骤 | 结果 | 鉴权计数增量 |
|---|---|---|
| 第一次尝试 ①：重启后直接用 devices.json 压测 | **失败**，100/600 被拒 | — |
| ② `refresh` 后压测 600 台 × 0.5 条/秒 × 20 秒 | 600 台全部通过，PUBACK 6,000、客户端错误 0 | ①② 合计 `allow/device` +1,100、`wrong_secret` +700 |
| 复验：devices.json 已最新，重启新版模拟器后**不 refresh** 直接压测 | 600 台全部通过、客户端错误 0 | `allow/device` +774（模拟器 174 + 压测 600），`wrong_secret` **+0** |

第一次尝试 ① 的失败是验证设计错误，不是改动无效：那 100 台的密钥是 08:59 重启**旧版**模拟器时被重置的（上一项验证中），晚于 devices.json 最后一次刷新、早于新版部署；新版启动只证明它不再重置，无法撤销旧版已做的重置。为此补做复验，结论以复验为准。100 台被拒对应 `wrong_secret` +700，约每台 7 次，推测为 loadgen/paho 的连接重试，未进一步核实。

## 遗留与未验证项

- 已有的压测数据仍需在部署后 `refresh` 一次（旧版模拟器留下的重置）。
- 若 `.env` 把 `SIM_EXCLUDE_PRODUCTS` 配成空或压测产品改名，冲突会回来；看板「设备鉴权」的「拒绝·wrong_secret」可直接发现。
- 未执行 e2e（不涉及模拟器发现路径）。
