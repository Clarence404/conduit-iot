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
 * @param batchSize     每次读取/认领的最大消息数；**-1（或任意非正数）表示不限**，取走当前可读的全部消息，默认 -1。
 *                      吞吐上限约为「批大小 ÷ 1 秒」——每批只写一次 InfluxDB，而写入要等 WAL 刷盘（默认 1 秒），
 *                      所以批越大吞吐越高；不限时由实际积压决定。写入会按 conduit.influx.max-request-bytes
 *                      自动拆分请求，批再大也不会触发 413。代价是单批驻留内存随积压增长，
 *                      内存受限时应设一个有界值。
 * @param block         读取新消息时的最长阻塞时间
 * @param claimMinIdle  待确认消息空闲多久后可被认领（崩溃实例遗留的投递）
 * @param claimInterval worker 两次认领扫描的最短间隔
 * @param maxDeliveries 单条消息的最大投递次数，超过则进死信 MAX_DELIVERY_EXCEEDED
 */
@ConfigurationProperties(prefix = "conduit.pipeline")
public record PipelineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("conduit-server") String group,
        @DefaultValue("server-1") String consumer,
        @DefaultValue("-1") int batchSize,
        @DefaultValue("1s") Duration block,
        @DefaultValue("60s") Duration claimMinIdle,
        @DefaultValue("10s") Duration claimInterval,
        @DefaultValue("5") int maxDeliveries) {
}
