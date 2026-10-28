package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.TelemetryIngestor.Prepared;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 处理一批投递：属性消息交给 {@link TelemetryIngestor}（解码 → 校验 → 写 InfluxDB → 写最新值），本类只负责消费侧的事——
 * 死信、ACK 与在线状态。被剔除的测点另写一条 FIELD_REJECTED 死信记录。不可恢复的错误进死信（并 ACK）；
 * 下游不可用时由 {@link Backoff} 原地无上限退避重试，未处理的投递保持未 ACK，之后由认领机制重新投递。
 * 属性数据落库并 ACK 后，按数据来源批量把设备标记为在线（尽力而为，失败不影响 ACK；规则见 PresenceStore）。
 * 重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    /** 已通过解码与校验、等待批量写库的属性消息：原投递与处理结果。 */
    private record PendingProperty(Delivery delivery, Prepared prepared) {
    }

    private final MessageBus bus;
    private final PipelineProperties props;
    private final TelemetryIngestor ingestor;
    private final PresenceHandler presenceHandler;
    private final PresenceStore presence;
    private final MeterRegistry meters;
    private final Backoff backoff;

    @Autowired
    public PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                             PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters) {
        this(bus, props, ingestor, presenceHandler, presence, meters,
                Backoff.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    /** 可指定退避策略的构造器（供另外两个构造器委托）。 */
    PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                      PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters,
                      Backoff backoff) {
        this.bus = bus;
        this.props = props;
        this.ingestor = ingestor;
        this.presenceHandler = presenceHandler;
        this.presence = presence;
        this.meters = meters;
        this.backoff = backoff;
    }

    /**
     * 测试构造器：沿用提取 TelemetryIngestor 之前的协作者列表，内部自行组装 ingestor 与快速退避，
     * 这样既能单独 mock 各协作者，又不必在测试里重复装配。
     */
    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                      TelemetryWriter writer, LatestValueStore latest, PresenceStore presence,
                      MeterRegistry meters, Duration initialBackoff, Duration maxBackoff) {
        this(bus, props, new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters),
                presenceHandler, presence, meters, Backoff.unlimited(initialBackoff, maxBackoff));
    }

    /**
     * 处理一批投递：逐条分流，在线/离线消息处理后统一 ACK，属性消息攒成一批后统一写库。
     * 每条投递的去向：投递次数超过 maxDeliveries 进 MAX_DELIVERY_EXCEEDED；信封损坏进 MALFORMED_ENVELOPE；
     * EVENT 暂不支持，进 UNSUPPORTED_TYPE；DecodeException 按其原因进死信；其他运行时异常只让该条进 PROCESSING_ERROR，
     * 不影响同批其他消息。
     *
     * @throws ProcessingAbortedException 线程在退避重试中被中断；已读取但未处理的投递保持未 ACK，之后重新认领
     */
    public void process(List<Delivery> batch) {
        log.debug("Processing batch of {} deliveries", batch.size());
        List<String> presenceAcks = new ArrayList<>();
        List<PendingProperty> properties = new ArrayList<>();
        Map<String, DeviceIdentity> known = prefetchIdentities(batch);

        for (Delivery delivery : batch) {
            if (delivery.deliveryCount() > props.maxDeliveries()) {
                dead(delivery, DeadReason.MAX_DELIVERY_EXCEEDED);
                continue;
            }
            RawMessage message;
            try {
                message = delivery.message();
            } catch (IllegalArgumentException e) {
                dead(delivery, DeadReason.MALFORMED_ENVELOPE);
                continue;
            }
            try {
                switch (message.type()) {
                    case PROPERTY -> properties.add(new PendingProperty(delivery,
                            backoff.run("lookup", () -> prepareProperty(delivery, message, known))));
                    case PRESENCE_CONNECTED, PRESENCE_DISCONNECTED -> {
                        log.debug("Presence message {}: deviceKey={} type={}", delivery.id(), message.deviceKey(),
                                message.type());
                        backoff.run("presence", () -> {
                            presenceHandler.handle(message);
                            return null;
                        });
                        presenceAcks.add(delivery.id());
                        meters.counter("conduit.pipeline.processed", "type", "presence").increment();
                    }
                    case EVENT -> dead(delivery, DeadReason.UNSUPPORTED_TYPE);
                }
            } catch (DecodeException e) {
                log.debug("Dead-lettering {}: {} {}", delivery.id(), e.reason(), e.getMessage());
                dead(delivery, e.reason(), e.details());
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure processing delivery {}", delivery.id(), e);
                dead(delivery, DeadReason.PROCESSING_ERROR);
            }
        }

        ack(presenceAcks);
        if (!properties.isEmpty()) {
            writeProperties(properties);
        }
    }

    /**
     * 先扫一遍本批，把需要查身份的 PROPERTY 消息的 deviceKey 去重后一次批量查出（一次 MGET，未命中的才回源数据库），
     * 把每条消息一次 Redis GET 降为每批一次往返。超限、信封损坏等稍后会被死信的投递不参与。
     * 可恢复错误原地退避重试；不可恢复的错误（如某设备的数据库查询永久失败）不能拖垮整批，
     * 此时返回 null，由 {@link #prepareProperty} 退回逐条查询，使失败只影响出错的那一条。
     */
    private Map<String, DeviceIdentity> prefetchIdentities(List<Delivery> batch) {
        Set<String> deviceKeys = new LinkedHashSet<>();
        for (Delivery delivery : batch) {
            if (delivery.deliveryCount() > props.maxDeliveries()) {
                continue;
            }
            try {
                RawMessage message = delivery.message();
                if (message.type() == MessageType.PROPERTY) {
                    deviceKeys.add(message.deviceKey());
                }
            } catch (IllegalArgumentException e) {
                // 信封损坏，主循环会进 MALFORMED_ENVELOPE
            }
        }
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        try {
            return backoff.run("lookup", () -> ingestor.findIdentities(deviceKeys));
        } catch (ProcessingAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Batch identity lookup failed, falling back to per-message lookup: {}", e.toString());
            return null;
        }
    }

    /**
     * 解码并校验一条属性消息：批量取得的身份里没有该设备即 UNKNOWN_DEVICE（与逐条查不到时一致）；
     * known 为 null（批量查询失败）时退回逐条查询。
     */
    private Prepared prepareProperty(Delivery delivery, RawMessage message, Map<String, DeviceIdentity> known) {
        if (known == null) {
            return ingestor.prepare(delivery.id(), message, IngestSource.LIVE);
        }
        DeviceIdentity identity = known.get(message.deviceKey());
        if (identity == null) {
            throw new DecodeException(DeadReason.UNKNOWN_DEVICE, message.deviceKey());
        }
        return ingestor.prepare(delivery.id(), message, IngestSource.LIVE, identity);
    }

    /**
     * 批量写 InfluxDB，再一次提交最新值、补写 FIELD_REJECTED 记录并 ACK。
     * InfluxDB 拒绝的数据点（字段类型冲突）对应的投递进 FIELD_TYPE_CONFLICT；
     * 单条收尾阶段的意外错误只让该条进 PROCESSING_ERROR。其余投递在写库成功后才一次性 ACK（至少一次投递）。
     */
    private void writeProperties(List<PendingProperty> items) {
        List<TelemetryPoint> points = items.stream().map(i -> i.prepared().point()).toList();
        long writeStart = System.nanoTime();
        WriteResult result = backoff.run("influx write", () -> ingestor.write(points));
        log.debug("Influx write: lines={} rejected={} elapsedMs={}", points.size(), result.rejected().size(),
                (System.nanoTime() - writeStart) / 1_000_000);
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());

        // 阶段一：剔除字段类型冲突的；逐条校验最新值参数，不合格的只死信该条，不进批
        List<PendingProperty> candidates = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PendingProperty item = items.get(i);
            if (rejected.contains(i)) {
                dead(item.delivery(), DeadReason.FIELD_TYPE_CONFLICT);
                continue;
            }
            try {
                LatestValueStore.validate(item.prepared().point().fields());
                candidates.add(item);
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }

        // 阶段二：最新值一次 pipeline 提交；批量失败（不可恢复）时退回逐条，使失败只影响出错的那一条
        List<PendingProperty> updated = updateLatest(candidates);

        List<String> acks = new ArrayList<>();
        Map<String, Long> reportedAt = new LinkedHashMap<>();
        for (PendingProperty item : updated) {
            TelemetryPoint p = item.prepared().point();
            try {
                log.debug("Latest values updated: deviceKey={} fields={} ts={}", p.deviceKey(), p.fields().size(),
                        p.ts());
                if (!item.prepared().rejected().isEmpty()) {
                    Map<String, String> details = RejectedFields.details(item.prepared().rejected());
                    // 先留痕再 ACK：两步之间崩溃只会产生重复死信记录，不会丢失
                    backoff.run("dead record", () -> {
                        bus.recordDead(item.delivery(), DeadReason.FIELD_REJECTED.name(), details);
                        return null;
                    });
                }
                acks.add(item.delivery().id());
                reportedAt.merge(p.deviceKey(), item.delivery().message().receivedAt(), Math::max);
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }
        ack(acks);
        markOnline(reportedAt);
        meters.counter("conduit.pipeline.processed", "type", "property").increment(acks.size());
    }

    /**
     * 把一批已校验的属性消息的最新值一次提交（pipeline，可恢复错误原地退避重试）。
     * 若批量提交遇到不可恢复的错误（例如某个 key 类型被污染），退回逐条更新，只让出错的那条进 PROCESSING_ERROR；
     * 脚本按时间戳比较、幂等，批量已执行的部分在逐条重做时不会改变结果。
     *
     * @return 最新值已更新成功的条目（保持原顺序）
     */
    private List<PendingProperty> updateLatest(List<PendingProperty> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<TelemetryPoint> points = candidates.stream().map(i -> i.prepared().point()).toList();
        try {
            backoff.run("latest update batch", () -> {
                ingestor.updateLatestBatch(points);
                return null;
            });
            return candidates;
        } catch (ProcessingAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Batch latest update failed ({} items), falling back to per-item update: {}",
                    candidates.size(), e.toString());
        }
        List<PendingProperty> ok = new ArrayList<>();
        for (PendingProperty item : candidates) {
            TelemetryPoint p = item.prepared().point();
            try {
                backoff.run("latest update", () -> {
                    ingestor.updateLatest(p);
                    return null;
                });
                ok.add(item);
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }
        return ok;
    }

    /**
     * 把本批成功落库并已 ACK 的设备按「数据」来源标记为在线（{@link PresenceStore#applyDataBatch}，一次 pipeline 往返）：
     * 补偿「设备在 transport 订阅之前就已连接、没有收到连接事件」的情况。时间取该设备本批消息的最大 receivedAt
     * （transport 主机时钟，与 EMQX 事件时钟不同源），因此数据<b>不会推进</b>乱序保护用的 since：已在线不变，
     * 离线设备只有数据时间超过断连时间加宽限期才会复活，晚到的断连事件仍然生效。
     * 必须在 ACK 之后执行；这是尽力而为的附带动作，Redis 失败只在每批记一次 WARN，不重试、不影响 ACK。
     * 遥测重放不走这条路径：重放的是旧数据，会把已离线设备误标为在线（spec 4.3）。
     */
    private void markOnline(Map<String, Long> reportedAt) {
        if (reportedAt.isEmpty()) {
            return;
        }
        try {
            presence.applyDataBatch(reportedAt);
        } catch (RuntimeException e) {
            log.warn("Failed to mark {} device(s) online from data (best effort, ignored): {}", reportedAt.size(),
                    e.getMessage());
        }
    }

    /** 批量 ACK（空列表直接返回），暂时性故障原地重试。 */
    private void ack(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        backoff.run("ack", () -> {
            bus.ack(props.group(), ids);
            return null;
        });
        log.debug("Acked {} deliveries", ids.size());
    }

    /**
     * 无详情的死信。DEBUG 记一行；PROCESSING_ERROR 的调用方已记 ERROR（含堆栈），这里不再重复。
     * 带详情的重载由调用方（DecodeException 分支）已记 DEBUG。
     */
    private void dead(Delivery delivery, DeadReason reason) {
        if (reason != DeadReason.PROCESSING_ERROR) {
            log.debug("Dead-lettering {}: {}", delivery.id(), reason);
        }
        dead(delivery, reason, Map.of());
    }

    /** 写死信记录、ACK 原投递并计数 conduit.pipeline.dead；details 写入死信记录。 */
    private void dead(Delivery delivery, DeadReason reason, Map<String, String> details) {
        // 先留痕再 ACK，分两步各自重试：ACK 暂时失败时不会重复写死信记录
        backoff.run("dead letter", () -> {
            bus.recordDead(delivery, reason.name(), details);
            return null;
        });
        ack(List.of(delivery.id()));
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
