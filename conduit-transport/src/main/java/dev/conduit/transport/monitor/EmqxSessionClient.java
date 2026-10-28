package dev.conduit.transport.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * 读取 EMQX 的 {@code GET /api/v5/clients/{clientid}} 并映射为 {@link SessionSnapshot}。
 * 字段名以 EMQX 5.10.5 实际返回为准；缺字段按 0 / false 处理。
 * 认证用 API key/secret 的 Basic 认证，<b>密钥不得出现在日志中</b>，本类不打印任何请求头。
 */
public class EmqxSessionClient implements Supplier<SessionSnapshot> {

    private final RestClient client;
    private final String clientId;

    /** 按配置构造客户端；Basic 认证头只在此处拼装一次，不落日志。 */
    public EmqxSessionClient(SessionMonitorProperties props, String clientId) {
        this.clientId = clientId;
        String credentials = Base64.getEncoder().encodeToString(
                (props.apiKey() + ":" + props.apiSecret()).getBytes(StandardCharsets.UTF_8));
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /** 采样一次；HTTP 错误与解析失败都以异常抛出，由 SessionMonitor 转为 pollFailed。 */
    @Override
    public SessionSnapshot get() {
        JsonNode n = client.get()
                .uri("/api/v5/clients/{clientId}", clientId)
                .retrieve()
                .body(JsonNode.class);
        if (n == null) {
            throw new IllegalStateException("EMQX returned an empty body for client " + clientId);
        }
        return new SessionSnapshot(
                n.path("mqueue_dropped").asLong(),
                n.path("mqueue_len").asLong(),
                n.path("mqueue_max").asLong(),
                n.path("inflight_cnt").asLong(),
                n.path("inflight_max").asLong(),
                n.path("send_msg").asLong(),
                n.path("heap_size").asLong(),
                n.path("durable").asBoolean(false));
    }
}
