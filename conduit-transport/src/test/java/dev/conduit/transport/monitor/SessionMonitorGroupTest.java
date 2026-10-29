package dev.conduit.transport.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 多连接会话监控的汇总规则：任一连接不健康即整体不健康，且各连接的仪表必须分开。 */
class SessionMonitorGroupTest {

    private static SessionMonitor monitor(boolean healthy, String reason) {
        SessionMonitor m = mock(SessionMonitor.class);
        when(m.verdict()).thenReturn(new SessionMonitor.Verdict(healthy, reason, Map.of("k", reason)));
        return m;
    }

    private static SessionMonitorGroup group(SessionMonitor... ms) {
        Map<String, SessionMonitor> members = new LinkedHashMap<>();
        for (int i = 0; i < ms.length; i++) {
            members.put("conduit-transport" + (i == 0 ? "" : "-" + i), ms[i]);
        }
        return new SessionMonitorGroup(members);
    }

    @Test
    void 任一连接停滞即整体不健康并指明是哪一条() {
        SessionMonitor.Verdict v = group(monitor(true, "ok"), monitor(false, "delivery stalled"),
                monitor(true, "ok")).verdict();

        assertThat(v.healthy()).isFalse();
        assertThat(v.reason()).contains("conduit-transport-1").contains("delivery stalled");
        assertThat(v.details()).containsKeys("conduit-transport", "conduit-transport-1", "conduit-transport-2");
    }

    @Test
    void 全部健康时整体健康() {
        SessionMonitor.Verdict v = group(monitor(true, "ok"), monitor(true, "ok")).verdict();
        assertThat(v.healthy()).isTrue();
        assertThat(v.reason()).isEqualTo("ok");
    }

    @Test
    void 单连接时原样返回唯一成员的结论() {
        SessionMonitor.Verdict inner = new SessionMonitor.Verdict(false, "delivery stalled", Map.of("x", 1));
        SessionMonitor m = mock(SessionMonitor.class);
        when(m.verdict()).thenReturn(inner);

        assertThat(SessionMonitorGroup.single(m).verdict()).isSameAs(inner);
    }

    @Test
    void 一条连接采样抛错不影响其余连接本轮采样() {
        SessionMonitor bad = mock(SessionMonitor.class);
        SessionMonitor good = mock(SessionMonitor.class);
        doThrow(new IllegalStateException("401")).when(bad).sample();

        group(bad, good).sample();

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
}
