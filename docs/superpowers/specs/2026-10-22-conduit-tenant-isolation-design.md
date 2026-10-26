# Conduit 租户隔离加固设计

- 日期：2026-10-22
- 状态：已实施（2026-10-22，验收记录见 `docs/test-reports/2026-10-22-tenant-isolation.md`）
- 范围：在现有「`tenant_id` 列 + MyBatis-Plus 租户拦截器」之上补三件事——数据库级的跨租户关联约束、唯一受控的跨租户通道、以及越权测试套件。
- 定位：V0.3 的前置一次性加固，不占子项目编号。后续 V0.3-B（组织模型与 RBAC）等子项目在这套规矩上继续建设。

## 1. 背景

路线图原文要求「PostgreSQL 行级安全或等价的数据库强制隔离」。2026-10-22 讨论后**决定不实施 RLS**，理由与取舍见第 3 节。本设计是落地的替代方案：隔离保证主体仍在应用层，但把最容易出错的几处收紧到可测、可审计。

## 2. 现状核实（2026-10-22）

实施前先确认当前状态，避免重复建设。

### 2.1 已经做到的

| 项 | 状态 |
|---|---|
| `tenant_id` 列 | `product`、`device`、`product_property`、`app_user` 四张表全部具备 |
| SQL 自动过滤 | `MybatisPlusConfig` 的 `TenantLineInnerInterceptor`，`TENANT_FREE_TABLES = {tenant, app_user}` |
| 无租户上下文即拒绝 | `TenantContext.require()` 未绑定时抛 `IllegalStateException` |
| InfluxDB 隔离 | `HistoryQueryBuilder.Scope` 的构造必须给 `tenantId`，计数/原始/聚合三条 SQL 的 WHERE 固定含 `tenant_id = $tenant` |
| 用户按租户过滤 | `UserService` 的四处查询均显式 `.eq(AppUser::getTenantId, tenantId)` |

### 2.2 Redis 的隔离来源

Redis 键不含租户（`conduit:latest:{deviceKey}`、`conduit:presence:{deviceKey}`、`conduit:auth:{deviceKey}`）。核实全部读取点后确认：**deviceKey 全部来自一次已经过租户过滤的 PostgreSQL 查询**，Redis 没有独立的暴露面。

| 位置 | deviceKey 来源 |
|---|---|
| `TelemetryController.latest` | 先 `devices.get(deviceKey)`，跨租户 404 |
| `HistoryController.history` | 同上，且 `Scope` 带 `TenantContext.require()` |
| `DeviceService` 设备列表 / 删除清理 | 租户过滤后的设备列表 |
| `DeviceStats` / `OverviewService` | 同上 |
| `TelemetryIngestor` / `PipelineProcessor` | 设备身份自带的 `tenantId` |
| `TenantStats` | 平台级，有意跨租户 |

`device_key` 在数据库中全局唯一（`V1__init.sql`），不存在两个租户抢同一个键的情况。

据此**不给 Redis 键加租户前缀**，改为把前提固化为一条约定并用测试守住（见 6.3、7.4）。

### 2.3 现有的 7 处 `@InterceptorIgnore`

核实后分三类，不是同一回事：

| 位置 | 性质 | 处置 |
|---|---|---|
| `DeviceMapper.findIdentity` | 真·跨租户（MQTT 鉴权、流水线按 deviceKey 查身份） | 迁入系统 Mapper |
| `ProductMapper.findByKeyAnyTenant` | 真·跨租户（流水线按 productKey 查产品） | 迁入系统 Mapper |
| `PlatformStatsMapper.productCounts` | 平台级跨租户统计 | 迁入系统 Mapper |
| `PlatformStatsMapper.devices` | 平台级跨租户统计 | 迁入系统 Mapper |
| `PlatformStatsMapper.deviceKeysOf` | 平台级（按租户清身份缓存） | 迁入系统 Mapper |
| `ProductMapper.lockById` | **不是跨租户**。`product` 受拦截器过滤，这里加豁免只是为了手写的 `for update` SQL 不被改写 | **去掉豁免**，让拦截器正常追加 `tenant_id` |
| `TenantMapper.lockById` | **冗余**。`tenant` 本就在 `TENANT_FREE_TABLES` 中，拦截器根本不会处理它 | **删除注解** |

### 2.4 真实的缺口

1. **跨租户错误关联无约束**：`device.product_id` 的外键只指向 `product(id)`，不带租户。一条 `tenant_id = A` 但 `product_id` 属于 B 的设备，数据库不会拒绝。`product_property` 同理。现在的代码碰巧安全（`DeviceService` 经租户过滤的 `productService.require()` 解析 productKey），但这正是「靠代码写对」。
2. **豁免是单一动作**：加一个注解就能跨租户，没有第二道门，也没有日志。
3. **事务中可切换租户**：`TenantContext.callAs` 没有任何限制，事务前半段和后半段可以属于不同租户。
4. **没有越权测试**：现有测试没有一条是「以租户 A 的身份访问租户 B 的资源」。

## 3. 决定：不实施 RLS

讨论中评估过 PostgreSQL 行级安全（`enable/force row level security` + 基于会话变量 `conduit.tenant_id` 的策略 + DataSource 层设置变量），**决定暂不实施**。

**放弃了什么（必须明确记录）**：隔离保证全部留在应用层。以下路径数据库不会替应用拦截，只能靠本设计的约定与测试守住：

- 不经过 MyBatis 的原生 JDBC
- 将来替换 ORM 或引入不走拦截器的组件
- 数据库工具直连执行 SQL
- 新增的 `@InterceptorIgnore`（本设计用第 5 节收紧，但那是应用层规则）

**重新评估的触发条件**（出现任一即重新讨论）：

- 出现一条真实的跨租户数据泄露（无论是否影响线上）
- 引入不经过 MyBatis 的数据访问路径（报表、批处理、直连分析工具等）
- 多人协作开发，或新增表数量超过 10 张使人工守规模不可靠

RLS 是纯叠加能力：届时实施只需增加一个迁移和一层 DataSource 包装，本设计产出的组合外键、系统模式与测试套件全部保留，不会返工。

## 4. 改动一：组合外键（迁移 V5）

补上数据库级的跨租户关联约束。这是本设计中**唯一由数据库强制**的部分。

### 4.1 实施前检查

迁移会因存量脏数据失败。实施者先在开发库与演示库执行：

```sql
select d.id, d.tenant_id, p.tenant_id as product_tenant
from device d join product p on p.id = d.product_id
where d.tenant_id <> p.tenant_id;

select pp.id, pp.tenant_id, p.tenant_id as product_tenant
from product_property pp join product p on p.id = pp.product_id
where pp.tenant_id <> p.tenant_id;
```

两条都必须返回 0 行。有行则先查清来源再决定修数据还是改设计，**不得静默修正**。

### 4.2 迁移内容

新建 `conduit-server/src/main/resources/db/migration/V5__tenant_scoped_fk.sql`：

```sql
-- 跨租户关联约束：子行的 tenant_id 必须与父行一致，由数据库强制。
-- 组合外键需要父表上有对应的唯一键。
alter table product
    add constraint uk_product_tenant_id unique (tenant_id, id);

-- device：原外键只到 product(id)，换成带租户的组合外键（保持无级联，与原约束一致）。
alter table device
    drop constraint device_product_id_fkey;
alter table device
    add constraint fk_device_product
        foreign key (tenant_id, product_id) references product (tenant_id, id);

-- product_property：原外键带 on delete cascade，组合外键必须保留该行为。
alter table product_property
    drop constraint product_property_product_id_fkey;
alter table product_property
    add constraint fk_product_property_product
        foreign key (tenant_id, product_id) references product (tenant_id, id) on delete cascade;
```

**注意**：被 drop 的两个约束名是 PostgreSQL 为 `V1__init.sql` / `V2__thing_model.sql` 中的列级 `references` 自动生成的默认名。实施者必须先用下面的查询确认实际名称，不得假定：

```sql
select conname, conrelid::regclass from pg_constraint
where contype = 'f' and conrelid in ('device'::regclass, 'product_property'::regclass);
```

### 4.3 不改动的部分

`product_key` 与 `device_key` **保持全局唯一**，不改为租户内唯一。它们出现在 MQTT 主题 `conduit/{productKey}/{deviceKey}/up/#` 与 InfluxDB 的 tag 中，改为租户内唯一需要把租户写进主题，属于协议层变更，不在本次范围。

## 5. 改动二：跨租户只能走系统模式

### 5.1 `TenantContext.callAsSystem`

新增方法：

```java
/**
 * 以系统模式（跨租户）执行。进入时清除当前线程的租户绑定，结束后恢复，并记一行 INFO 日志。
 * 清除而不是保留绑定，是为了让系统模式内部误用的普通查询立即抛
 * IllegalStateException，而不是悄悄按外层租户过滤。
 *
 * @param purpose 用途标签，写进日志，例如 "mqtt-auth"、"pipeline-product"、"platform-stats"
 */
public static <T> T callAsSystem(String purpose, Supplier<T> action)

/** 无返回值版本。 */
public static void runAsSystem(String purpose, Runnable action)
```

语义：

- 进入时 `CURRENT.remove()`，结束时恢复进入前的绑定（与 `callAs` 一致，支持嵌套）。
- 每次进入记一行 INFO：`系统模式: {}`，参数为 `purpose`。不记查询内容与参数。
- 系统模式**不改变** `@InterceptorIgnore` 的作用。两者是独立的两道门：没有注解的查询在系统模式里会因 `require()` 抛异常；有注解但不在系统模式里调用的，由 5.3 的测试禁止。

### 5.2 系统 Mapper 归拢

新建包 `dev.conduit.server.tenant.system`，把真正需要跨租户的查询集中进去：

| 新位置 | 来源 | 方法 |
|---|---|---|
| `system/DeviceIdentityMapper` | `DeviceMapper.findIdentity` | `findIdentity(String deviceKey)` |
| `system/ProductLookupMapper` | `ProductMapper.findByKeyAnyTenant` | `findByKeyAnyTenant(String productKey)` |
| `system/PlatformStatsMapper` | `platform/PlatformStatsMapper` 整体移动 | `productCounts()`、`devices()`、`deviceKeysOf(long)` |

SQL 与返回类型保持不变，只改所在类与包。记录类型按「是否只服务于该 Mapper」区分：`TenantCount`、`TenantDevice` 只有平台统计查询用到，随 `PlatformStatsMapper` 一起迁入 `tenant.system` 并作为其嵌套记录（原 `platform` 包中的同名类随之删除，避免出现两个 `PlatformStatsMapper`）；`DeviceIdentity` 还被缓存、鉴权与流水线共用，留在 `device` 包，由系统 Mapper 引用。

调用方改为在系统模式中调用，例如：

```java
// MqttAuthService
DeviceIdentity id = TenantContext.callAsSystem("mqtt-auth",
        () -> identities.findIdentity(deviceKey));
```

用途标签取值固定为：`mqtt-auth`、`pipeline-identity`、`pipeline-product`、`platform-stats`、`tenant-device-keys`。实际调用点以实施时的代码为准，新增标签需在本表追加。

同时处理 2.3 的另外两处：

- `ProductMapper.lockById`：删除 `@InterceptorIgnore`，让拦截器为 `select id from product where id = #{id} for update` 追加 `tenant_id` 条件。**实施者必须验证** MyBatis-Plus 对带 `FOR UPDATE` 的语句改写正确（断言生成的 SQL 含 `tenant_id`，且原有的属性导入并发测试仍通过）；若改写有问题，改为手写 `and tenant_id = #{tenantId}` 并由调用方传入，不保留豁免。
- `TenantMapper.lockById`：直接删除冗余的 `@InterceptorIgnore`（`tenant` 已在 `TENANT_FREE_TABLES` 中）。

### 5.3 禁止新增豁免

新增一条架构测试（纯 Java 反射或源码扫描，不引入新依赖）：

> 扫描 `dev.conduit.server` 下的全部类型与方法，断言带 `@InterceptorIgnore` 的声明只出现在 `dev.conduit.server.tenant.system` 包中。

失败信息要指出违规的类与方法，并说明「跨租户查询必须放入 `tenant.system` 包，并经 `TenantContext.callAsSystem` 调用」。

## 6. 改动三：一事务一租户

### 6.1 规则

一个数据库事务只服务一个租户。事务进行中不得从租户 A 切到租户 B，也不得从某个租户切入系统模式；从「未绑定」进入任何模式是允许的（平台接口本就没有租户上下文）。

### 6.2 实施

在 `callAs` / `runAs` / `callAsSystem` / `runAsSystem` 进入时断言：

```java
// 伪代码
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    Long previous = CURRENT.get();
    boolean switching = previous != null && !previous.equals(target);  // target 为 null 表示系统模式
    if (switching) {
        throw new IllegalStateException(
                "事务中不允许切换租户: " + previous + " -> " + (target == null ? "system" : target));
    }
}
```

**实施者注意**：现有三处调用点可能触发该断言——`TenantAdminService:187`（删除租户前检查数据）、`ThingModelCache:68`（加载物模型属性）、`JwtAuthFilter:45`（请求入口绑定）。全量测试若因此失败，**必须报告具体场景并一起讨论**，不得为了让测试通过而放宽断言条件。

### 6.3 Redis / InfluxDB 访问前置条件

把 2.2 核实出的前提固化为约定，写入 `CLAUDE.md` 1.2：

> 访问 Redis（最新值、在线状态）与 InfluxDB 之前，必须先经受租户过滤的 PostgreSQL 查询取得设备，用查出来的 deviceKey 作为键；禁止直接用请求参数中的 deviceKey 读写这两个存储。`conduit:auth:{deviceKey}` 是例外——MQTT 登录时只有 deviceKey，它是有意的平台级键，其缓存内容必须带可信的 `tenantId`，后续处理一律以该值为准。

## 7. 测试

全部为新增，覆盖 2.4 的四个缺口。

### 7.1 组合外键（集成测试，Testcontainers）

- 插入 `tenant_id = A` 但 `product_id` 属于 B 的设备 → 数据库拒绝（外键违例）。
- 同样条件插入 `product_property` → 拒绝。
- 正常的同租户插入 → 成功。
- 删除产品时其 `product_property` 随之级联删除（验证 `on delete cascade` 未在换约束时丢失）。

### 7.2 系统模式

- `callAsSystem` 内调用普通 Mapper（未标注豁免）→ 抛 `IllegalStateException`。
- `callAsSystem` 结束后恢复进入前的绑定；嵌套正确。
- 进入系统模式记一行 INFO 且含 `purpose`，不含查询参数。
- 架构测试：`@InterceptorIgnore` 只出现在 `tenant.system` 包（5.3）。

### 7.3 一事务一租户

- 事务中 `callAs(B)` 而当前为 A → 抛异常。
- 事务中 `callAsSystem` 而当前已绑定租户 → 抛异常。
- 未绑定时进入任一模式 → 允许。
- 无事务时切换 → 允许。

### 7.4 接口级越权（集成测试）

准备两个租户各自的数据与令牌。以租户 A 的令牌访问 B 的资源，**全部断言 404**（不是 403，避免暴露资源是否存在）：

| 接口 | 用 B 的标识 |
|---|---|
| `GET /api/v1/products/{productKey}` | B 的 productKey |
| `PUT /api/v1/products/{productKey}` | B 的 productKey |
| `DELETE /api/v1/products/{productKey}` | B 的 productKey |
| `GET /api/v1/devices/{deviceKey}` | B 的 deviceKey |
| `PUT /api/v1/devices/{deviceKey}` | B 的 deviceKey |
| `DELETE /api/v1/devices/{deviceKey}` | B 的 deviceKey |
| `POST /api/v1/devices/{deviceKey}/reset-secret` | B 的 deviceKey |
| `GET /api/v1/devices/{deviceKey}/latest` | B 的 deviceKey |
| `GET /api/v1/devices/{deviceKey}/history` | B 的 deviceKey |
| `GET /api/v1/products/{productKey}/properties` | B 的 productKey |
| `GET /api/v1/users/{id}` | B 的用户 id |
| `PUT /api/v1/users/{id}` | B 的用户 id |
| `DELETE /api/v1/users/{id}` | B 的用户 id |
| `POST /api/v1/users/{id}/reset-password` | B 的用户 id |

接口清单以实施时的实际路由为准；实施者须遍历 `/api/v1/**` 中所有以 productKey、deviceKey 或用户 id 为路径参数的接口，不得只做上表所列。

另加两条：

- 以 A 的令牌创建设备并指定 B 的 productKey → 404（产品解析阶段即失败）。
- 列表接口（`GET /api/v1/products`、`GET /api/v1/devices`、`GET /api/v1/users`、`GET /api/v1/overview`）在 A 的令牌下不含任何 B 的数据，计数也不含 B。

### 7.5 回归

`mvn -B verify` 与 e2e 五个场景全部通过，确认收口改造没有影响 MQTT 鉴权与流水线（这两条路径都用到被迁移的系统 Mapper）。

## 8. 文档同步

- `CLAUDE.md` 1.2「多租户与用户」：更新跨租户查询的描述（`tenant.system` 包 + `callAsSystem`，不再是「标注 `@InterceptorIgnore` 即可」），补 6.3 的 Redis / InfluxDB 访问前置条件与一事务一租户规则。
- `CLAUDE.md` 第 4 节：追加一条决定记录，含「不实施 RLS」及其重启条件。
- `docs/roadmap-saas.md` V0.3 一节：**已随本 spec 一并更新**（V0.3-A 改为前置加固、RLS 降级为可选增强并记录触发条件，B～F 编号与依赖不变），实施时无需再改，只需复核结论仍与实际一致。
- 架构文档与图：本次不改变组件拓扑、数据流、协议、存储与部署方式，`docs/images/architecture.svg` 与 `module-dependencies.svg` **无需修改**；`README.md` 架构说明同理。交付说明中应写明已检查且无需修改的结论。

## 9. 完成标准

1. 迁移 V5 在开发库与演示库上成功执行，4.1 的两条检查查询均返回 0 行。
2. 7.1～7.4 全部新增测试通过；越权测试覆盖所有带 productKey / deviceKey / 用户 id 路径参数的接口。
3. 架构测试证明 `@InterceptorIgnore` 只存在于 `tenant.system` 包。
4. `mvn -B verify` 与 e2e 五个场景通过。
5. 第 8 节的文档同步完成。
6. 按 `CLAUDE.md` 2.2 在 `docs/test-reports/` 留存本次测试报告。
