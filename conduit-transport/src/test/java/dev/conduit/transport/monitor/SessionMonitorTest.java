package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionMonitor 的六条判据：丢弃增长、队列水位、会话进程内存、投递停滞、配置漂移、批大小与窗口不匹配。 */
class SessionMonitorTest {

    /** 按给定字段造一份快照。 */
    private static SessionSnapshot snapshot(long dropped, long mqueueLen, long mqueueMax,
                                            long inflight, long inflightMax, long sendMsg,
                                            long heapWords, boolean durable) {
        return new SessionSnapshot(dropped, mqueueLen, mqueueMax, inflight, inflightMax,
                sendMsg, heapWords, durable);
    }

    /** 批大小固定取 32，与快照里的 inflightMax 相等，避免干扰其他判据。 */
    private static SessionMonitor monitorOf(Supplier<SessionSnapshot> source, int stallSamples) {
        return new SessionMonitor(source, stallSamples, 256L * 1024 * 1024, 32);
    }

    @Test
    void healthyWhenNothingIsWrong() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void reportsDroppedMessagesWhenTheCounterGrows() {
        long[] dropped = {0};
        SessionMonitor m = monitorOf(() -> snapshot(dropped[0], 0, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        dropped[0] = 42;
        m.sample();
        // 丢弃是可见的数据丢失，但系统仍在推进：告警而不置 DOWN
        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("mqueueDroppedDelta", 42L);
    }

    @Test
    void reportsStalledDeliveryAfterEnoughUnchangedSamples() {
        // inflight 占满 + 队列非空 + send_msg 连续不变 == 2026-10-28 那次故障的形状
        SessionMonitor m = monitorOf(() -> snapshot(0, 500, 100_000, 32, 32, 39_811, 1_000, false), 3);
        m.sample();
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
        m.sample();
        assertThat(m.verdict().healthy()).isFalse();
        assertThat(m.verdict().reason()).contains("stalled");
    }

    @Test
    void doesNotReportStallWhenDeliveryIsProgressing() {
        long[] sent = {0};
        SessionMonitor m = monitorOf(() -> snapshot(0, 500, 100_000, 32, 32, sent[0] += 100, 1_000, false), 3);
        for (int i = 0; i < 5; i++) {
            m.sample();
        }
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void warnsWhenMqueueIsNearCapacity() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 80_000, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("mqueueNearCapacity", true);
    }

    @Test
    void warnsWhenSessionHeapApproachesTheLimit() {
        // 堆限 256MB，按 70% 告警；heap_size 单位是 Erlang 字（×8 字节）
        long words = (long) (0.75 * 256 * 1024 * 1024 / 8);
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, words, false), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("heapNearLimit", true);
    }

    @Test
    void warnsWhenDurableSessionsAreUnexpectedlyEnabled() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, true), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("durable", true);
    }

    @Test
    void keepsThePreviousVerdictWhenPollingFails() {
        // 监控本身失败不得改变健康状态，否则 EMQX 抖动会制造假故障
        SessionMonitor m = monitorOf(() -> {
            throw new IllegalStateException("unreachable");
        }, 6);
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("pollFailed", true);
    }

    @Test
    void warnsWhenBatchSizeDoesNotMatchTheInflightWindow() {
        // 批大小 64、窗口 32：批永远攒不满，每批白等满 batch-delay
        SessionMonitor m = new SessionMonitor(
                () -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6, 256L * 1024 * 1024, 64);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("batchSizeMismatch", 32L);
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void doesNotWarnWhenBatchSizeMatchesTheInflightWindow() {
        SessionMonitor m = new SessionMonitor(
                () -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6, 256L * 1024 * 1024, 32);
        m.sample();
        assertThat(m.verdict().details()).doesNotContainKey("batchSizeMismatch");
    }
}
