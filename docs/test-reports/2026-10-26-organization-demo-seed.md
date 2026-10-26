# 组织演示种子测试报告

- 日期：2026-10-26
- 分支：`codex/v0.3b-rbac-design`
- 范围：Flyway V7 组织演示数据与存量设备分配。
- 结果：新库与升级专项通过；VM 验收记录见后续补充。

## 数据基线

| 数据 | 全平台新增 | 每个演示租户 |
|---|---:|---:|
| 客户 | 8 | 2 |
| 业务项目 | 8 | 2 |
| 业务站点 | 16 | 4 |
| 资产（设备区、主设备组、备用设备组） | 48 | 12 |

全新库包含系统默认组织后共 12 项目、20 站点。V4 的 174 台设备分配至主设备组；备用设备组为禁用状态，便于展示状态标签。四个租户分别覆盖园区、配电、水务和光伏场景。动态遥测由模拟器产生。

## 实际执行

Java 21.0.8，PostgreSQL 16.8；Testcontainers 经 SSH 隧道使用 VM Docker，临时数据库与演示库分离。

```powershell
$env:JAVA_HOME='C:\Users\ChenHan\.jdks\liberica-21.0.8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:DOCKER_HOST='tcp://127.0.0.1:23750'
$env:TESTCONTAINERS_HOST_OVERRIDE='192.168.126.128'
mvn -B -pl conduit-server -am verify `
  '-Dit.test=DemoSeedMigrationIT,OrganizationModelIT,OrganizationApiIT' `
  '-Dfailsafe.failIfNoSpecifiedTests=false' `
  '-DargLine=-javaagent:C:\Users\ChenHan\.m2\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar'
```

结果：BUILD SUCCESS；common 单元测试 9、server 单元测试 247、专项集成测试 8，全部通过，无失败/错误/跳过。

- 新库执行 V1～V7：8 客户、12 项目、20 站点、48 资产、174 设备归属、32 子资产均符合断言。
- V3 升级：同名旧设备复用、不重复插入。
- V6 升级：人工调整到非默认站点的设备归属保留；普通租户不新增演示客户。
- 再次 migrate：无新迁移，组织数量不变。
- 组织 API、默认组织保护、租户组合外键测试通过。
- 证据：`conduit-server/target/failsafe-reports/` 与各模块 `target/surefire-reports/`。
- 未执行：本次未重跑完整后端及前端套件；变更只增加 SQL 种子及其迁移断言，已执行相关回归。

## 文档和架构

已同步 README、CLAUDE.md §1.2 与第 4 节。检查 architecture.svg、module-dependencies.svg 和 organization-model.svg：领域关系、组件和协议未变化，本次只填充演示记录，无需修改图源。路线图未修改。
