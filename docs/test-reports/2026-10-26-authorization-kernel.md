# 项目与站点授权内核测试报告

- 日期：2026-10-26（Asia/Shanghai）。
- 分支：`codex/v0.3b-authorization`。
- 范围：B2 前半段，五个预置角色、三级绑定、SQL 数据范围、OWNER 保护、开关与授权界面；自定义角色、项目成员委派、B3/B4 不在本次完成范围。
- 环境：Windows、Java 21.0.8；Testcontainers 经 SSH Docker 隧道使用 演示 VM 的临时 PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0 Core、EMQX 5.10.5，测试库与演示库分离。

## 实际执行

```powershell
$env:JAVA_HOME='<JDK21目录>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:DOCKER_HOST='tcp://127.0.0.1:23750'
$env:TESTCONTAINERS_HOST_OVERRIDE='<Docker宿主机地址>'
mvn -B verify '-DargLine=-javaagent:<用户目录>\.m2\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar'
```

15:09:49 完整 Maven 回归 BUILD SUCCESS，全部无失败、错误或跳过：

| 模块 | 单元测试 | 集成测试 |
|---|---:|---:|
| common | 9 | 17 |
| transport | 48 | 16 |
| server | 249 | 261 |
| simulator | 82 | 4 |
| 合计 | 388 | 298 |

共 686 项，数目取本次构建日志，不使用可能残留旧 XML 的目录汇总。证据：`target/b2-full-final.log` 和各模块本次 Surefire/Failsafe 输出。

后续补充普通租户管理员不能授予/重置 OWNER 的场景，仅重跑受影响的 AuthorizationIT/AuthorizationReadOnlyIT（同样使用真实数据库），15:31:48 BUILD SUCCESS：common单元9、server单元249、授权专项集成10全部通过，耗时1分27秒，日志 target/b2-authorization-matrix.log；未放宽任何一事务一租户断言或越权断言。

前端 `npm run test`：66 文件、429 项通过；`npm run build`（vue-tsc + Vite）通过。随后调整身份标签与「兼容角色」列，相关 AppLayout/UsersView 26 项通过，构建重新执行。保留已有大 chunk 提示，没有构建错误。证据：`target/b2-ui-final.log`、`target/b2-ui-followup.log`、`target/b2-ui-build-final.log`。

可靠性 E2E：先 `mvn -B -DskipTests install`，设置 `E2E_DEV_HOST=<开发机可达地址>`，执行 `mvn -B verify -Pe2e -pl conduit-e2e`。15:18:48 BUILD SUCCESS：2 单元 + 5 故障场景，均无失败/错误/跳过。临时 server 明确启用 enforce，Broker 的连接堆限制与部署配置均为256MB。五场景覆盖杀 server、杀主 transport、杀全部 transport、正常重启 EMQX、可靠上报中强杀 EMQX；每场景500条，保持原有计数、死信与 pending 判据。证据：`target/b2-e2e.log` 与 `conduit-e2e/target/e2e-logs/20261026-151455/`。

## 权限与迁移判据

- 同一旧 JWT role=TENANT_VIEWER 的账号，项目 A 为 PROJECT_ADMIN、B 为 VIEWER、C 无绑定：分页 total=2，C 不在列表；首页计数=2，死信全租户统计不向项目成员披露。
- A 设备可写、B 设备读取200/写入与重置密钥404，C 详情、归属、最新值与历史均404；设备响应权限与按钮一致。
- 共享产品模板可读，但隐藏设备所属产品的 deviceCount/onlineCount=0；项目/站点角色不能修改共享产品或新建项目。
- SITE OPERATOR 只看到指定站点，不因同项目存在其他站点而扩大范围；设备可写，租户用户管理403。
- 清空显式绑定后，原 access token 下一请求即拒绝，不能恢复旧 viewer 全租户权限；禁用账号下一请求401。
- 停用站点设备只读仍可查看，写入404；租户管理员删除仍有此类设备的产品返回409，防止依赖检查漏掉停用组织后级联误删。
- 跨租户用户绑定404，直接插入跨租户组合外键被数据库拒绝；非法替换整体失败且原绑定保留。
- 最后一个 OWNER 不能撤销；平台代入获得目标租户范围，不能借代入令牌访问平台接口。原事务断言保持不变。
- read-only 下管理员读取允许、写入403，返回给前端的权限剔除写操作。shadow 拒绝差异不改变旧请求结果。未知业务路由失败关闭，MVC 路由登记测试覆盖当前所有业务映射。
- 过滤器正常和异常退出均清理 ThreadLocal；授权对 PostgreSQL 直接回源，无 Redis 权限缓存故障放行路径。
- Flyway V1～V8 在新库执行成功，完整回归包含演示种子、组织约束、租户组合外键与事务边界旧测试。

## 失败记录与修正

1. 初次编译失败：SecurityConfig 外层 lambda 与内层 lambda 参数同名；改为不同参数名，之后编译通过。
2. 前端首轮425项中2项失败：用户表新增「资源权限」按钮后，旧测试仍断言原按钮列表；更新可见操作预期，并补充按设备范围隐藏操作及授权加载失败不能提交空列表的测试，最终429项通过。
3. 首次完整后端回归新增7个授权用例因测试注入的 RequestMappingHandlerMapping 同时匹配业务与 Actuator 两个 Bean 而报错；明确限定业务 Bean 后专项通过，完整 Maven 回归重新执行并全部通过。未修改生产授权以绕过测试。
4. 代码检查发现停用组织过滤不能套用产品删除的依赖查询；将活动组织限制限定在设备/资产操作，并补充产品删除409断言，完整回归通过。

## 演示与架构

- 版本化演示脚本：`deploy/demo/authorization-demo.py`，显式 --demo，仅默认演示租户，通过正式 API 创建客户、A/B/C 项目/站点/资产/设备、物模型和 rbac-demo 账号；已存在记录、密码和授权不覆盖。
- VM 已在 enforce 模式实际执行初始化与 `--verify` 两次，A/B/C 设备 Key 两次完全一致；现有账号与授权未被重置。模拟器重建后，三台演示设备均在线，最后上报距查询时约 5.5～5.7 秒。演示账号 `rbac-demo`：A 可写、B 只读、C 不可见，访问用户管理403。演示密码仅设置在 VM 运行环境与交付消息中，仓库不包含凭据。
- 通过页面检查「系统管理 → 用户 → rbac-demo → 资源权限」，可见 A 项目管理员、B 只读成员两条绑定；「设备接入 → 设备 → 权限演示设备 A → 最新值」显示 Ua/Ia/temp 和「刚刚」上报。账号视角的 A/B/C HTTP 判据由正式 API 执行，未通过更改管理员浏览器会话来代替权限检查。
- 已同步 README、CLAUDE.md §1.2/§4、spec/plan、roadmap、授权说明、部署和接入文档；新增支持亮暗主题的 `docs/images/authorization-flow.svg` 并检查结构、文字基线、间距与箭头端点。
- 检查 architecture.svg、module-dependencies.svg、organization-model.svg：进程拓扑、Maven 模块依赖与组织关系均未改变，保留图源，用新增授权图表达变化；reliable-uplink.svg 的协议和业务回执未变。
- 用户要求的「新功能配套演示与测试报告」已持久写入全局 `%USERPROFILE%/.codex/AGENTS.md` 和项目 CLAUDE.md。

## VM 发布

发布版本 `a8bc225`（功能分支 `codex/v0.3b-authorization`，已推送）。发布前在 VM 做 PostgreSQL 自定义格式备份，并在容器内用 `pg_restore --list` 验证可枚举目录。服务先以 shadow 启动：Flyway 从 V7 升到 V8 成功、健康探针 UP、原管理员可查询实时设备，旧账号 `authz_managed` 均为 false。随后切 enforce 并重建 server；健康探针 UP，演示账号按上文通过。

新 release 构建 server/web、两台 transport 与 simulator。两台 transport 使用新的共同 clientId，停止临时恢复 transport 后，新主正常接管同一新会话；旧会话及其积压未删除。服务器当前配置 enforce、EMQX 热配置 `force_shutdown.max_heap_size=256MB`。新配置文件也设置 256 MB，以便之后重建 Broker 保持同值。固定缺测演示设备与实时演示设备的差别及未恢复的旧会话积压另见 `2026-10-26-telemetry-freshness.md`。

访问：`http://<演示 VM>:8090`；用户名 `rbac-demo`，密码由交付消息提供；菜单「设备接入 → 设备」可见 A/B，「系统管理 → 用户」不可访问。A/B/C 的完整验收数据由 `deploy/demo/authorization-demo.py --demo --verify` 复现；运行需要显式提供演示环境管理员密码与演示账号密码。生产环境不运行该脚本。
