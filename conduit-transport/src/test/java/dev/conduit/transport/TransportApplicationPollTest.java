package dev.conduit.transport;

import dev.conduit.transport.leader.LeaderElection;
import dev.conduit.transport.leader.LeaderElection.Role;
import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** 单次轮询：主实例采样、非主实例重置，任何异常（含 Error）都不得逸出（否则调度任务会被永久取消）。 */
class TransportApplicationPollTest {

    @Test
    void leaderSamplesAndStandbyResets() {
        LeaderElection election = mock(LeaderElection.class);
        SessionMonitor monitor = mock(SessionMonitor.class);

        when(election.role()).thenReturn(Role.LEADER);
        TransportApplication.pollOnce(election, SessionMonitorGroup.single(monitor));
        verify(monitor).sample();
        verify(monitor, never()).reset();

        when(election.role()).thenReturn(Role.STANDBY);
        TransportApplication.pollOnce(election, SessionMonitorGroup.single(monitor));
        verify(monitor).reset();
    }

    @Test
    void swallowsAnyThrowable() {
        LeaderElection election = mock(LeaderElection.class);
        SessionMonitor monitor = mock(SessionMonitor.class);
        when(election.role()).thenReturn(Role.LEADER);
        doThrow(new NullPointerException("boom")).when(monitor).sample();
        assertThatCode(() -> TransportApplication.pollOnce(election, SessionMonitorGroup.single(monitor))).doesNotThrowAnyException();

        doThrow(new StackOverflowError()).when(monitor).sample();
        assertThatCode(() -> TransportApplication.pollOnce(election, SessionMonitorGroup.single(monitor))).doesNotThrowAnyException();
    }
}
