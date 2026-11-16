authentication = [
  {
    mechanism = password_based
    backend = http
    enable = true
    method = post
    url = "__CONDUIT_AUTH_URL__/internal/mqtt/auth"
    body {
      clientid = "${clientid}"
      username = "${username}"
      password = "${password}"
    }
    headers {
      "content-type" = "application/json"
      "X-Conduit-Internal-Token" = "__CONDUIT_INTERNAL_TOKEN__"
    }
    request_timeout = "10s"
  }
]

authorization {
  no_match = deny
  deny_action = ignore
  cache { enable = true }
  sources = [
    {
      type = file
      enable = true
      path = "/opt/emqx/etc/conduit-acl.conf"
    }
  ]
}

sys_topics {
  sys_event_messages {
    client_connected = true
    client_disconnected = true
  }
}

# 只供 transport 使用的内部监听器（2026-11-16，见 docs/superpowers/specs/2026-11-16-conduit-transport-internal-listener-design.md）。
# 所属 zone transport 的信箱与会话队列放宽（compose 环境变量 EMQX_ZONES__TRANSPORT__*），补传洪峰下订阅连接不被强杀；
# 设备仍连 tcp:default（1883）。本监听器有自己的鉴权链：回调多带 X-Conduit-Listener: internal，server 据此只放行 transport，
# 设备连上来一律拒绝（原因 device_on_internal_listener）。该请求头写死在这里，设备无法伪造。
listeners.tcp.internal {
  bind = "0.0.0.0:1884"
  zone = transport
  authentication = [
    {
      mechanism = password_based
      backend = http
      enable = true
      method = post
      url = "__CONDUIT_AUTH_URL__/internal/mqtt/auth"
      body {
        clientid = "${clientid}"
        username = "${username}"
        password = "${password}"
      }
      headers {
        "content-type" = "application/json"
        "X-Conduit-Internal-Token" = "__CONDUIT_INTERNAL_TOKEN__"
        "X-Conduit-Listener" = "internal"
      }
      request_timeout = "10s"
    }
  ]
}
