package dev.conduit.transport.monitor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * EmqxSessionClient：构造，以及按 EMQX 真实响应结构解析各字段。
 *
 * <p>解析测试很要紧：字段名写错时 Jackson 的 path().asLong() 不会报错，而是静默返回 0——
 * 面板上就会多一条「出事时也永远是 0」的曲线，比没有更糟。
 */
class EmqxSessionClientTest {

    @Test
    void buildsWithTimeouts() {
        SessionMonitorProperties props = new SessionMonitorProperties(true, "http://localhost:1", "k", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256));
        assertThatCode(() -> new EmqxSessionClient(props, "c")).doesNotThrowAnyException();
    }

    /**
     * 返回体取自 2026-10-29 在压测机 EMQX 5.10.5 上对 /api/v5/clients/conduit-transport-1 的实际响应（只保留用到的字段，
     * 数值改成互不相同，以便发现字段错位）。
     */
    @Test
    void parsesEveryFieldFromTheRealEmqxResponseShape() throws Exception {
        String body = """
                {"clientid":"conduit-transport-1","mqueue_dropped":7,"mqueue_len":11,"mqueue_max":100000,
                 "inflight_cnt":13,"inflight_max":1024,"send_msg":1256078,"heap_size":10958,"durable":false,
                 "mailbox_len":17,"reductions":839925756}
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/clients/conduit-transport-1", ex -> {
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
            SessionSnapshot s = new EmqxSessionClient(props, "conduit-transport-1").get();

            assertThat(s.mqueueDropped()).isEqualTo(7);
            assertThat(s.mqueueLen()).isEqualTo(11);
            assertThat(s.mqueueMax()).isEqualTo(100000);
            assertThat(s.inflightCnt()).isEqualTo(13);
            assertThat(s.inflightMax()).isEqualTo(1024);
            assertThat(s.sendMsg()).isEqualTo(1256078);
            assertThat(s.heapWords()).isEqualTo(10958);
            assertThat(s.durable()).isFalse();
            assertThat(s.mailboxLen()).isEqualTo(17);
            assertThat(s.reductions()).isEqualTo(839925756L);
        } finally {
            server.stop(0);
        }
    }
}
