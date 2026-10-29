package dev.conduit.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 吞吐速率采样器：Micrometer 计数器只有「自进程启动以来的累计值」，算不出速率，
 * 因此本类每 {@link #INTERVAL_SECONDS} 秒把累计值抄一份，用首尾两次采样的差值除以间隔得到速率。
 *
 * <p>语义（与全局遥测约定一致：null 表示没有数据，0 表示真实的零）：
 * <ul>
 *   <li>速率为**最近约 60 秒的平均值**，不是瞬时值；采样不足两次（进程刚启动）时速率为 {@code null}，
 *       不得当成 0——那会把「还不知道」说成「确实没有流量」。</li>
 *   <li>累计值与峰值都是**本进程启动以来**的，server 重启即归零；多实例部署时各实例互不相同。
 *       需要跨重启、跨实例的口径请看 Prometheus，不要用这里的数。</li>
 * </ul>
 *
 * <p>自带一个守护线程定时采样，不启用全局 {@code @EnableScheduling}，避免给整个应用引入调度线程池。
 */
@Component
public class ThroughputSampler {

    /** 采样间隔（秒）。 */
    static final int INTERVAL_SECONDS = 5;

    /** 环形缓冲长度：13 次 × 5 秒，首尾相差 60 秒，正好覆盖 1 分钟窗口。 */
    static final int SAMPLES = 13;

    private static final Logger log = LoggerFactory.getLogger(ThroughputSampler.class);

    private final MeterRegistry meters;
    private final LongSupplier clock;

    /** 环形缓冲：三个并行数组分别存采样时刻（毫秒）、累计入库条数、累计死信条数。 */
    private final long[] atMillis = new long[SAMPLES];
    private final long[] ingested = new long[SAMPLES];
    private final long[] dead = new long[SAMPLES];
    /** 已写入的采样次数；小于 SAMPLES 时缓冲尚未填满。 */
    private int count;
    /** 下一次写入的位置。 */
    private int next;
    /** 本进程启动以来算出过的最高速率；还没算出过任何速率时为 null。 */
    private Double peakTps;

    private ScheduledExecutorService timer;

    public ThroughputSampler(MeterRegistry meters) {
        this(meters, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟，并可直接调用 {@link #sample()} 而不依赖定时线程。 */
    ThroughputSampler(MeterRegistry meters, LongSupplier clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /** 启动定时采样线程（守护线程，不阻止 JVM 退出）。 */
    @PostConstruct
    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "throughput-sampler");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::sampleQuietly, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.debug("Throughput sampler started: interval={}s window={}s", INTERVAL_SECONDS,
                INTERVAL_SECONDS * (SAMPLES - 1));
    }

    /** 停止采样线程。 */
    @PreDestroy
    public void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** 定时线程的入口：采样失败只记日志，绝不能让异常终止定时任务。 */
    private void sampleQuietly() {
        try {
            sample();
        } catch (RuntimeException e) {
            log.warn("Throughput sampling failed: {}", e.toString());
        }
    }

    /** 抄录一次当前累计值。 */
    synchronized void sample() {
        atMillis[next] = clock.getAsLong();
        ingested[next] = total("conduit.pipeline.processed", "type", "property");
        dead[next] = total("conduit.pipeline.dead", null, null);
        next = (next + 1) % SAMPLES;
        if (count < SAMPLES) {
            count++;
        }
        Double tps = computeTps();
        if (tps != null && (peakTps == null || tps > peakTps)) {
            peakTps = tps;
        }
    }

    /**
     * 读取某个计数器的累计值；计数器是惰性注册的，还没被 increment 过时并不存在，
     * 此时累计值就是真实的 0（而不是「未知」）。
     *
     * @param tagKey 为 null 时合计同名计数器的所有标签维度（死信按 reason 分成了多个计数器）
     */
    private long total(String name, String tagKey, String tagValue) {
        if (tagKey == null) {
            double sum = 0;
            for (Counter c : meters.find(name).counters()) {
                sum += c.count();
            }
            return (long) sum;
        }
        Counter c = meters.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0L : (long) c.count();
    }

    /** 用环形缓冲里最旧与最新两次采样算平均速率；不足两次或时间没有推进时返回 null。 */
    private Double computeTps() {
        if (count < 2) {
            return null;
        }
        int newest = (next - 1 + SAMPLES) % SAMPLES;
        int oldest = count < SAMPLES ? 0 : next;
        long elapsed = atMillis[newest] - atMillis[oldest];
        if (elapsed <= 0) {
            return null;
        }
        double delta = ingested[newest] - ingested[oldest];
        return Math.max(0d, delta) * 1000d / elapsed;
    }

    /**
     * 当前吞吐快照。
     *
     * @return 快照；采样不足时 {@code ingestTps} / {@code peakTps} 为 null
     */
    public synchronized ThroughputView snapshot() {
        int newest = count == 0 ? -1 : (next - 1 + SAMPLES) % SAMPLES;
        long ingestTotal = newest < 0 ? 0L : ingested[newest];
        long deadTotal = newest < 0 ? 0L : dead[newest];
        return new ThroughputView(round(computeTps()), round(peakTps), ingestTotal, deadTotal,
                INTERVAL_SECONDS * (SAMPLES - 1));
    }

    /** 速率保留一位小数；null 原样返回（未知不能变成 0）。 */
    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10d) / 10d;
    }

    /**
     * 入库吞吐快照（均为**本进程**启动以来的口径，重启归零）。
     *
     * @param ingestTps     最近 {@code windowSeconds} 秒的平均入库条数/秒；采样不足时为 null
     * @param peakTps       启动以来算出过的最高速率；同上，未知为 null
     * @param ingestTotal   启动以来累计写入时序库的属性消息条数
     * @param deadTotal     启动以来累计死信条数
     * @param windowSeconds 速率的统计窗口长度（秒）
     */
    public record ThroughputView(Double ingestTps, Double peakTps, long ingestTotal, long deadTotal,
                                 int windowSeconds) {
    }
}
