package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionMonitor 的失败与重置语义：连续采样失败置 DOWN、成功后恢复、reset 清空旧状态。 */
class SessionMonitorFailureTest {

    private static final SessionSnapshot OK = new SessionSnapshot(0, 0, 100, 0, 32, 10, 0, false);

    @Test
    void neverSampledMonitorTurnsDownAfterConsecutiveFailures() {
        SessionMonitor m = new SessionMonitor(() -> { throw new IllegalStateException("401"); }, 3, 1L << 28, 32);
        m.sample();
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("pollFailed", true).containsEntry("consecutiveFailures", 2);

        m.sample();

        assertThat(m.verdict().healthy()).isFalse();
        assertThat(m.verdict().reason()).contains("连续 3 次采样失败");
        assertThat(m.verdict().details()).containsEntry("lastSuccessAt", "never");
    }

    @Test
    void successResetsTheFailureCountAndRecordsLastSuccess() {
        boolean[] fail = {true};
        SessionMonitor m = new SessionMonitor(() -> {
            if (fail[0]) throw new IllegalStateException("down");
            return OK;
        }, 2, 1L << 28, 32);
        m.sample();
        m.sample();
        assertThat(m.verdict().healthy()).isFalse();

        fail[0] = false;
        m.sample();

        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("consecutiveFailures", 0).containsKey("lastSuccessAt");
    }

    @Test
    void resetDiscardsPreviousSnapshotAndStallCount() {
        SessionSnapshot stuck = new SessionSnapshot(0, 50, 100, 32, 32, 7, 0, false);
        SessionMonitor m = new SessionMonitor(() -> stuck, 3, 1L << 28, 32);
        m.sample();
        m.sample();
        assertThat(m.verdict().details()).containsEntry("unchangedSendSamples", 2);

        m.reset();
        assertThat(m.verdict().healthy()).isTrue();
        m.sample();

        assertThat(m.verdict().details()).containsEntry("unchangedSendSamples", 1);
    }
}
