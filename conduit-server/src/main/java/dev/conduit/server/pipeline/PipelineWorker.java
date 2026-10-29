package dev.conduit.server.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.ArrayList;

/**
 * 流水线后台消费线程，随应用生命周期启停（conduit.pipeline.enabled=false 时不创建）。
 * 启动 {@code conduit.pipeline.workers} 条虚拟线程，每条是消费组里一个独立消费者（名为 consumer-序号），
 * 各自循环：定期认领遗留投递，再读取新消息交给 {@link PipelineRunner}；
 * 循环内出现任何异常（如 Redis 不可用）都按 1s→30s 指数退避后继续，成功一轮后退避复位，各线程互不影响。
 * 为什么并发、以及为什么乱序安全，见 {@link PipelineProperties} 的 workers 说明。
 */
@Component
@ConditionalOnProperty(prefix = "conduit.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PipelineWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PipelineWorker.class);
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final PipelineRunner runner;
    private final PipelineProperties props;
    private final Clock clock;

    private volatile boolean running;
    private final List<Thread> threads = new ArrayList<>();

    public PipelineWorker(PipelineRunner runner, PipelineProperties props, Clock clock) {
        this.runner = runner;
        this.props = props;
        this.clock = clock;
    }

    /** 启动 workers 条消费线程，每条一个独立消费者名。 */
    @Override
    public void start() {
        running = true;
        for (int i = 0; i < props.workers(); i++) {
            String consumer = props.consumer() + "-" + i;
            threads.add(Thread.ofVirtual().name("pipeline-worker-" + i).start(() -> loop(consumer)));
        }
        log.info("Pipeline started {} worker(s) as consumers {}-0..{}", props.workers(), props.consumer(),
                props.workers() - 1);
    }

    /**
     * 停止循环：中断全部线程并合计最多等待 5 秒；正在处理的批次若被中断，未 ACK 的投递留待之后认领。
     * 等待时间按全部线程共享一个截止时刻，而不是每条各等 5 秒——否则 workers 越多，停机越慢。
     */
    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (Thread t : threads) {
            long left = (deadline - System.nanoTime()) / 1_000_000;
            if (left <= 0) {
                break;
            }
            try {
                t.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 一个消费者的主循环：每隔 claimInterval 先把待认领的积压全部认领处理完，再读取新消息。
     * 异常时退避重试；线程被中断或 running 变为 false 时退出。
     *
     * @param consumer 本循环在消费组中的消费者名
     */
    private void loop(String consumer) {
        long nextClaimAt = 0;
        long backoff = INITIAL_BACKOFF_MS;
        while (running) {
            try {
                if (clock.millis() >= nextClaimAt) {
                    while (running && runner.claimOnce(consumer) > 0) {
                        // 把积压的待处理消息全部认领完
                    }
                    nextClaimAt = clock.millis() + props.claimInterval().toMillis();
                }
                runner.pollOnce(consumer);
                backoff = INITIAL_BACKOFF_MS;
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("Pipeline iteration failed, retrying in {} ms: {}", backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }
}
