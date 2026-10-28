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
        @DefaultValue("5") int maxDeliveries) {
}
