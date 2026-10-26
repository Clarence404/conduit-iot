# Conduit 独立部署包

基础服务（PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX，定义在 `../dev/docker-compose.yml`，以 `extends` 引用）+ conduit-server、两个 transport（一主一备）、conduit-web 与演示模拟器。需要 Docker 与 Docker Compose v2。

项目/站点授权采用扩展迁移与分阶段开关：`CONDUIT_AUTHORIZATION_MODE=shadow`（默认观察）、`enforce`（正式执行）、`read-only`（保留范围的只读保护）。启用前备份 PostgreSQL；已依赖范围授权后不能回退旧应用恢复全租户权限。配置和演示初始化见 [授权说明](../../docs/authorization.md)。新功能演示关系数据通过版本化 `deploy/demo/authorization-demo.py` 复现，动态遥测仍由模拟器生成。

EMQX 单连接进程堆上限由 `EMQX_CONNECTION_MAX_HEAP_SIZE` 配置，默认 `256MB`，用于持久会话恢复；仍保留内存保护，不等于每连接预留256MB。已存在 Dashboard/CLI 运行时覆盖配置时，应以 `emqx ctl conf show force_shutdown` 检查实际生效值。调高不会自动修复既有会话的确认序号异常，运维处理须独立验证，不能通过静默清空持久会话掩盖积压丢失。

## 方式一：预编译产物（宿主机无外网）

在开发机上（需 git、JDK 21 上的 Maven、Node.js 22）：

```bash
deploy/package.sh       # 生成 target/conduit-standalone-<短提交>.tar.gz
```

把包复制到 Linux 宿主机后：

```bash
mkdir -p conduit && tar -xzf conduit-standalone-<短提交>.tar.gz -C conduit
cd conduit/deploy/standalone
cp .env.example .env    # 替换全部 change-me-* 口令；BUILD_FROM 保持 prebuilt
docker compose up -d --build
```

宿主机上需已有镜像 `eclipse-temurin:21.0.6_7-jre`、`nginx:1.27.4-alpine` 以及基础服务的镜像（`postgres:16.8`、`redis:7.4.2`、`influxdb:3.12.0-core`、`influxdata/influxdb3-ui:1.11.0`、`emqx/emqx:5.10.5`）。包里只有部署文件与产物，没有源码；`.env` 不在包内。

## 方式二：从源码构建（宿主机能联网）

```bash
git clone <仓库地址> conduit && cd conduit/deploy/standalone
cp .env.example .env    # 替换口令，并设置 BUILD_FROM=source
docker compose up -d --build
```

镜像内用 `maven:3.9.9-eclipse-temurin-21` 与 `node:22.14.0-alpine` 编译，需要能下载 Maven 与 npm 依赖。

## 说明

- 项目名 `conduit`，数据卷与开发环境包 `deploy/dev` 相同（`conduit_pg-data` 等），同一台宿主机上二选一；从开发环境包切换过来时数据沿用。从一个包切换到另一个包时，先在旧包目录执行 `docker compose down`（不加 `-v`），并在新包的 `.env` 中沿用相同的共用口令与密钥（`POSTGRES_PASSWORD`、`INFLUX_TOKEN`、`INFLUX_EXPLORER_SESSION_KEY`、`CONDUIT_INTERNAL_TOKEN`、`EMQX_DASHBOARD_PASSWORD`）。
- 构建上下文是包（或仓库）的根目录，`deploy/dev` 与 `deploy/standalone` 的相对位置不能改变。
- EMQX 的鉴权回调固定为 `http://conduit-server:8080`。
- 浏览器打开 `http://<docker-host>:8090`（`WEB_PORT`），用 `CONDUIT_ADMIN_USERNAME` / `CONDUIT_ADMIN_PASSWORD` 登录；平台管理员用 `CONDUIT_PLATFORM_ADMIN_USERNAME` / `CONDUIT_PLATFORM_ADMIN_PASSWORD`。
- 升级：先删除旧的 `deploy/standalone/artifacts/`，再把新包解压到同一目录（`.env` 不在包内，会保留），然后 `docker compose up -d --build`。
- 更换 InfluxDB token：token 在首次启动时写入库中，之后要改需 `docker compose down`、`docker volume rm conduit_influx3-data`（会丢失时序数据）再启动。
- 预编译前端里「连接信息」显示的 MQTT 端口在打包时确定（`VITE_MQTT_PORT`，默认 1883）；宿主机 `MQTT_PORT` 不是 1883 时，打包前设置 `VITE_MQTT_PORT`。
- 日志在命名卷 `conduit_conduit-logs`（第二个 transport 在其 `transport-2` 子目录）。
