package dev.conduit.server.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 流水线配置（前缀 conduit.pipeline）。
 *
 * @param enabled       是否启动 PipelineWorker 消费循环
 * @param group         Redis Streams 消费组名
 * @param consumer      本实例在消费组中的消费者名（多实例须各不相同）
 * @param batchSize     每次读取/认领的最大消息数；默认 20000；-1（或任意非正数）仍合法，表示不限，取走当前可读的全部消息。
 *                      吞吐上限约为「批大小 ÷ 1 秒」——每批只写一次 InfluxDB，而写入要等 WAL 刷盘（默认 1 秒），
 *                      20000 的理论上限是 2 万条/秒，远高于实测约 4000 条/秒的排空能力。
 *                      默认不用 -1：它会一次取走流中当前可读的全部消息，而流 MAXLEN 为 100 万条，
 *                      配合容器无内存上限曾导致宿主机满 swap。写入会按 conduit.influx.max-request-bytes
 *                      自动拆分请求，批再大也不会触发 413。
 * @param block         读取新消息时的最长阻塞时间
 * @param claimMinIdle  待确认消息空闲多久后可被认领（崩溃实例遗留的投递）
 * @param claimInterval worker 两次认领扫描的最短间隔
 * @param maxDeliveries 单条消息的最大投递次数，超过则进死信 MAX_DELIVERY_EXCEEDED
 * @param workers       本进程内并发运行的消费循环数，每个是消费组里一个独立消费者（名为 consumer-序号）。
 *                      为什么要并发：每批写 InfluxDB 要等 WAL 刷盘（约 0.5～1 秒），单线程串行时这段时间
 *                      线程纯等、Redis 与 CPU 都闲着。而 InfluxDB 的刷盘是周期性的，同一周期内到达的多个
 *                      写请求会被同一次刷盘一起落盘，所以多批同时在途能把等待摊薄，且每批仍等刷盘后才 ACK，
 *                      持久性不受影响（这是它优于 no_sync 的地方）。缩短刷盘间隔则是负优化——实测会把批
 *                      变小、让每批的固定开销摊不薄，吞吐反降 36%。
 *                      并发后各批完成顺序不再确定，但各下游都不依赖顺序：最新值按测点比较 ts、InfluxDB
 *                      按（序列, ts）幂等、在线状态有 since 守护。多消费者分摊也是消费组的本来用法，
 *                      与将来拆成多个进程是同一套机制。必须为正数。
 */
@ConfigurationProperties(prefix = "conduit.pipeline")
public record PipelineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("conduit-server") String group,
        @DefaultValue("server-1") String consumer,
        @DefaultValue("20000") int batchSize,
        @DefaultValue("1s") Duration block,
        @DefaultValue("60s") Duration claimMinIdle,
        @DefaultValue("10s") Duration claimInterval,
        @DefaultValue("5") int maxDeliveries,
        @DefaultValue("4") int workers) {

    /** 启动时校验：workers 为 0 或负数时没有任何消费循环，消息会静默堆积，必须在启动时就失败。 */
    public PipelineProperties {
        if (workers < 1) {
            throw new IllegalArgumentException("conduit.pipeline.workers must be >= 1, but was " + workers);
        }
    }
}
