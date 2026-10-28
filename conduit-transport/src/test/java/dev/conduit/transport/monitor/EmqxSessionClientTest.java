package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;

/** EmqxSessionClient 构造：带超时的请求工厂可正常构建（超时本身依赖真实网络挂起，难以在单测中断言）。 */
class EmqxSessionClientTest {

    @Test
    void buildsWithTimeouts() {
        SessionMonitorProperties props = new SessionMonitorProperties(true, "http://localhost:1", "k", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256));
        assertThatCode(() -> new EmqxSessionClient(props, "c")).doesNotThrowAnyException();
    }
}
