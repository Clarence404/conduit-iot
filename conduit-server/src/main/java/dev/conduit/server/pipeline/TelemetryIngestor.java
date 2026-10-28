package dev.conduit.server.pipeline;

import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodeContext;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 正常消费与遥测重放共用的处理单元（V0.2-R3 spec 4.3）：解码 → 物模型校验 → 写 InfluxDB → 写最新值。
 * 这里不写死信、不 ACK、不更新在线状态——这三件事由调用方决定：流水线三者都做，重放一个都不做。
 * 下游不可用时原样抛出异常，由调用方用 {@link Backoff} 决定重试策略。
 */
@Component
public class TelemetryIngestor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestor.class);

    /**
     * 一条已解码并校验、等待写库的属性消息。
     *
     * @param point    待写入的数据点，fields 只含通过校验的测点
     * @param rejected 被剔除的测点；流水线据此补写 FIELD_REJECTED 记录，重放只是忽略
     */
    public record Prepared(TelemetryPoint point, List<RejectedField> rejected) {
    }

    private final DeviceIdentityLookup identities;
    private final ThingModelCache thingModels;
    private final DecoderRegistry decoders;
    private final TelemetryWriter writer;
    private final LatestValueStore latest;
    private final MeterRegistry meters;

    public TelemetryIngestor(DeviceIdentityLookup identities, ThingModelCache thingModels, DecoderRegistry decoders,
                             TelemetryWriter writer, LatestValueStore latest, MeterRegistry meters) {
        this.identities = identities;
        this.thingModels = thingModels;
        this.decoders = decoders;
        this.writer = writer;
        this.latest = latest;
        this.meters = meters;
    }

    /**
     * 「解码 → 校验」阶段：依次检查设备存在、所属租户已启用、设备已启用、productKey 一致，
     * 取产品物模型与 codec 并解码，再逐测点校验。被剔除的测点计入 conduit.pipeline.rejected_fields 指标（带 source 标签）。
     *
     * @param traceId 仅用于日志关联：流水线传投递 id，重放传流条目 id
     * @param source  调用来源，作为 rejected_fields 指标的 source 标签，使重放不污染实时计数
     * @throws DecodeException UNKNOWN_DEVICE / TENANT_DISABLED / DEVICE_DISABLED / PRODUCT_MISMATCH / UNKNOWN_CODEC、
     *                         解码失败，或全部测点被拒绝（FIELD_REJECTED，details 含被拒详情）
     */
    public Prepared prepare(String traceId, RawMessage message, IngestSource source) {
        DeviceIdentity identity = identities.find(message.deviceKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE, message.deviceKey()));
        return prepare(traceId, message, source, identity);
    }

    /**
     * 与三参版本相同，但身份由调用方提供（流水线按批一次取得，避免每条消息一次 Redis 往返）。
     *
     * @param identity 该消息 deviceKey 对应的设备身份，不能为 null（查不到时调用方应自行按 UNKNOWN_DEVICE 处理）
     */
    public Prepared prepare(String traceId, RawMessage message, IngestSource source, DeviceIdentity identity) {
        if (!identity.tenantActive()) {
            throw new DecodeException(DeadReason.TENANT_DISABLED, message.deviceKey());
        }
        if (!identity.enabled()) {
            throw new DecodeException(DeadReason.DEVICE_DISABLED, message.deviceKey());
        }
        if (!identity.productKey().equals(message.productKey())) {
            throw new DecodeException(DeadReason.PRODUCT_MISMATCH,
                    message.productKey() + " != " + identity.productKey());
        }
        ProductProfile profile = thingModels.find(identity.productKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE,
                        "product not found: " + identity.productKey()));
        PayloadDecoder decoder = decoders.find(profile.codec())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_CODEC, String.valueOf(profile.codec())));
        DecodedPayload decoded = decoder.decode(
                new DecodeContext(identity.productKey(), identity.deviceKey(), message.receivedAt()),
                message.payload());
        log.debug("Decoded {}: productKey={} deviceKey={} codec={} fields={} ts={}", traceId,
                identity.productKey(), identity.deviceKey(), profile.codec(), decoded.values().size(), decoded.ts());
        FieldValidation validation = FieldValidator.validate(profile.properties(), decoded.values());
        logValidation(traceId, identity.deviceKey(), validation);
        validation.rejected().forEach(r ->
                meters.counter("conduit.pipeline.rejected_fields", "reason", r.reason().name(), "source", source.tag()).increment());
        if (validation.accepted().isEmpty()) {
            throw new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected",
                    RejectedFields.details(validation.rejected()));
        }
        TelemetryPoint point = new TelemetryPoint(identity.tenantId(), identity.productKey(), identity.deviceKey(),
                decoded.ts(), validation.accepted());
        return new Prepared(point, validation.rejected());
    }

    /** 批量查设备身份（一次 MGET，未命中回源），供流水线整批预取；查不到的设备不在结果中。 */
    public Map<String, DeviceIdentity> findIdentities(java.util.Collection<String> deviceKeys) {
        return identities.findAll(deviceKeys);
    }

    /**
     * 批量写入时序库。
     *
     * @return 写入结果；其中 rejected 的 index 指向入参列表中被 InfluxDB 拒绝的数据点（通常是字段类型冲突）
     * @throws dev.conduit.server.telemetry.TelemetryUnavailableException 下游不可用（可重试）
     */
    public WriteResult write(List<TelemetryPoint> points) {
        return writer.write(points);
    }

    /** 更新该设备的最新值；Lua 脚本逐测点比较时间戳，重放的旧值不会覆盖更新的值。 */
    public void updateLatest(TelemetryPoint point) {
        latest.update(point.deviceKey(), point.ts(), point.fields());
    }

    /**
     * 批量更新最新值（流水线用）：同一个 Lua 脚本放进一次 pipeline，语义与逐条 {@link #updateLatest} 一致。
     * 调用方须先用 {@link LatestValueStore#validate} 剔除不合格条目。
     */
    public void updateLatestBatch(List<TelemetryPoint> points) {
        latest.updateBatch(points.stream()
                .map(p -> new LatestValueStore.LatestUpdate(p.deviceKey(), p.ts(), p.fields())).toList());
    }

    /**
     * 记录校验结果：DEBUG 一行（通过/剔除数量，有剔除时附字段名与原因，不含值）；TRACE 逐测点列出通过的值（截断 256 字符）。
     */
    private void logValidation(String traceId, String deviceKey, FieldValidation validation) {
        if (log.isDebugEnabled()) {
            String rejectedText = validation.rejected().isEmpty() ? "" : " rejected=" + validation.rejected().stream()
                    .map(r -> r.field() + ":" + r.reason()).collect(Collectors.joining(",", "[", "]"));
            log.debug("Validated {}: deviceKey={} accepted={} rejected={}{}", traceId, deviceKey,
                    validation.accepted().size(), validation.rejected().size(), rejectedText);
        }
        if (log.isTraceEnabled()) {
            Map<String, Object> accepted = validation.accepted();
            accepted.forEach((field, value) -> log.trace("Accepted field {}.{} = {}", deviceKey, field,
                    truncate(String.valueOf(value))));
        }
    }

    /** 把值截断到 256 个字符供 TRACE 日志使用。 */
    private static String truncate(String value) {
        return value.length() <= 256 ? value : value.substring(0, 256) + "...(truncated)";
    }
}
