package dev.conduit.transport.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** NodeMemoryMonitor：EMQX 主机内存高水位告警与仪表。只告警，从不影响健康结论。 */
class NodeMemoryMonitorTest {

    private static final long G = 1L << 30;

    @Test
    void 内存占比达到阈值即告警并发布仪表() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        NodeMemoryMonitor m = new NodeMemoryMonitor(() -> new NodeMemory(7 * G, 8 * G), 0.8, reg);

        m.sample();

        assertThat(m.details()).containsEntry("memoryHigh", true);
        assertThat(reg.find("conduit.transport.emqx.memory.used.ratio").gauge().value()).isEqualTo(0.875);
        assertThat(reg.find("conduit.transport.emqx.memory.used.bytes").gauge().value()).isEqualTo(7.0 * G);
    }

    @Test
    void 低于阈值不告警() {
        NodeMemoryMonitor m = new NodeMemoryMonitor(() -> new NodeMemory(2 * G, 8 * G), 0.8, new SimpleMeterRegistry());
        m.sample();
        assertThat(m.details()).doesNotContainKey("memoryHigh").containsEntry("usedRatio", 0.25);
    }

    @Test
    void 采样失败与重置后仪表置为NaN不停留在旧值() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        boolean[] fail = {false};
        NodeMemoryMonitor m = new NodeMemoryMonitor(() -> {
            if (fail[0]) {
                throw new IllegalStateException("unreachable");
            }
            return new NodeMemory(2 * G, 8 * G);
        }, 0.8, reg);
        m.sample();
        fail[0] = true;
        m.sample();
        assertThat(reg.find("conduit.transport.emqx.memory.used.ratio").gauge().value()).isNaN();
        assertThat(m.details()).containsEntry("pollFailed", true);

        fail[0] = false;
        m.sample();
        m.reset();
        assertThat(reg.find("conduit.transport.emqx.memory.used.ratio").gauge().value()).isNaN();
        assertThat(m.details()).isEmpty();
    }
}
