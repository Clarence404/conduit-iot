# B2 自定义角色与项目成员委派测试报告

- 日期：2026-10-26（Asia/Shanghai）
- 分支：`codex/v0.3b-custom-roles`
- 范围：租户自定义角色创建、修改、停用、删除约束；项目管理员仅在本项目委派成员；界面与可复现演示。

## 设计与数据

沿用 Flyway V8 的 `role_definition`、`role_permission`、`role_binding`，无需更改已有迁移或用户数据。预置角色保持不可编辑；自定义角色键以 `CUSTOM_` 开头，稳定权限码必须在目录中，不能授予操作者没有的权限；`tenant.manage` 只允许内置 OWNER。修改绑定中角色的作用域若不兼容则拒绝，停用立即使绑定无效，绑定存在时拒绝删除。项目委派只替换当前项目绑定，不能管理租户管理员/所有者，不能授予高于自身的权限；数据库仍用原有租户组合外键保护。

演示脚本在默认演示租户补 `CUSTOM_INSPECTOR` 和 `rbac-inspector`，由 `rbac-demo` 在项目 A 委派。仅在显式 `--demo` 和环境变量完整时启用；重跑不覆盖已有密码、状态、角色或人工绑定。A 设备可读不可写，B/C 不可见；与原 A 可写/B 只读/C 不可见演示配合。动态遥测仍由模拟器生成。

## 本地执行与判据

- Java 21 + Docker Testcontainers；`AuthorizationIT` 11 项通过，覆盖角色绑定、停用后的旧令牌下一请求失效、绑定中拒删、内置角色拒删、项目管理员跨项目404、租户管理员不可改、委派后读取 A 而不可见 B、撤权立即失效，以及既有范围矩阵。完整回归见下方最终结果。
- 前端全套 68 文件、431 项通过；两个新增组件专项各 1 项通过。`npm run build`（含 vue-tsc）通过，保留 Vite 大 chunk 提示。
- Python `py_compile` 通过；`git diff --check` 无空白错误。演示脚本的业务验收以 VM 的 `--verify` 实际执行为准。

首次用 `-Dtest=AuthorizationIT` 运行时，reactor 中 common 无同名测试，Maven 因“未匹配指定测试”退出；改用 `-Dit.test` 后根模块同样需要 `-Dfailsafe.failIfNoSpecifiedTests=false`。参数修正后 11 项通过，测试断言未放宽。前端首次构建因新 `RoleDefinition.built_in` 字段未补进旧组件测试桩而报 TS2741；补齐测试桩后构建通过。

## 完整回归

### 后端全量

```text
JAVA_HOME=<Liberica JDK 21.0.8>
DOCKER_HOST=tcp://<开发虚拟机>:2375
TESTCONTAINERS_HOST_OVERRIDE=<开发虚拟机>
mvn -B -o verify
```

- 结果：`BUILD SUCCESS`，五个模块全部成功；**689 项通过，0 失败、0 错误、0 跳过**，用时 3 分 46 秒。
- 分项（单元 + 集成）：common 9 + 17、transport 48 + 16、server 249 + 264、simulator 82 + 4。
- 与上一基线相比，server 新增 2 项单元、18 项集成，与本次新增的角色与项目成员用例一致。
- 证据：各模块 `target/surefire-reports` 与 `target/failsafe-reports`。

### 前端全量

```text
cd conduit-ui
npm run test
```

- 结果：68 个测试文件、431 项全部通过，用时 34.3 秒。

### 未执行项（不计入通过）

- 可靠性 e2e（`-Pe2e`）**本轮未执行**。本次改动只涉及授权与界面，未触及 transport、流水线、总线与 EMQX 接入路径；`PermissionGateFilter` 仅新增一条 `/api/v1/projects/{code}/members` 路由映射，不影响 MQTT 鉴权端点。如需覆盖，应单独执行并在此补记实际结果。

## VM 验收

待发布后填写访问入口、演示账号、脚本重复执行结果、页面可见性、越权 API 结果与运行限制。发布前不把脚本存在当作已验收。

## 架构同步

已同步 README、CLAUDE.md §1.2/§4、spec、roadmap、授权/API 与演示说明。授权流程 SVG 的 PostgreSQL 框更新为自定义角色与绑定；进程拓扑、模块依赖、组织关系未变化，现有架构 SVG 不另改。
