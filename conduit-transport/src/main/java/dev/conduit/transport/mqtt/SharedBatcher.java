package dev.conduit.transport.mqtt;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.PublishResult;
import dev.conduit.common.message.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 实例级合批提交器：本实例持有的所有 MQTT 连接把收到的消息交到同一个队列，由一条批提交线程按
 * batchSize / batchDelay 合成一批写入总线，写入成功后再按提交顺序逐条回调（适配器在回调里发业务回执、回 PUBACK）。
 *
 * <p>为什么在实例内合批：身份池变大后流量分散到更多连接上，每条连接各自攒的批变小、写 Redis 的次数随之增加
 * （见 2026-11-17 transport 多活设计第 5 节）。合批不改变每条连接的在途上限：各连接的未确认消息仍受 EMQX
 * 给该连接的 max_inflight 窗口限制，所以队列虽不设上限，实际长度不超过「连接数 × 窗口」。
 *
 * <p>至少一次投递的约束：
 * <ul>
 *   <li>回调只在整批写入总线成功之后执行，绝不提前回 PUBACK；</li>
 *   <li>每次写入前（含每次重试前）丢弃所属会话已停止（live 为 false）的条目：不写入、不回调，
 *       消息留在 EMQX 会话里，由接管该身份的连接重新收到；回调前也再查一次，已写入但会话已停止的不回 PUBACK；</li>
 *   <li>写入失败时退避重试，直到全部成功或提交器关闭，不跳过、不进死信（原因见 {@link #publishAllWithRetry}）。</li>
 * </ul>
 *
 * <p>线程模型：{@link #submit} 可被任意线程并发调用；批的组装、写入、重试与回调都在唯一的批提交线程上串行执行，
 * 因此回调顺序就是提交顺序，同一连接的 PUBACK 保持到达顺序。
 */
public final class SharedBatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SharedBatcher.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    /** close 等待批提交线程退出的上限：线程可能卡在一次无响应的总线调用里，不能让关闭跟着卡住。 */
    private static final Duration CLOSE_JOIN_TIMEOUT = Duration.ofSeconds(1);

    /** 一条待提交的消息及其会话有效性判断与提交成功后的回调。 */
    private record Entry(RawMessage message, BooleanSupplier live, Runnable onCommitted) {
    }

    private final MessageBus bus;
    private final int batchSize;
    private final Duration batchDelay;
    private final Duration initialBackoff;
    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Thread loop;
    private volatile boolean closed;

    /**
     * 创建并立即启动批提交线程。
     *
     * @param bus            上行总线
     * @param batchSize      一批的最大条数（≥ 1），攒满即提交
     * @param batchDelay     未攒满时从第一条到达起最多等待的时间（≥ 0），即 PUBACK 的最坏额外延迟
     * @param initialBackoff 写入失败后的首次重试间隔（> 0），之后逐次翻倍，封顶 30 秒
     * @throws IllegalArgumentException 参数越界
     */
    public SharedBatcher(MessageBus bus, int batchSize, Duration batchDelay, Duration initialBackoff) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
        }
        if (batchDelay.isNegative()) {
            throw new IllegalArgumentException("batchDelay must not be negative, got " + batchDelay);
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive, got " + initialBackoff);
        }
        this.bus = bus;
        this.batchSize = batchSize;
        this.batchDelay = batchDelay;
        this.initialBackoff = initialBackoff;
        this.loop = Thread.ofPlatform().name("bus-batcher").daemon().start(this::run);
    }

    /**
     * 提交一条消息，立即返回（线程安全）。提交器已关闭时直接丢弃：不写入也不回调，消息留在 EMQX 等待重投。
     *
     * @param message     要写入总线的消息
     * @param live        所属会话是否仍有效；为 false 的条目在写入前与回调前被丢弃
     * @param onCommitted 写入成功后在批提交线程上按提交顺序调用；抛出的异常只记日志，不影响其他回调
     */
    public void submit(RawMessage message, BooleanSupplier live, Runnable onCommitted) {
        if (closed) {
            log.debug("Bus batcher closed, dropping message of device {} without ack", message.deviceKey());
            return;
        }
        queue.add(new Entry(message, live, onCommitted));
    }

    /**
     * 停止批提交线程：正在攒的批与正在重试的批都放弃、不回调（未 ACK 的消息由 EMQX 重投）。
     * 中断线程以唤醒退避等待，最多等 {@link #CLOSE_JOIN_TIMEOUT}，不会被失败的总线卡住。可重复调用。
     */
    @Override
    public void close() {
        closed = true;
        loop.interrupt();
        try {
            if (!loop.join(CLOSE_JOIN_TIMEOUT)) {
                log.warn("Bus batcher thread did not terminate within {} ms after close",
                        CLOSE_JOIN_TIMEOUT.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
    }

    /** 批提交循环：取一批、写入、回调，直到关闭。意外异常只记 ERROR 并放弃该批（不回调），循环继续。 */
    private void run() {
        while (!closed) {
            List<Entry> batch;
            try {
                batch = nextBatch();
            } catch (InterruptedException e) {
                break; // 只有 close 会中断本线程
            }
            try {
                commit(batch);
            } catch (RuntimeException e) {
                log.error("Bus batcher dropped a batch of {} messages without ack after an unexpected error",
                        batch.size(), e);
            }
        }
        log.debug("Bus batcher stopped");
    }

    /**
     * 阻塞到第一条到达，然后继续收集直到攒满 batchSize 或自第一条起过了 batchDelay。
     *
     * @throws InterruptedException 提交器关闭
     */
    private List<Entry> nextBatch() throws InterruptedException {
        List<Entry> batch = new ArrayList<>(batchSize);
        batch.add(queue.take());
        long deadline = System.nanoTime() + batchDelay.toNanos();
        while (batch.size() < batchSize) {
            queue.drainTo(batch, batchSize - batch.size());
            if (batch.size() >= batchSize) {
                break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Entry next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    /**
     * 写入一批并在全部成功后按提交顺序回调。回调前再次检查 live：已写入但所属会话已停止的条目不回 PUBACK，
     * 由 EMQX 重投给接管者（重复写入由下游按 ts 幂等覆盖，属至少一次语义）。
     */
    private void commit(List<Entry> batch) {
        List<Entry> written = publishAllWithRetry(batch);
        if (written == null) {
            return; // 提交器关闭，一条都不回调
        }
        if (!written.isEmpty()) {
            log.debug("Published batch of {} messages to bus", written.size());
        }
        for (Entry e : written) {
            if (!e.live().getAsBoolean()) {
                continue;
            }
            try {
                e.onCommitted().run();
            } catch (RuntimeException ex) {
                log.warn("Commit callback for device {} failed: {}", e.message().deviceKey(), ex.toString());
            }
        }
    }

    /**
     * 把整批写入总线并重试到全部成功：整体抛异常（下游不可用）与逐条失败都按退避重试，
     * 每轮只重新提交「尚未成功」的条目，已成功的不会重复写入；每轮写入前先丢弃会话已停止的条目。
     *
     * <p>绝不能留下未 ACK 的条目而继续处理后面的：EMQX 的 mqtt.retry_interval 默认为 infinity，
     * 已发出未 ACK 的在途消息不会重传，只在客户端重连时才重投；一条失败后放着不管就永久占住一个 inflight 槽位，
     * 攒满窗口后 EMQX 不再投递，整条接入链路停摆。所以逐条失败也在此阻塞重试，直到成功、条目所属会话停止或提交器关闭。
     *
     * @return 写入成功的条目，按提交顺序（不含写入前已丢弃的条目）；提交器关闭时返回 null
     */
    private List<Entry> publishAllWithRetry(List<Entry> batch) {
        int n = batch.size();
        boolean[] done = new boolean[n];
        List<Integer> remaining = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            remaining.add(i);
        }
        for (int attempt = 0; !closed; attempt++) {
            remaining = remaining.stream().filter(i -> batch.get(i).live().getAsBoolean()).toList();
            if (remaining.isEmpty()) {
                return collectDone(batch, done);
            }
            List<Integer> current = remaining;
            List<RawMessage> messages = current.stream().map(i -> batch.get(i).message()).toList();
            String failure;
            try {
                List<PublishResult> results = bus.publish(messages);
                List<Integer> stillFailing = new ArrayList<>();
                String lastError = null;
                for (int k = 0; k < current.size(); k++) {
                    PublishResult r = results.get(k);
                    if (r.succeeded()) {
                        done[current.get(k)] = true;
                    } else {
                        stillFailing.add(current.get(k));
                        lastError = r.failure().getMessage();
                    }
                }
                remaining = stillFailing;
                if (remaining.isEmpty()) {
                    return collectDone(batch, done);
                }
                failure = lastError;
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
            if (closed) {
                return null;
            }
            Duration delay = backoff(attempt);
            // 不能因失败而跳过 ACK（retry_interval=infinity 下失败条目会永久占住 inflight 槽位并耗尽窗口）
            log.warn("Bus publish of {} of {} messages failed (attempt {}), retrying in {} ms: {}",
                    remaining.size(), n, attempt + 1, delay.toMillis(), failure);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                return null; // 只有 close 会中断本线程
            }
        }
        return null;
    }

    /** 取出已写入成功的条目，保持提交顺序。 */
    private static List<Entry> collectDone(List<Entry> batch, boolean[] done) {
        List<Entry> written = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            if (done[i]) {
                written.add(batch.get(i));
            }
        }
        return written;
    }

    /** 指数退避：initialBackoff * 2^attempt（指数封顶 5），上限 30 秒。 */
    private Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }
}
