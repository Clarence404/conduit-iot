# Conduit 独立部署包

基础服务（PostgreSQL、Redis、InfluxDB 3、InfluxDB 3 Explorer、EMQX，定义在 `../dev/docker-compose.yml`，以 `extends` 引用）+ conduit-server、两个同时工作的 transport（身份池多活）、conduit-web、演示模拟器与 Mailpit 演示收件箱。需要 Docker 与 Docker Compose v2。

邮件邀请需先在 `.env` 设置 `CONDUIT_PUBLIC_URL` 为收件人可打开的 Web 地址。演示邮件可在 `http://<docker-host>:8025` 查看；真实 SMTP 的替换方式、演示脚本与升级后会话行为见[身份治理说明](../../docs/identity-governance.md)。

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

宿主机上需已有镜像 `eclipse-temurin:21.0.6_7-jre`、`nginx:1.27.4-alpine`、`axllent/mailpit:v1.27.4` 以及基础服务的镜像（`postgres:16.8`、`redis:7.4.2`、`influxdb:3.12.0-core`、`influxdata/influxdb3-ui:1.11.0`、`emqx/emqx:5.10.5`）。包里只有部署文件与产物，没有源码；`.env` 不在包内。

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
- 升级：先删除旧的 `deploy/standalone/artifacts/`，再把新包解压到同一目录（`.env` 不在包内，会保留），然后 `docker compose up -d --build`。从 2026-11-19 之前的版本（transport 单活多备）升级时，改按下文「升级到 transport 身份池多活」执行。
- 更换 InfluxDB token：token 在首次启动时写入库中，之后要改需 `docker compose down`、`docker volume rm conduit_influx3-data`（会丢失时序数据）再启动。
- 预编译前端里「连接信息」显示的 MQTT 端口在打包时确定（`VITE_MQTT_PORT`，默认 1883）；宿主机 `MQTT_PORT` 不是 1883 时，打包前设置 `VITE_MQTT_PORT`。
- 日志在命名卷 `conduit_conduit-logs`（第二个 transport 在其 `transport-2` 子目录）。

## 升级到 transport 身份池多活（从 2026-11-19 之前的版本）

新版两个 transport 同时工作，经 Redis 租约分摊 64 个 MQTT 身份（旧版只有主实例连 8 条）。**新旧版本不能混跑**；
新版检测到旧选主键 `conduit:transport:leader` 时不会抢身份（每分钟记一次 ERROR），直到旧实例全部停止且该键过期（TTL 15 秒）；
旧实例全部停止后若 `redis-cli TTL conduit:transport:leader` 返回 -1（没有过期时间），直接 `DEL` 该键。
设计见 [`2026-11-17-conduit-transport-multi-active-design.md`](../../docs/superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md)。
EMQX 要重建，设备会断开重连，选设备流量低的时候做。

1. 改保留下来的 `.env`（`.env` 不随包更新，旧值会一直生效）：
   - `EMQX_TRANSPORT_MAX_MQUEUE_LEN=15000`。**不得沿用旧的 100000**：它是每个会话的上限，旧版 8 个会话合计 80 万条，新版 64 个会话就是 640 万条，按每条 1.9～5.4 KB 约 12～35 GB，积压时会耗尽主机内存。
   - `EMQX_SHARED_SUBSCRIPTION_STRATEGY=round_robin`。
   - 删除 `TRANSPORT_MQTT_CONNECTIONS`，设 `TRANSPORT_POOL_SIZE=64`。
2. 先停掉旧 transport（`docker compose stop conduit-transport conduit-transport-2`），再按上文「说明」中的升级方式换上新包（删除旧的 `artifacts/` 后解压到同一目录）或新检出。

3. 重建 EMQX，让会话按新的队列上限重新创建（会话参数在创建时固定，接管旧会话不会换成新值），然后启动全部服务：

   ```bash
   docker compose up -d --force-recreate emqx
   docker compose up -d --build
   ```

4. 回读：

   ```bash
   docker exec conduit-emqx emqx ctl conf show mqtt  | grep shared_subscription_strategy   # round_robin
   docker exec conduit-emqx emqx ctl conf show zones | grep -A3 max_mqueue_len             # transport zone 为 15000
   docker exec conduit-redis redis-cli GET conduit:transport:pool-size                        # 64
   docker exec conduit-emqx emqx ctl broker stats | grep subscriptions.shared.count            # 64
   for c in conduit-transport-1 conduit-transport-2; do
     docker exec $c bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3' | tail -1
   done   # 两个实例 held 各 32、members 为 2、poolSize 为 64
   ```

调大 K 与估算 EMQX 内存的做法见 [拆分部署说明](../split/README.md) 第 6 节（`redis-cli SET conduit:transport:pool-size <新 K>`，只能调大）。
缩小 K 不支持。改 `EMQX_TRANSPORT_MAX_MQUEUE_LEN` 只对新建的会话生效，需重建 EMQX 才对已有会话生效。

## 回退到 K = 1 或旧版

不能只改配置重启：身份 `conduit-transport-1` … `conduit-transport-63` 的会话是共享订阅组的持久成员，保留 24 小时，
EMQX 会继续把消息分给这些离线成员。回退到 K = 1 时它们各排满 1.5 万条后丢弃，占 EMQX 约 1.8～5.1 GB 内存；
回退到旧版（8 个身份）时身份 8～63 分走 56/64 的上行，这部分不会入库。步骤（被清掉的会话里排队的消息随之丢弃，选流量低的时候做）：

1. `docker compose stop conduit-transport conduit-transport-2`。
2. 清掉多出的会话：重建 EMQX（全部会话丢弃）——回退到旧版时**先**把 `.env` 的 `EMQX_TRANSPORT_MAX_MQUEUE_LEN` 改回 100000（旧版 8 × 10 万），再 `docker compose up -d --force-recreate emqx`，只重建这一次；或只 kick 目标 K 以上的身份（不重建时已有会话的队列上限不变）：

   ```bash
   K=64; FROM=1   # FROM：回退到 K = 1 时为 1，回退到旧版时为 8
   for i in $(seq $FROM $((K - 1))); do docker exec conduit-emqx emqx ctl clients kick conduit-transport-$i; done
   ```

   K = 1 时身份 0 的会话里还留着共享订阅，每条消息会投递两次（写入幂等，但写总线量翻倍）；要避免就一并 kick `conduit-transport` 或重建 EMQX。
3. `docker exec conduit-redis redis-cli DEL conduit:transport:pool-size`（K = 1 会把它写成 1，K 只增不减）。
4. K = 1：`.env` 设 `TRANSPORT_POOL_SIZE=1` 后 `docker compose up -d`。旧版：换回旧包与旧 `.env` 再启动；队列上限已在第 2 步处理，这里不要再重建 EMQX（会再丢一次会话与积压）。
5. 回读：`docker exec conduit-emqx emqx ctl clients list | grep -c conduit-transport` 为目标身份数（K = 1 为 1、旧版为 8）。
