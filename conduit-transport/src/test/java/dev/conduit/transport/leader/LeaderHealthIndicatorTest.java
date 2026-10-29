package dev.conduit.transport.leader;

import dev.conduit.transport.leader.LeaderElection.Role;
import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import dev.conduit.transport.monitor.SessionSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LeaderHealthIndicator 单元测试：监控关闭时只反映选主状态；监控健康时 UP 且带 session 明细；
 * 监控判定投递停滞时整体 DOWN 并带 sessionError。
 */
class LeaderHealthIndicatorTest {

    private static LeaderElection election() {
        LeaderElection e = mock(LeaderElection.class);
        when(e.role()).thenReturn(Role.LEADER);
        when(e.instanceId()).thenReturn("inst-1");
        when(e.adaptersConnected()).thenReturn(true);
        return e;
    }

    @Test
    void isUpWithoutMonitor() {
        Health h = new LeaderHealthIndicator(election(), (SessionMonitorGroup) null).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("role", "LEADER").containsEntry("connected", true)
                .doesNotContainKey("session");
    }

    @Test
    void isUpWithSessionDetailsWhenMonitorHealthy() {
        SessionMonitor monitor = new SessionMonitor(
                () -> new SessionSnapshot(0, 0, 100, 0, 32, 10, 0, false), 6, 1L << 28, 32);
        monitor.sample();

        Health h = new LeaderHealthIndicator(election(), monitor).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKey("session").doesNotContainKey("sessionError");
    }

    @Test
    void isDownWithSessionErrorWhenDeliveryStalled() {
        SessionMonitor monitor = new SessionMonitor(
                () -> new SessionSnapshot(0, 50, 100, 32, 32, 7, 0, false), 2, 1L << 28, 32);
        monitor.sample();
        monitor.sample();

        Health h = new LeaderHealthIndicator(election(), monitor).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("sessionError").containsKey("session")
                .containsEntry("role", "LEADER");
    }

    @Test
    void standbyDoesNotReuseStaleVerdict() {
        SessionMonitor monitor = new SessionMonitor(
                () -> new SessionSnapshot(0, 50, 100, 32, 32, 7, 0, false), 2, 1L << 28, 32);
        monitor.sample();
        monitor.sample();
        LeaderElection standby = mock(LeaderElection.class);
        when(standby.role()).thenReturn(Role.STANDBY);
        when(standby.instanceId()).thenReturn("inst-2");

        Health h = new LeaderHealthIndicator(standby, monitor).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).doesNotContainKey("sessionError").containsKey("session");
        assertThat(h.getDetails().get("session").toString()).contains("备实例");
    }
}
