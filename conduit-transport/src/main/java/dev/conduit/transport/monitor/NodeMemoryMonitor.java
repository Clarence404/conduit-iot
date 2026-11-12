package dev.conduit.transport.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * EMQX 主机内存水位告警。
 *
 * <p>为什么需要：2026-11-12 压测到崩溃时，EMQX 会话积压把整台主机内存耗尽，进程被 Linux OOM killer 杀掉两次，
 * 内存中的积压全部丢失；而事前 transport 健康检查一直是 UP、没有任何告警。本类在占比达到阈值时记 WARN，
 * 并发布仪表 {@code conduit.transport.emqx.memory.*} 供看板与告警使用。
 *
 * <p>只告警，<b>从不影响健康结论</b>（与会话监控一致，只有投递停滞置 DOWN）。采样失败或重置后仪表置 NaN，
 * 不停留在旧值上。
 */
public class NodeMemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(NodeMemoryMonitor.class);

    private final Supplier<NodeMemory> source;
    private final double warnRatio;
    private final AtomicReference<Double> used = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> total = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> ratio = new AtomicReference<>(Double.NaN);
    private volatile Map<String, Object> details = Map.of();

    /**
     * @param source    采样来源（生产环境为 {@link EmqxNodeClient}）
     * @param warnRatio 已用占比达到多少即告警，(0, 1]
     * @param registry  仪表注册表
     */
    public NodeMemoryMonitor(Supplier<NodeMemory> source, double warnRatio, MeterRegistry registry) {
        this.source = source;
        this.warnRatio = warnRatio;
        Gauge.builder("conduit.transport.emqx.memory.used.bytes", used, AtomicReference::get).register(registry);
        Gauge.builder("conduit.transport.emqx.memory.total.bytes", total, AtomicReference::get).register(registry);
        Gauge.builder("conduit.transport.emqx.memory.used.ratio", ratio, AtomicReference::get).register(registry);
    }

    /** 采样一次；失败只记 WARN 并清空仪表，不抛出。 */
    public synchronized void sample() {
        NodeMemory m;
        try {
            m = source.get();
        } catch (RuntimeException e) {
            clear();
            details = Map.of("pollFailed", true);
            log.warn("EMQX node memory poll failed: {}", e.getMessage());
            return;
        }
        double r = m.usedRatio();
        used.set((double) m.usedBytes());
        total.set((double) m.totalBytes());
        ratio.set(r);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("usedBytes", m.usedBytes());
        d.put("totalBytes", m.totalBytes());
        d.put("usedRatio", r);
        if (r >= warnRatio) {
            d.put("memoryHigh", true);
            log.warn("EMQX host memory at {}% ({} of {} bytes): session backlog may exhaust memory "
                    + "and get EMQX killed by the OS, losing every queued message",
                    Math.round(r * 100), m.usedBytes(), m.totalBytes());
        }
        details = Map.copyOf(d);
    }

    /** 本实例不再是主实例时调用：清空仪表与明细。 */
    public synchronized void reset() {
        clear();
        details = Map.of();
    }

    /** 最近一次采样的明细，写入健康检查；从未采样或已重置时为空。 */
    public Map<String, Object> details() {
        return details;
    }

    private void clear() {
        used.set(Double.NaN);
        total.set(Double.NaN);
        ratio.set(Double.NaN);
    }
}
