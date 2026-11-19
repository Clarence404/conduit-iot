package dev.conduit.transport.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按持有身份的会话监控汇总规则：只采样给定身份，监控按 clientId 惰性创建且只创建一次；
 * 不再持有的身份状态被重置、不再参与健康判定；任一已持有身份停滞即整体不健康；各连接的仪表必须分开。
 */
class SessionMonitorGroupTest {

    private static SessionMonitor monitor(boolean healthy, String reason) {
        SessionMonitor m = mock(SessionMonitor.class);
        when(m.verdict()).thenReturn(new SessionMonitor.Verdict(healthy, reason, Map.of("k", reason)));
        return m;
    }

    /** 由预先准备的 clientId → 监控表充当工厂，并记下工厂被调用的顺序。 */
    private static final class Factory implements Function<String, SessionMonitor> {
        final Map<String, SessionMonitor> monitors = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>();

        Factory with(String clientId, SessionMonitor m) {
            monitors.put(clientId, m);
            return this;
        }

        @Override
        public SessionMonitor apply(String clientId) {
            created.add(clientId);
            SessionMonitor m = monitors.get(clientId);
            if (m == null) {
                throw new AssertionError("unexpected clientId " + clientId);
            }
            return m;
        }
    }

    private static Set<String> ids(String... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }

    @Test
    void 只采样给定身份且监控按需创建一次() {
        SessionMonitor a = monitor(true, "ok");
        SessionMonitor b = monitor(true, "ok");
        Factory factory = new Factory().with("conduit-transport", a).with("conduit-transport-2", b);
        SessionMonitorGroup g = new SessionMonitorGroup(factory, null);

        g.sample(ids("conduit-transport"));
        g.sample(ids("conduit-transport", "conduit-transport-2"));

        verify(a, times(2)).sample();
        verify(b).sample();
        assertThat(factory.created).containsExactly("conduit-transport", "conduit-transport-2");
    }

    @Test
    void 不再持有的身份被重置且其停滞不再拖垮整体() {
        SessionMonitor stalled = monitor(false, "delivery stalled");
        SessionMonitor fine = monitor(true, "ok");
        Factory factory = new Factory().with("conduit-transport-1", stalled).with("conduit-transport-2", fine);
        SessionMonitorGroup g = new SessionMonitorGroup(factory, null);

        g.sample(ids("conduit-transport-1", "conduit-transport-2"));
        assertThat(g.verdict().healthy()).isFalse();

        g.sample(ids("conduit-transport-2"));

        verify(stalled).reset();
        verify(fine, never()).reset();
        SessionMonitor.Verdict v = g.verdict();
        assertThat(v.healthy()).isTrue();
        assertThat(v.details()).containsOnlyKeys("conduit-transport-2");
    }

    @Test
    void 任一已持有身份停滞即整体不健康并指明是哪一条() {
        Factory factory = new Factory().with("conduit-transport", monitor(true, "ok"))
                .with("conduit-transport-1", monitor(false, "delivery stalled"))
                .with("conduit-transport-2", monitor(true, "ok"));
        SessionMonitorGroup g = new SessionMonitorGroup(factory, null);

        g.sample(ids("conduit-transport", "conduit-transport-1", "conduit-transport-2"));
        SessionMonitor.Verdict v = g.verdict();

        assertThat(v.healthy()).isFalse();
        assertThat(v.reason()).contains("conduit-transport-1").contains("delivery stalled");
        assertThat(v.details()).containsKeys("conduit-transport", "conduit-transport-1", "conduit-transport-2");
    }

    @Test
    void 全部健康时整体健康() {
        Factory factory = new Factory().with("conduit-transport", monitor(true, "ok"))
                .with("conduit-transport-1", monitor(true, "ok"));
        SessionMonitorGroup g = new SessionMonitorGroup(factory, null);

        g.sample(ids("conduit-transport", "conduit-transport-1"));

        assertThat(g.verdict().healthy()).isTrue();
        assertThat(g.verdict().reason()).isEqualTo("ok");
    }

    @Test
    void 未持有任何身份时健康且不创建监控() {
        Factory factory = new Factory();
        SessionMonitorGroup g = new SessionMonitorGroup(factory, null);

        g.sample(Set.of());

        assertThat(factory.created).isEmpty();
        assertThat(g.verdict().healthy()).isTrue();
        assertThat(g.verdict().reason()).isEqualTo("ok");
    }

    @Test
    void 一条连接采样抛错不影响其余连接本轮采样() {
        SessionMonitor bad = mock(SessionMonitor.class);
        SessionMonitor good = mock(SessionMonitor.class);
        doThrow(new IllegalStateException("401")).when(bad).sample();
        Factory factory = new Factory().with("conduit-transport", bad).with("conduit-transport-1", good);

        new SessionMonitorGroup(factory, null).sample(ids("conduit-transport", "conduit-transport-1"));

        verify(good).sample();
    }

    @Test
    void 各连接的仪表带client标签分开而不是被合并成一个() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        new SessionMonitor(() -> null, 3, 1L << 28, 64, reg, "conduit-transport");
        new SessionMonitor(() -> null, 3, 1L << 28, 64, reg, "conduit-transport-1");

        assertThat(reg.find("conduit.transport.session.inflight").gauges()).hasSize(2);
        assertThat(reg.find("conduit.transport.session.inflight").tag("client", "conduit-transport-1").gauge())
                .isNotNull();
    }

    @Test
    void 信箱与reductions按连接发布为仪表且只暴露不参与健康判定() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        SessionMonitor m = new SessionMonitor(() -> new SessionSnapshot(0, 0, 100000, 0, 1024, 100, 1000, false,
                9999, 123456789L), 3, 1L << 28, 64, reg, "conduit-transport-2");

        m.sample();

        assertThat(reg.find("conduit.transport.session.mailbox.len").tag("client", "conduit-transport-2").gauge().value())
                .isEqualTo(9999);
        assertThat(reg.find("conduit.transport.session.reductions").tag("client", "conduit-transport-2").gauge().value())
                .isEqualTo(123456789);
        // 信箱已逼近强杀线，但阈值尚未经过载数据标定，此时不得据此把健康置为 DOWN
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void 持有身份时采样EMQX节点内存并写入明细且不影响健康结论_不持有时重置() {
        SessionMonitor m = monitor(true, "ok");
        NodeMemoryMonitor node = mock(NodeMemoryMonitor.class);
        when(node.details()).thenReturn(Map.of("usedRatio", 0.95, "memoryHigh", true));
        SessionMonitorGroup g = new SessionMonitorGroup(new Factory().with("conduit-transport", m), node);

        g.sample(ids("conduit-transport"));
        verify(node).sample();
        SessionMonitor.Verdict v = g.verdict();
        assertThat(v.healthy()).isTrue();
        assertThat(v.details()).containsKey("emqxNode");

        g.sample(Set.of());
        verify(node).reset();
        verify(node, times(1)).sample();
        assertThat(g.verdict().details()).doesNotContainKey("emqxNode");
    }
}
