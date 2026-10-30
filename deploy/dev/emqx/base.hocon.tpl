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
