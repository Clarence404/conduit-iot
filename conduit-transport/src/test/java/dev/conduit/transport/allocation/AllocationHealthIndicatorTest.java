package dev.conduit.transport.allocation;

import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AllocationHealthIndicator 单元测试：明细字段固定为 instanceId / held / members / poolSize / connected；
 * 实例在运行且持有的身份全部已连接时 UP（不持有任何身份也是 UP）；会话监控判定投递停滞时整体 DOWN 并带 sessionError。
 */
class AllocationHealthIndicatorTest {

    private static IdentityAllocator allocator(boolean running, boolean allConnected, Integer... held) {
        IdentityAllocator a = mock(IdentityAllocator.class);
        when(a.isRunning()).thenReturn(running);
        when(a.allConnected()).thenReturn(allConnected);
        when(a.held()).thenReturn(new TreeSet<>(Set.of(held)));
        when(a.instanceId()).thenReturn("inst-1");
        when(a.members()).thenReturn(2);
        when(a.poolSize()).thenReturn(64);
        return a;
    }

    private static SessionMonitorGroup monitorGroup(boolean healthy, String reason) {
        SessionMonitor m = mock(SessionMonitor.class);
        when(m.verdict()).thenReturn(new SessionMonitor.Verdict(healthy, reason, Map.of("k", reason)));
        SessionMonitorGroup g = new SessionMonitorGroup(id -> m, null);
        g.sample(Set.of("conduit-transport-3"));
        return g;
    }

    @Test
    void upWithAllocationDetailsWhenEveryHeldIdentityIsConnected() {
        Health h = new AllocationHealthIndicator(allocator(true, true, 3, 7), null).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsOnlyKeys("instanceId", "held", "members", "poolSize", "connected")
                .containsEntry("instanceId", "inst-1").containsEntry("held", 2).containsEntry("members", 2)
                .containsEntry("poolSize", 64).containsEntry("connected", true);
    }

    @Test
    void upWhenHoldingNothing() {
        Health h = new AllocationHealthIndicator(allocator(true, true), null).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("held", 0).containsEntry("connected", true);
    }

    @Test
    void downWhenAHeldIdentityIsNotConnected() {
        Health h = new AllocationHealthIndicator(allocator(true, false, 3), null).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("connected", false);
    }

    @Test
    void downWhenTheAllocatorIsNotRunning() {
        Health h = new AllocationHealthIndicator(allocator(false, true), null).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWithSessionDetailsWhenMonitorHealthy() {
        Health h = new AllocationHealthIndicator(allocator(true, true, 3), monitorGroup(true, "ok")).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKey("session").doesNotContainKey("sessionError");
    }

    @Test
    void downWithSessionErrorWhenDeliveryStalled() {
        Health h = new AllocationHealthIndicator(allocator(true, true, 3),
                monitorGroup(false, "delivery stalled")).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKeys("sessionError", "session", "instanceId", "held", "members",
                "poolSize", "connected");
        assertThat(h.getDetails().get("sessionError").toString()).contains("conduit-transport-3")
                .contains("delivery stalled");
    }
}
