package dev.conduit.transport.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EMQX 会话健康判定：按固定间隔采样，对「队列溢出丢弃」「队列逼近容量」「会话进程内存逼近堆限」
 * 「投递停滞」「磁盘持久会话被意外启用」「批大小与 inflight 窗口不匹配」六项给出结论。
 *
 * <p>「投递停滞」是对 2026-10-28 定位的那次故障的兜底：当时 inflight 占满、队列非空、
 * 投递总数永久不变，而所有既有信号（连接、健康检查、日志、丢弃计数）都是绿的。
 * 关闭磁盘持久会话后预期不再发生，一旦复现必须能被发现。
 *
 * <p>只有投递停滞会置为不健康（系统停摆）；其余各项只告警，避免健康检查变得没人信。
 * 采样失败不改变健康结论，只标记 pollFailed——监控本身的抖动不应制造故障。
 */
public class SessionMonitor {

    /** 队列长度占容量多少即预警。 */
    private static final double MQUEUE_WARN_RATIO = 0.8;
    /** 会话进程堆占堆限多少即预警。 */
    private static final double HEAP_WARN_RATIO = 0.7;
    /** Erlang 一个字的字节数。 */
    private static final int BYTES_PER_WORD = 8;

    private static final Logger log = LoggerFactory.getLogger(SessionMonitor.class);

    private final Supplier<SessionSnapshot> source;
    private final int stallSamples;
    private final long heapLimitBytes;
    private final int batchSize;

    private SessionSnapshot previous;
    private int unchangedSendSamples;
    private volatile Verdict verdict = new Verdict(true, "not sampled yet", Map.of());

    /**
     * @param source         采样来源（生产环境为 EMQX REST 客户端）
     * @param stallSamples   投递总数连续多少次采样不变才判定停滞
     * @param heapLimitBytes 配置的单连接堆上限字节数，用于计算内存预警阈值
     * @param batchSize      transport 攒批提交的批大小，必须与 EMQX 的 inflight 窗口相等
     */
    public SessionMonitor(Supplier<SessionSnapshot> source, int stallSamples,
                          long heapLimitBytes, int batchSize) {
        this.source = source;
        this.stallSamples = stallSamples;
        this.heapLimitBytes = heapLimitBytes;
        this.batchSize = batchSize;
    }

    /** 当前结论；从未成功采样时为健康。 */
    public Verdict verdict() {
        return verdict;
    }

    /**
     * 采样一次并更新结论。只有「投递停滞」会把健康状态置为 false；
     * 其余各项是告警——丢弃虽然是真实的数据丢失，但系统仍在正常推进。
     * 采样失败时保留上次结论，仅标记 pollFailed。
     */
    public void sample() {
        SessionSnapshot now;
        try {
            now = source.get();
        } catch (RuntimeException e) {
            // 保留上次结论：EMQX 短暂不可达不等于会话有问题
            Map<String, Object> failed = new LinkedHashMap<>(verdict.details());
            failed.put("pollFailed", true);
            verdict = new Verdict(verdict.healthy(), verdict.reason(), Map.copyOf(failed));
            log.warn("EMQX session poll failed, keeping the previous verdict: {}", e.getMessage());
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mqueueLen", now.mqueueLen());
        details.put("sendMsg", now.sendMsg());

        if (previous != null && now.mqueueDropped() > previous.mqueueDropped()) {
            long delta = now.mqueueDropped() - previous.mqueueDropped();
            details.put("mqueueDroppedDelta", delta);
            log.warn("EMQX dropped {} queued messages (total {}): ingest exceeds delivery capacity",
                    delta, now.mqueueDropped());
        }
        if (now.mqueueMax() > 0 && now.mqueueLen() >= now.mqueueMax() * MQUEUE_WARN_RATIO) {
            details.put("mqueueNearCapacity", true);
            log.warn("EMQX queue at {}/{}: messages will be dropped if it fills",
                    now.mqueueLen(), now.mqueueMax());
        }
        long heapBytes = now.heapWords() * BYTES_PER_WORD;
        if (heapLimitBytes > 0 && heapBytes >= heapLimitBytes * HEAP_WARN_RATIO) {
            details.put("heapNearLimit", true);
            log.warn("EMQX session process heap at {} bytes of a {} byte limit: "
                    + "reduce the queue capacity rather than raising the limit", heapBytes, heapLimitBytes);
        }
        if (now.durable()) {
            details.put("durable", true);
            log.warn("EMQX durable sessions are enabled; this platform expects in-memory sessions");
        }
        // 批大小必须等于 inflight 窗口：大于则批永远攒不满、每批白等满 batch-delay，小于则窗口没用满。
        // 两处配置分属 transport 与 EMQX，很容易被只改一边，所以在这里钉死。
        if (now.inflightMax() > 0 && batchSize != now.inflightMax()) {
            details.put("batchSizeMismatch", now.inflightMax());
            log.warn("transport batch size {} does not match the EMQX inflight window {}: "
                    + "batches never fill (or the window is underused); these two must be kept equal",
                    batchSize, now.inflightMax());
        }

        boolean deliveryBlocked = now.inflightCnt() >= now.inflightMax() && now.mqueueLen() > 0;
        // 统计「投递受阻且 send_msg 相同」的连续采样数（含当前这一次），所以连续 N 次相同采样即计为 N
        if (!deliveryBlocked) {
            unchangedSendSamples = 0;
        } else if (previous != null && now.sendMsg() == previous.sendMsg()) {
            unchangedSendSamples++;
        } else {
            unchangedSendSamples = 1;
        }
        details.put("unchangedSendSamples", unchangedSendSamples);

        previous = now;
        if (unchangedSendSamples >= stallSamples) {
            String reason = "EMQX delivery stalled: inflight window full and send_msg unchanged for "
                    + unchangedSendSamples + " samples";
            log.error("{} (queued {})", reason, now.mqueueLen());
            verdict = new Verdict(false, reason, Map.copyOf(details));
        } else {
            verdict = new Verdict(true, "ok", Map.copyOf(details));
        }
    }

    /**
     * 一次判定的结论。
     *
     * @param healthy false 只在判定投递停滞时出现
     * @param reason  不健康的原因，健康时为 "ok"
     * @param details 写入健康检查明细的观测值
     */
    public record Verdict(boolean healthy, String reason, Map<String, Object> details) {
    }
}
