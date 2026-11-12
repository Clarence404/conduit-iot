package dev.conduit.transport.monitor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EmqxNodeClient：按 EMQX 5.10.5 /api/v5/nodes 的真实响应解析内存（字段是带单位的字符串）。 */
class EmqxNodeClientTest {

    @Test
    void 解析带单位的内存字符串() {
        assertThat(EmqxNodeClient.parseSize("1.22G")).isEqualTo((long) (1.22 * (1L << 30)));
        assertThat(EmqxNodeClient.parseSize("512.00M")).isEqualTo(512L << 20);
        assertThat(EmqxNodeClient.parseSize("64K")).isEqualTo(64L << 10);
        assertThat(EmqxNodeClient.parseSize("1024")).isEqualTo(1024L);
        assertThatThrownBy(() -> EmqxNodeClient.parseSize("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EmqxNodeClient.parseSize("abc")).isInstanceOf(IllegalStateException.class);
    }

    /** 返回体取自 2026-11-12 拆分部署 EMQX 5.10.5 的实际响应（只保留用到的字段）；多节点时取占比最高的一个。 */
    @Test
    void 按真实响应结构解析并取占比最高的节点() throws Exception {
        String body = """
                [{"node":"emqx@127.0.0.1","memory_used":"1.22G","memory_total":"7.71G","node_status":"running"},
                 {"node":"emqx@b","memory_used":"6.00G","memory_total":"8.00G","node_status":"running"}]
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/nodes", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            SessionMonitorProperties props = new SessionMonitorProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "k", "s",
                    Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256));
            NodeMemory m = new EmqxNodeClient(props).get();
            assertThat(m.usedBytes()).isEqualTo(6L << 30);
            assertThat(m.totalBytes()).isEqualTo(8L << 30);
            assertThat(m.usedRatio()).isEqualTo(0.75);
        } finally {
            server.stop(0);
        }
    }
}
