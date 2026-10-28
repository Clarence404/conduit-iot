package dev.conduit.transport.leader;

import dev.conduit.transport.monitor.SessionMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 健康检查组件 leader（/actuator/health 的 components.leader）：details 含 role（LEADER / STANDBY）、
 * instanceId、connected（主实例的协议适配器是否已连接），以及会话监控的 session 明细。
 *
 * <p>投递停滞时整体置为 DOWN 并附 sessionError——2026-10-28 的故障中连接一切正常而消息不再流动，
 * 只看 connected 是发现不了的。备实例不轮询会话，状态恒为 UP。
 */
public class LeaderHealthIndicator implements HealthIndicator {

    private final LeaderElection election;
    private final SessionMonitor monitor;

    /** monitor 为 null 表示会话监控已关闭，此时行为与引入监控之前一致。 */
    public LeaderHealthIndicator(LeaderElection election, SessionMonitor monitor) {
        this.election = election;
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        if (monitor != null) {
            SessionMonitor.Verdict verdict = monitor.verdict();
            if (!verdict.healthy()) {
                builder = Health.down().withDetail("sessionError", verdict.reason());
            }
            builder.withDetail("session", verdict.details());
        }
        return builder
                .withDetail("role", election.role().name())
                .withDetail("instanceId", election.instanceId())
                .withDetail("connected", election.adaptersConnected())
                .build();
    }
}
