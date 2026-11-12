package dev.conduit.transport.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 读取 EMQX 的 {@code GET /api/v5/nodes}，返回内存占比最高的节点的 {@link NodeMemory}。
 *
 * <p>EMQX 5.10.5 把内存报告为带单位的字符串（如 {@code "1.22G"}），精度只有两位小数，用于水位告警足够。
 * 认证与超时同 {@link EmqxSessionClient}：Basic 认证头只拼装一次，<b>本类不打印任何请求头</b>。
 */
public class EmqxNodeClient implements Supplier<NodeMemory> {

    private final RestClient client;

    /** 按会话监控的同一组配置（地址、API key）构造。 */
    public EmqxNodeClient(SessionMonitorProperties props) {
        String credentials = Base64.getEncoder().encodeToString(
                (props.apiKey() + ":" + props.apiSecret()).getBytes(StandardCharsets.UTF_8));
        // 必须设超时：EMQX 过载时 REST 可能挂起，无超时会让单线程轮询永久阻塞
        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(EmqxSessionClient.TIMEOUT).build());
        http.setReadTimeout(EmqxSessionClient.TIMEOUT);
        this.client = RestClient.builder()
                .requestFactory(http)
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /**
     * 采样一次；HTTP 错误、空列表与解析失败都以异常抛出，由 {@link NodeMemoryMonitor} 转为 pollFailed。
     * 多节点时取占比最高的那个：任一节点内存耗尽都会被系统杀掉。
     */
    @Override
    public NodeMemory get() {
        JsonNode nodes = client.get().uri("/api/v5/nodes").retrieve().body(JsonNode.class);
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("EMQX returned no nodes");
        }
        NodeMemory worst = null;
        for (JsonNode n : nodes) {
            NodeMemory m = new NodeMemory(parseSize(n.path("memory_used").asText()),
                    parseSize(n.path("memory_total").asText()));
            if (worst == null || m.usedRatio() > worst.usedRatio()) {
                worst = m;
            }
        }
        return worst;
    }

    /**
     * 解析 EMQX 的容量字符串：数字后可跟 K / M / G / T（按 1024 进位），无单位即字节。
     *
     * @throws IllegalStateException 为空或无法解析时；字段缺失不能静默当作 0，否则告警永远不会触发
     */
    static long parseSize(String text) {
        String s = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalStateException("empty EMQX memory value");
        }
        int shift = switch (s.charAt(s.length() - 1)) {
            case 'K' -> 10;
            case 'M' -> 20;
            case 'G' -> 30;
            case 'T' -> 40;
            default -> 0;
        };
        String number = shift == 0 ? s : s.substring(0, s.length() - 1);
        try {
            return (long) (Double.parseDouble(number) * (1L << shift));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unparsable EMQX memory value: " + text, e);
        }
    }
}
