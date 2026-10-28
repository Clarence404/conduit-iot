package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionMonitor 的六条判据：丢弃增长、队列水位、会话进程内存、投递停滞、配置漂移、批大小超过窗口。 */
class SessionMonitorTest {

    /** 按给定字段造一份快照。 */
    private static SessionSnapshot snapshot(long dropped, long mqueueLen, long mqueueMax,
                                            long inflight, long inflightMax, long sendMsg,
                                            long heapWords, boolean durable) {
        return new SessionSnapshot(dropped, mqueueLen, mqueueMax, inflight, inflightMax,
                sendMsg, heapWords, durable);
    }

    /** 批大小固定取 32，不大于快照里的 inflightMax，避免干扰其他判据。 */
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
    void warnsWhenSessionHeapApproachesTheActualKillThreshold() {
        // 配置 256MB，EMQX 实际在 1.5 倍即 384MB 处强杀（2026-10-28 实测），按该值的 70% 告警。
        // heap_size 单位是 Erlang 字（×8 字节）
        long words = (long) (0.75 * 1.5 * 256 * 1024 * 1024 / 8);
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, words, false), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("heapNearLimit", true);
    }

    @Test
    void doesNotWarnBelowSeventyPercentOfTheActualKillThreshold() {
        // 按配置值 256MB 算，这个占用已超 70%（会误报）；按实际的 384MB 算则远未到，应当不告警。
        // 这条用例守住「不按配置值、而按实际触发值计算」这个修正
        long words = (long) (0.80 * 256 * 1024 * 1024 / 8);
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, words, false), 6);
        m.sample();
        assertThat(m.verdict().details()).doesNotContainKey("heapNearLimit");
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

    private static SessionMonitor withBatch(int batchSize, long inflightMax) {
        return new SessionMonitor(
                () -> snapshot(0, 0, 100_000, 0, inflightMax, 1_000, 1_000, false), 6, 256L * 1024 * 1024, batchSize);
    }

    @Test
    void warnsOnlyWhenBatchSizeExceedsTheInflightWindow() {
        // 批大小 64、窗口 32：批永远攒不满，每批白等满 batch-delay
        SessionMonitor m = withBatch(64, 32);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("batchSizeExceedsInflight", 32L);
        assertThat(m.verdict().details()).containsEntry("inflightToBatchRatio", 0.5);
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void doesNotWarnWhenWindowIsSeveralTimesTheBatch() {
        // 1024/64 是实测最优配置：不相等恰恰是正确的
        SessionMonitor m = withBatch(64, 1024);
        m.sample();
        assertThat(m.verdict().details()).doesNotContainKey("batchSizeExceedsInflight");
        assertThat(m.verdict().details()).containsEntry("inflightToBatchRatio", 16.0);
    }

    @Test
    void doesNotWarnWhenBatchSizeEqualsTheWindow() {
        // 相等虽是停等（比值 1），但批能攒满，不属于「批大于窗口」的真问题
        SessionMonitor m = withBatch(32, 32);
        m.sample();
        assertThat(m.verdict().details()).doesNotContainKey("batchSizeExceedsInflight");
        assertThat(m.verdict().details()).containsEntry("inflightToBatchRatio", 1.0);
    }
}
