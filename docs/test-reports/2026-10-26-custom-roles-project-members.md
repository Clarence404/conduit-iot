# B2 自定义角色与项目成员委派测试报告

- 日期：2026-10-26～27（Asia/Shanghai）
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
- 本次总数为 689；各模块合计以本次 Maven 日志为准，不以旧报告推算增量。授权专项另外重跑 `AuthorizationIT` 与 `AuthorizationReadOnlyIT`，12 项通过。
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

2026-10-27，发布提交 `046687c` 到演示 VM，沿用原 `enforce` 模式与已有 PostgreSQL/Redis/InfluxDB/EMQX、transport 和模拟器，仅重建 server/web。发布前 `pg_dump -Fc` 备份到 VM `/opt/conduit-backups/postgres-before-custom-roles-20261027.dump`（77060 字节、权限 600、SHA-256 `ebf749cc79b82eccfc7f537a8329dbebf35b222db75580f6a739c88505f4e771`），在 PostgreSQL 容器内用 `pg_restore --list` 校验目录。Flyway 验证 8 个迁移且无新迁移，server 健康探针 UP；发布后日志未见 ERROR/Exception。

`deploy/demo/authorization-demo.py --demo --verify` 在 VM **连续运行两次均通过**。原 A/B/C 设备 Key 分别保持为 `7np1abt8cq8inhkj`、`nt16d24y3y3ry85k`、`svyrukbjnmllpcmk`，第二次没有重建；原 `rbac-demo` 账号与绑定保留。新建 `CUSTOM_INSPECTOR`（项目范围、3 个只读权限）和 `rbac-inspector`，由 `rbac-demo` 在项目 A 委派。实际接口断言：`rbac-demo` A 可写/B 只读/C 隐藏、租户用户管理拒绝；`rbac-inspector` A 可读不可写、B 不可见。两次均输出两个 PASS。演示密码由运行环境提供，未写入仓库或报告。

页面检查：`http://<演示 VM>:8090`，管理员进入「系统管理 → 用户 → 角色管理」可见 `CUSTOM_INSPECTOR`/「权限演示巡检员」、项目范围、3 项权限、启用状态；「组织 → 项目 → 权限演示 A（可写）→ 成员」可见 `rbac-demo` 与 `rbac-inspector`，后者绑定显示「权限演示巡检员 / 整个项目」。三台演示设备继续在线，实际最后上报距查询约 6.8～7.0 秒，Redis 消费组 lag 约 10 条，MQTT 新会话无 Broker 丢弃或堆积；遥测由原模拟器持续生成。访问账号与密码由交付消息提供。

旧 MQTT 会话积压仍未回放，沿用上一阶段已经记录的保留方案；本轮仅部署授权代码，没有改变 Broker/transport 会话或清理旧数据。可靠性 e2e 依用户要求未执行，不能计入通过结果。

## 架构同步

已同步 README、CLAUDE.md §1.2/§4、spec、roadmap、授权/API 与演示说明。授权流程 SVG 的 PostgreSQL 框更新为自定义角色与绑定；进程拓扑、模块依赖、组织关系未变化，现有架构 SVG 不另改。
