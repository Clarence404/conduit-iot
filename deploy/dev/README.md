# Conduit 开发环境包（deploy/dev）

PostgreSQL 16.8、Redis 7.4.2、InfluxDB 3.12.0-core、InfluxDB 3 Explorer 1.11.0、EMQX 5.10.5（内存持久会话）。应用在开发机的 IDE 中运行（见仓库 README「开发」）。

Redis 使用 AOF 和 `appendfsync always`，并显式保持 `no-appendfsync-on-rewrite no`，写命令回复前执行刷盘。默认 Broker 镜像是 5.10.5；其持久化仍有异步缓冲，不能据此承诺 Broker 崩溃不丢已确认消息。故障验证与性能记录见 [`docs/durability-validation.md`](../../docs/durability-validation.md)。

## 启动

把本目录复制或 clone 到 Linux 宿主机（需 Docker 与 Docker Compose v2），在本目录中：

```bash
cp .env.example .env    # 替换全部 change-me-* 口令，CONDUIT_AUTH_URL 改为 http://<dev-host>:8080
docker compose up -d
docker compose ps       # 5 个服务均为 running，其中 4 个 healthy（Explorer 无健康检查）
```

- 项目名 `conduit`，数据卷 `conduit_pg-data`、`conduit_redis-data`、`conduit_influx3-data`、`conduit_influx-explorer-data`、`conduit_emqx-data`。与独立部署包 `deploy/standalone` 的项目名和数据卷相同，同一台宿主机上二选一。从一个包切换到另一个包时，先在旧包目录执行 `docker compose down`（不加 `-v`），并在新包的 `.env` 中沿用相同的共用口令与密钥（`POSTGRES_PASSWORD`、`INFLUX_TOKEN`、`INFLUX_EXPLORER_SESSION_KEY`、`CONDUIT_INTERNAL_TOKEN`、`EMQX_DASHBOARD_PASSWORD`）。
- `CONDUIT_AUTH_URL` 必填：EMQX 把设备与 transport 的连接鉴权回调到这个地址上的 conduit-server。修改后执行 `docker compose up -d --force-recreate emqx`。
- `CONDUIT_INTERNAL_TOKEN` 必须与开发机上 server 的 `conduit.internal.token` 一致。
- `emqx/base.hocon.tpl` 与 `emqx/acl.conf` 以只读方式挂载进 EMQX 容器，需对容器内的 emqx 用户（uid 1000）可读（普通 644 权限即可）。

## 从旧的开发基础设施迁移

旧的 `deploy/docker-compose.yml` 可能是在开发机上用 `DOCKER_HOST` 加 `docker compose -f deploy/docker-compose.yml` 启动的，也可能是 1Panel 从宿主机上的 `/opt/conduit` 启动的。项目名与数据卷不变，数据沿用：

1. 不依赖任何文件，按项目名停止旧项目：`docker compose -p conduit down`（绝不加 `-v`）。
2. 旧 `.env` 在开发机的 `deploy/.env` 或宿主机的 `/opt/conduit/.env`。复制为本目录的 `.env`（多余的变量会被忽略），下列值必须原样保留：`POSTGRES_PASSWORD`、`INFLUX_TOKEN`、`INFLUX_EXPLORER_SESSION_KEY`、`CONDUIT_INTERNAL_TOKEN`、`EMQX_DASHBOARD_PASSWORD`，以及各端口与 `CONDUIT_AUTH_URL`。PostgreSQL 与 InfluxDB 只在数据卷首次启动时应用口令与 token，所以换成新值会导致已有数据无法访问。确认 `CONDUIT_AUTH_URL` 指向开发机。
3. 1Panel 用户：先删除旧编排（不要删除数据卷），再导入本目录。
4. 在本目录执行 `docker compose up -d`，然后用 `docker volume ls` 检查没有出现新的 `conduit_*` 数据卷。
5. 旧的自定义 EMQX 镜像 `conduit-emqx` 不再使用，可以删除。
