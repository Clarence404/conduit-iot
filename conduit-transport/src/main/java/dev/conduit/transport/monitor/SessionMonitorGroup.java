package dev.conduit.transport.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主实例各条连接的会话监控：采样与重置下发给每条连接的 {@link SessionMonitor}，健康结论取最差的那条。
 *
 * <p>多连接共享订阅下，任一条连接投递停滞都意味着约 1/K 的流量滞留在它的会话里（延迟而非丢失，
 * 但积满会话队列后就会丢），所以只要有一条不健康，整体即判为不健康。
 *
 * <p>单连接时原样返回唯一成员的结论，健康检查的输出与引入多连接之前逐字一致。
 */
public final class SessionMonitorGroup {

    private static final Logger log = LoggerFactory.getLogger(SessionMonitorGroup.class);

    /** clientId → 该连接的监控；保持插入顺序，健康明细按连接序号展示。 */
    private final Map<String, SessionMonitor> members;

    /** @param members clientId → 监控，至少一个；按连接序号插入 */
    public SessionMonitorGroup(Map<String, SessionMonitor> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("SessionMonitorGroup needs at least one member");
        }
        this.members = new LinkedHashMap<>(members);
    }

    /** 单连接部署：只盯一个会话。 */
    public static SessionMonitorGroup single(SessionMonitor monitor) {
        return new SessionMonitorGroup(Map.of("", monitor));
    }

    /**
     * 让每条连接各采样一次。逐个捕获异常：一条连接的采样失败不能让其余连接本轮漏采，
     * 否则一条连接的 REST 故障会把所有连接的停滞判定一起拖延。
     */
    public void sample() {
        members.forEach((client, m) -> {
            try {
                m.sample();
            } catch (RuntimeException e) {
                log.warn("Session monitor sample failed for {}: {}", client, e.toString());
            }
        });
    }

    /** 重置全部成员（本实例转为备实例时调用，过期结论不能沿用）。 */
    public void reset() {
        members.values().forEach(SessionMonitor::reset);
    }

    /**
     * 汇总结论：全部健康才健康；不健康时原因逐条列出所属连接，明细按连接分组。
     * 单连接时原样返回其结论。
     */
    public SessionMonitor.Verdict verdict() {
        if (members.size() == 1) {
            return members.values().iterator().next().verdict();
        }
        boolean healthy = true;
        List<String> reasons = new java.util.ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        for (var e : members.entrySet()) {
            SessionMonitor.Verdict v = e.getValue().verdict();
            details.put(e.getKey(), v.details());
            if (!v.healthy()) {
                healthy = false;
                reasons.add(e.getKey() + ": " + v.reason());
            }
        }
        // 与单个监控的约定一致：健康时原因为 "ok" 而不是 null
        return new SessionMonitor.Verdict(healthy, healthy ? "ok" : String.join("; ", reasons), details);
    }
}
