# Prometheus 抓取配置模板（只读挂载到容器内，启动时由 docker-compose 中 prometheus 的启动命令渲染）。
# 占位符 __EMQX_API_KEY__ / __EMQX_API_SECRET__ / __CONDUIT_INTERNAL_TOKEN__ 渲染为 .env 中的只读 API key 与内部令牌，渲染结果只存在于容器内 /tmp。
# 容器名即主机名（同一 compose 网络）。开发环境包（deploy/dev）里应用跑在 IDE 中，conduit-transport 等目标会显示 DOWN，属正常。
global:
  scrape_interval: 15s
  scrape_timeout: 10s

scrape_configs:
  # conduit-server：/actuator/** 除 health 外仅平台管理员可访问（JWT 会过期），不能为抓取放开，
  # 所以改抓专用端点 /internal/metrics：由控制器自行校验与 EMQX 鉴权回调相同的内部令牌（请求头 X-Conduit-Internal-Token）。
  # __CONDUIT_INTERNAL_TOKEN__ 在启动时渲染，渲染结果只存在于容器内 /tmp。
  # http_headers 需要 Prometheus 2.55+ / 3.x 才支持，本镜像（v3.5.0）支持与否未能实际验证。
  - job_name: conduit-server
    metrics_path: /internal/metrics
    http_headers:
      X-Conduit-Internal-Token:
        values: ["__CONDUIT_INTERNAL_TOKEN__"]
    static_configs:
      - targets: ["conduit-server:8080"]

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

  # cadvisor 默认不启动（镜像在 gcr.io，部分网络不可达），目标永久 down 会让人习惯性忽略告警，故默认注释。
  # 启用 metrics-cadvisor profile 时必须同时取消下面三行的注释。
  # - job_name: cadvisor
  #   static_configs:
  #     - targets: ["cadvisor:8080"]

  - job_name: redis
    static_configs:
      - targets: ["redis-exporter:9121"]

  # InfluxDB 3 Core 是否提供 Prometheus 端点未能验证，不硬凑；其资源占用由 cadvisor 的容器指标覆盖。
