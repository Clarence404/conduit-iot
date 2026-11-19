package dev.conduit.transport.allocation;

import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 健康检查组件 allocation（/actuator/health 的 components.allocation）：details 含 instanceId、
 * held（本实例持有的身份数）、members（存活实例数 N）、poolSize（身份池大小 K）、connected（持有的身份是否都已连上），
 * 会话监控开启时另有 session 明细。
 *
 * <p>UP 的条件：分配器在运行，且持有的身份全部已连接。未持有任何身份也是 UP——实例数多于 K 时的空闲实例、
 * 或刚启动尚未分到身份的实例都是正常状态。会话监控判定投递停滞时整体 DOWN 并附 sessionError：2026-10-28 的故障中
 * 连接一切正常而消息不再流动，只看 connected 是发现不了的；该判定只针对本实例持有的身份（见 {@link SessionMonitorGroup}）。
 */
public class AllocationHealthIndicator implements HealthIndicator {

    private final IdentityAllocator allocator;
    private final SessionMonitorGroup monitor;

    /**
     * @param allocator 身份池分配器
     * @param monitor   本实例持有身份的会话监控；为 null 表示会话监控已关闭，只看分配与连接状态
     */
    public AllocationHealthIndicator(IdentityAllocator allocator, SessionMonitorGroup monitor) {
        this.allocator = allocator;
        this.monitor = monitor;
    }

    /** 汇总分配状态与会话监控结论，规则见类注释。 */
    @Override
    public Health health() {
        boolean connected = allocator.allConnected();
        Health.Builder builder = allocator.isRunning() && connected ? Health.up() : Health.down();
        if (monitor != null) {
            SessionMonitor.Verdict verdict = monitor.verdict();
            if (!verdict.healthy()) {
                builder.down().withDetail("sessionError", verdict.reason());
            }
            builder.withDetail("session", verdict.details());
        }
        return builder
                .withDetail("instanceId", allocator.instanceId())
                .withDetail("held", allocator.held().size())
                .withDetail("members", allocator.members())
                .withDetail("poolSize", allocator.poolSize())
                .withDetail("connected", connected)
                .build();
    }
}
