package dev.conduit.transport.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 本实例持有的各个 MQTT 身份的会话监控：每次轮询只采样给定（本实例当前持有）的身份，健康结论取其中最差的那条。
 *
 * <p>身份池可在运行中调大，身份也会在实例间迁移，所以每个 clientId 的 {@link SessionMonitor} 在第一次被采样时才创建，
 * 之后一直留存复用（仪表在创建时注册，不能重复注册）。不在本轮集合中的身份立即重置：其会话已归其他实例或无人持有，
 * 本实例读到的不再是自己的会话，过期结论不能沿用，被让出的停滞身份也不能继续把本实例判为 DOWN。
 *
 * <p>共享订阅下任一条连接投递停滞都意味着约 1/K 的流量滞留在它的会话里（延迟而非丢失，但积满会话队列后就会丢），
 * 所以只要已持有的身份中有一条不健康，整体即判为不健康。
 *
 * <p>可选附带 EMQX 主机内存监控 {@link NodeMemoryMonitor}：持有至少一个身份时随成员一起采样，未持有任何身份时重置；
 * 明细放在 {@code emqxNode} 键下，只告警、不参与健康判定。
 *
 * <p>线程：采样只在会话监控调度线程上进行；健康检查线程只读 {@link #verdict()}，读到的是最近一次采样的集合。
 */
public final class SessionMonitorGroup {

    private static final Logger log = LoggerFactory.getLogger(SessionMonitorGroup.class);

    /** clientId → 会话监控的工厂，每个 clientId 只调用一次。 */
    private final Function<String, SessionMonitor> factory;
    /** 已创建过的全部监控（含已不再持有的身份），按创建顺序。只由采样线程修改。 */
    private final Map<String, SessionMonitor> monitors = new LinkedHashMap<>();
    /** EMQX 主机内存监控，可为 null。 */
    private final NodeMemoryMonitor nodeMemory;
    /** 最近一次采样的身份及其监控，按调用方给定的顺序；整体替换，供健康检查线程读取。 */
    private volatile Map<String, SessionMonitor> active = Map.of();

    /**
     * @param factory    按 clientId 创建会话监控，每个 clientId 只调用一次
     * @param nodeMemory EMQX 主机内存监控，为 null 时不采样
     */
    public SessionMonitorGroup(Function<String, SessionMonitor> factory, NodeMemoryMonitor nodeMemory) {
        this.factory = factory;
        this.nodeMemory = nodeMemory;
    }

    /**
     * 只采样给定身份：首次出现的 clientId 先创建监控；上一轮采样过而本轮不在集合中的身份重置。
     * 逐个捕获运行时异常：一条连接的采样失败不能让其余连接本轮漏采，否则一条连接的 REST 故障会把所有连接的停滞判定一起拖延。
     * 集合为空（未持有任何身份）时重置节点内存监控，否则采样它。
     *
     * @param clientIds 本实例当前持有的身份的 clientId，按身份序号排列（决定健康明细的展示顺序）
     */
    public synchronized void sample(Set<String> clientIds) {
        Map<String, SessionMonitor> next = new LinkedHashMap<>();
        for (String client : new LinkedHashSet<>(clientIds)) {
            next.put(client, monitors.computeIfAbsent(client, factory));
        }
        for (var e : active.entrySet()) {
            if (!next.containsKey(e.getKey())) {
                e.getValue().reset();
            }
        }
        active = next;
        next.forEach((client, m) -> {
            try {
                m.sample();
            } catch (RuntimeException e) {
                log.warn("Session monitor sample failed for {}: {}", client, e.toString());
            }
        });
        if (nodeMemory != null) {
            if (next.isEmpty()) {
                nodeMemory.reset();
            } else {
                nodeMemory.sample();
            }
        }
    }

    /**
     * 汇总最近一次采样的身份：全部健康才健康（没有持有身份时也健康）；不健康时原因逐条列出所属 clientId，
     * 明细按 clientId 分组；持有身份且有节点内存监控时在明细中加 {@code emqxNode}。
     */
    public SessionMonitor.Verdict verdict() {
        Map<String, SessionMonitor> current = active;
        boolean healthy = true;
        List<String> reasons = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        for (var e : current.entrySet()) {
            SessionMonitor.Verdict v = e.getValue().verdict();
            details.put(e.getKey(), v.details());
            if (!v.healthy()) {
                healthy = false;
                reasons.add(e.getKey() + ": " + v.reason());
            }
        }
        if (nodeMemory != null && !current.isEmpty()) {
            details.put("emqxNode", nodeMemory.details());
        }
        // 与单个监控的约定一致：健康时原因为 "ok" 而不是 null
        return new SessionMonitor.Verdict(healthy, healthy ? "ok" : String.join("; ", reasons), details);
    }
}
