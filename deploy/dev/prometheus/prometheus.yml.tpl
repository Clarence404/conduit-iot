# Prometheus 抓取配置模板（只读挂载到容器内，启动时由 docker-compose 中 prometheus 的启动命令渲染）。
# 占位符 __EMQX_API_KEY__ / __EMQX_API_SECRET__ 渲染为 .env 中的只读 API key，渲染结果只存在于容器内 /tmp。
# 容器名即主机名（同一 compose 网络）。开发环境包（deploy/dev）里应用跑在 IDE 中，conduit-transport 等目标会显示 DOWN，属正常。
global:
  scrape_interval: 15s
  scrape_timeout: 10s

scrape_configs:
  # conduit-server：/actuator/** 除 health 外仅平台管理员可访问（JWT，15 分钟过期，依赖数据库会话），
  # Prometheus 无法干净地带凭据抓取，也不得为此放开权限，所以本任务保持关闭，待所有者决定方案。
  # 关闭期间「入库/秒」「死信」「流水线拒绝」「server JVM」面板没有数据（入库速率另有 Redis 消费组侧的近似序列）。
  # 方案确定后取消注释并补上鉴权方式：
  # - job_name: conduit-server
  #   metrics_path: /actuator/prometheus
  #   static_configs:
  #     - targets: ["conduit-server:8080"]

  # transport 没有引入 Spring Security，/actuator 端点本就匿名（与既有 health 一致），且端口只在 compose 网络内，不映射到宿主机。
  - job_name: conduit-transport
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["conduit-transport:8081"]
        labels: { instance: conduit-transport }
      - targets: ["conduit-transport-2:8081"]
        labels: { instance: conduit-transport-2 }

  # EMQX 5 自带。该端点在 5.10.5 上是否强制鉴权未能实际验证，这里无论是否需要都带上只读 API key 做 basic auth（不需要时被忽略）。
  - job_name: emqx
    metrics_path: /api/v5/prometheus/stats
    basic_auth:
      username: "__EMQX_API_KEY__"
      password: "__EMQX_API_SECRET__"
    static_configs:
      - targets: ["emqx:18083"]

  - job_name: node
    static_configs:
      - targets: ["node-exporter:9100"]

  - job_name: cadvisor
    static_configs:
      - targets: ["cadvisor:8080"]

  - job_name: redis
    static_configs:
      - targets: ["redis-exporter:9121"]

  # InfluxDB 3 Core 是否提供 Prometheus 端点未能验证，不硬凑；其资源占用由 cadvisor 的容器指标覆盖。
