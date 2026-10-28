package dev.conduit.transport.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionMonitor 发布的会话仪表：成功采样后取值正确；失败或不再是主实例时为 NaN，不保留旧值。 */
class SessionMonitorMetricsTest {

    private static final String P = "conduit.transport.session.";

    private static double gauge(MeterRegistry r, String name) {
        return r.get(P + name).gauge().value();
    }

    @Test
    void publishesValuesAfterSuccessfulSample() {
        MeterRegistry reg = new SimpleMeterRegistry();
        SessionMonitor m = new SessionMonitor(
                () -> new SessionSnapshot(7, 500, 100_000, 32, 1024, 39_811, 1_000, false), 6, 1L << 28, 32, reg);
        m.sample();

        assertThat(gauge(reg, "mqueue.len")).isEqualTo(500);
        assertThat(gauge(reg, "mqueue.max")).isEqualTo(100_000);
        assertThat(gauge(reg, "mqueue.dropped")).isEqualTo(7);
        assertThat(gauge(reg, "inflight")).isEqualTo(32);
        assertThat(gauge(reg, "inflight.max")).isEqualTo(1024);
        assertThat(gauge(reg, "sent")).isEqualTo(39_811);
        assertThat(gauge(reg, "heap.bytes")).isEqualTo(8_000);
        assertThat(gauge(reg, "poll.failures")).isEqualTo(0);
    }

    @Test
    void gaugesAreNaNBeforeFirstSample() {
        MeterRegistry reg = new SimpleMeterRegistry();
        new SessionMonitor(() -> { throw new IllegalStateException(); }, 6, 1L << 28, 32, reg);
        assertThat(gauge(reg, "mqueue.len")).isNaN();
        assertThat(gauge(reg, "poll.failures")).isNaN();
    }

    @Test
    void failedSampleTurnsDataGaugesToNaNInsteadOfKeepingStaleValues() {
        MeterRegistry reg = new SimpleMeterRegistry();
        boolean[] fail = {false};
        SessionMonitor m = new SessionMonitor(() -> {
            if (fail[0]) throw new IllegalStateException("down");
            return new SessionSnapshot(0, 500, 100_000, 32, 1024, 100, 1_000, false);
        }, 6, 1L << 28, 32, reg);
        m.sample();
        assertThat(gauge(reg, "mqueue.len")).isEqualTo(500);

        fail[0] = true;
        m.sample();

        assertThat(gauge(reg, "mqueue.len")).isNaN();
        assertThat(gauge(reg, "mqueue.max")).isNaN();
        assertThat(gauge(reg, "mqueue.dropped")).isNaN();
        assertThat(gauge(reg, "inflight")).isNaN();
        assertThat(gauge(reg, "inflight.max")).isNaN();
        assertThat(gauge(reg, "sent")).isNaN();
        assertThat(gauge(reg, "heap.bytes")).isNaN();
        assertThat(gauge(reg, "poll.failures")).isEqualTo(1);

        m.sample();
        assertThat(gauge(reg, "poll.failures")).isEqualTo(2);

        fail[0] = false;
        m.sample();
        assertThat(gauge(reg, "mqueue.len")).isEqualTo(500);
        assertThat(gauge(reg, "poll.failures")).isEqualTo(0);
    }

    @Test
    void resetOnNonLeaderTurnsAllGaugesToNaN() {
        MeterRegistry reg = new SimpleMeterRegistry();
        SessionMonitor m = new SessionMonitor(
                () -> new SessionSnapshot(0, 500, 100_000, 32, 1024, 100, 1_000, false), 6, 1L << 28, 32, reg);
        m.sample();

        m.reset();

        assertThat(gauge(reg, "mqueue.len")).isNaN();
        assertThat(gauge(reg, "sent")).isNaN();
        assertThat(gauge(reg, "poll.failures")).isNaN();
    }
}
