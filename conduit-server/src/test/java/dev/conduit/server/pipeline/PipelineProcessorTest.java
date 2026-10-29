package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryUnavailableException;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineProcessorTest {

    private static final String GROUP = "g";

    MessageBus bus = mock(MessageBus.class);
    DeviceIdentityLookup identities = mock(DeviceIdentityLookup.class);
    ThingModelCache thingModels = mock(ThingModelCache.class);
    DecoderRegistry decoders = mock(DecoderRegistry.class);
    PayloadDecoder decoder = mock(PayloadDecoder.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PresenceHandler presenceHandler = mock(PresenceHandler.class);
    TelemetryWriter writer = mock(TelemetryWriter.class);
    LatestValueStore latest = mock(LatestValueStore.class);
    PresenceStore presence = mock(PresenceStore.class);
    PipelineProcessor processor;

    @BeforeEach
    void setUp() {
        PipelineProperties props = new PipelineProperties(true, GROUP, "c", 100,
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(10), 5, 1);
        processor = new PipelineProcessor(bus, props, identities, thingModels, decoders, presenceHandler, writer,
                latest, presence, meters, Duration.ofMillis(1), Duration.ofMillis(5));
        when(thingModels.find("pk")).thenReturn(Optional.of(new ProductProfile("standard-json", Map.of())));
        when(decoders.find("standard-json")).thenReturn(Optional.of(decoder));
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1L, Map.of("Ua", 1.0)));
        when(writer.write(anyList())).thenReturn(WriteResult.ok());
        // 批量查身份默认委托给逐条 find 的桩，使各用例仍可用 identities.find 描述设备
        when(identities.findAll(any())).thenAnswer(inv -> {
            Map<String, DeviceIdentity> found = new java.util.HashMap<>();
            for (String dk : inv.<java.util.Collection<String>>getArgument(0)) {
                identities.find(dk).ifPresent(i -> found.put(dk, i));
            }
            return found;
        });
    }

    private void useModel(PropertySpec... specs) {
        Map<String, PropertySpec> model = new java.util.LinkedHashMap<>();
        for (PropertySpec s : specs) {
            model.put(s.identifier(), s);
        }
        when(thingModels.find("pk")).thenReturn(Optional.of(new ProductProfile("standard-json", model)));
    }

    private static PropertySpec doubleSpec(String id) {
        return new PropertySpec(id, id, DataType.DOUBLE, null, null, null, null, Map.of());
    }

    private void decodes(Map<String, Object> values) {
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1L, values));
    }

    /** 整条消息进死信：先写死信记录（无 details）再 ACK。 */
    private void verifyDeadLettered(Delivery d, String reason) {
        InOrder order = inOrder(bus);
        order.verify(bus).recordDead(d, reason, Map.of());
        order.verify(bus).ack(GROUP, List.of(d.id()));
    }

    private double counter(String name, String reason) {
        var c = meters.find(name).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private DeviceIdentity identity(String dk) {
        return new DeviceIdentity(dk, 1L, "ENABLED", "h", "s", "pk", "ACTIVE");
    }

    private Delivery delivery(String id, String dk) {
        RawMessage m = new RawMessage("pk", dk, MessageType.PROPERTY, "{}".getBytes(StandardCharsets.UTF_8), 1L, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 1);
    }

    private Delivery deliveryAt(String id, String dk, long receivedAt) {
        RawMessage m = new RawMessage("pk", dk, MessageType.PROPERTY, "{}".getBytes(StandardCharsets.UTF_8),
                receivedAt, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 1);
    }

    @Test
    void marksDevicesOnlineInOneBatchCallWithLatestReceivedAtPerDevice() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(identities.find("d2")).thenReturn(Optional.of(identity("d2")));

        processor.process(List.of(deliveryAt("1-0", "d1", 100), deliveryAt("2-0", "d1", 300),
                deliveryAt("3-0", "d2", 200)));

        verify(presence, times(1)).applyDataBatch(Map.of("d1", 300L, "d2", 200L));
    }

    @Test
    void marksOnlineOnlyAfterAck() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));

        processor.process(List.of(deliveryAt("1-0", "d1", 100)));

        InOrder order = inOrder(bus, presence);
        order.verify(bus).ack(GROUP, List.of("1-0"));
        order.verify(presence).applyDataBatch(Map.of("d1", 100L));
    }

    @Test
    void doesNotMarkOnlineWhenMessageIsDeadLettered() {
        when(identities.find("d1")).thenReturn(Optional.empty());

        processor.process(List.of(deliveryAt("1-0", "d1", 100)));

        verify(presence, never()).applyDataBatch(any());
    }

    @Test
    void presenceFailureDoesNotFailOrRetryProcessing() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        doThrow(new RedisConnectionFailureException("down")).when(presence).applyDataBatch(any());

        processor.process(List.of(deliveryAt("1-0", "d1", 100)));

        verify(bus, times(1)).ack(GROUP, List.of("1-0"));
        verify(presence, times(1)).applyDataBatch(any());
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void retriesWriteInPlaceUntilInfluxRecovers() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(writer.write(anyList()))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("down")))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("down")))
                .thenReturn(WriteResult.ok());

        processor.process(List.of(delivery("1-0", "d1")));

        verify(writer, times(3)).write(anyList());
        verify(bus, times(1)).ack(GROUP, List.of("1-0"));
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void retriesLatestUpdateOnRedisFailure() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        doThrow(new RedisConnectionFailureException("down")).doNothing().when(latest).updateBatch(anyList());

        processor.process(List.of(delivery("1-0", "d1")));

        verify(latest, times(2)).updateBatch(anyList());
        verify(writer, times(1)).write(anyList());
        verify(bus, times(1)).ack(GROUP, List.of("1-0"));
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void unexpectedFailureDeadLettersOnlyThatMessage() {
        when(identities.find("good")).thenReturn(Optional.of(identity("good")));
        when(identities.find("bad")).thenThrow(new IllegalStateException("boom"));
        Delivery a = delivery("1-0", "good");
        Delivery bad = delivery("2-0", "bad");
        Delivery c = delivery("3-0", "good");

        processor.process(List.of(a, bad, c));

        verifyDeadLettered(bad, "PROCESSING_ERROR");
        verify(bus, times(1)).recordDead(any(), anyString(), anyMap());
        verify(writer, times(1)).write(anyList());
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    @Test
    void interruptDuringBackoffAbortsWithoutAck() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(writer.write(anyList())).thenThrow(new TelemetryUnavailableException(new RuntimeException("down")));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> processor.process(List.of(delivery("1-0", "d1"))))
                    .isInstanceOf(ProcessingAbortedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(bus, never()).ack(anyString(), anyList());
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void permanentDataAccessFailureInLookupIsNotRetried() {
        when(identities.find("good")).thenReturn(Optional.of(identity("good")));
        when(identities.find("bad")).thenThrow(new BadSqlGrammarException("q", "select", new java.sql.SQLException("x")));
        Delivery bad = delivery("2-0", "bad");

        processor.process(List.of(delivery("1-0", "good"), bad, delivery("3-0", "good")));

        // 批量预取时失败一次，退回逐条查询时再失败一次；均不重试（不可恢复）
        verify(identities, times(2)).find("bad");
        verifyDeadLettered(bad, "PROCESSING_ERROR");
        verify(bus, times(1)).recordDead(any(), anyString(), anyMap());
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    @Test
    void permanentRedisFailureInLatestUpdateDeadLettersOnlyThatItem() {
        when(identities.find(anyString())).thenAnswer(i -> Optional.of(identity(i.getArgument(0))));
        // 批量提交整体失败（不可恢复）后退回逐条，只有 bad 那条失败
        doThrow(new RedisSystemException("WRONGTYPE", new RuntimeException("WRONGTYPE")))
                .when(latest).updateBatch(anyList());
        doThrow(new RedisSystemException("WRONGTYPE", new RuntimeException("WRONGTYPE")))
                .when(latest).update(eq("bad"), anyLong(), any());
        Delivery bad = delivery("2-0", "bad");

        processor.process(List.of(delivery("1-0", "good"), bad, delivery("3-0", "good")));

        verify(latest, times(1)).update(eq("bad"), anyLong(), any());
        verifyDeadLettered(bad, "PROCESSING_ERROR");
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    @Test
    void nonRecoverableWriteFailureLeavesPropertiesUnackedForRedelivery() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(writer.write(anyList())).thenThrow(new IllegalStateException("InfluxDB rejected the write request itself"));

        assertThatThrownBy(() -> processor.process(List.of(delivery("1-0", "d1"))))
                .isInstanceOf(IllegalStateException.class);

        verify(writer, times(1)).write(anyList());
        verify(bus, never()).ack(anyString(), anyList());
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void unknownCodecDeadLettersWholeMessage() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(thingModels.find("pk")).thenReturn(Optional.of(new ProductProfile("gone", Map.of())));
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verifyDeadLettered(d, "UNKNOWN_CODEC");
        verify(writer, never()).write(anyList());
    }

    @Test
    void deviceOfADisabledTenantIsDeadLettered() {
        when(identities.find("d1")).thenReturn(Optional.of(new DeviceIdentity("d1", 1L, "ENABLED", "h", "s", "pk", "DISABLED")));
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verifyDeadLettered(d, "TENANT_DISABLED");
        verify(writer, never()).write(anyList());
    }

    @Test
    void tenantIsCheckedBeforeTheDevice() {
        when(identities.find("d1")).thenReturn(Optional.of(new DeviceIdentity("d1", 1L, "DISABLED", "h", "s", "pk", "DISABLED")));
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verifyDeadLettered(d, "TENANT_DISABLED");
        verify(writer, never()).write(anyList());
    }

    @Test
    void missingProductProfileIsUnknownDevice() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(thingModels.find("pk")).thenReturn(Optional.empty());
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verifyDeadLettered(d, "UNKNOWN_DEVICE");
    }

    @Test
    void withoutModelNumbersAreWrittenAsDouble() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        decodes(Map.of("Ua", 220L));

        processor.process(List.of(delivery("1-0", "d1")));

        verify(writer).write(argThat(points -> points.getFirst().fields().equals(Map.of("Ua", 220.0))));
        verify(bus).ack(GROUP, List.of("1-0"));
    }

    @Test
    void partiallyRejectedMessageWritesValidFieldsRecordsRejectionThenAcks() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        useModel(doubleSpec("Ua"));
        decodes(Map.of("Ua", 1.0, "Zz", 2L));
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verify(writer).write(argThat(points -> points.getFirst().fields().equals(Map.of("Ua", 1.0))));
        InOrder order = inOrder(latest, bus);
        order.verify(latest).updateBatch(argThat(l -> l.size() == 1 && l.getFirst().deviceKey().equals("d1")
                && l.getFirst().params().equals(Map.of("Ua", 1.0))));
        order.verify(bus).recordDead(eq(d), eq("FIELD_REJECTED"),
                argThat(m -> m.get("rejected").contains("\"field\":\"Zz\"") && m.get("rejected").contains("UNDEFINED")));
        order.verify(bus).ack(GROUP, List.of("1-0"));
        verify(bus, times(1)).recordDead(any(), anyString(), anyMap());
        assertThat(counter("conduit.pipeline.rejected_fields", "UNDEFINED")).isEqualTo(1.0);
        assertThat(counter("conduit.pipeline.dead", "FIELD_REJECTED")).isZero();
    }

    @Test
    void fullyRejectedMessageIsDeadLetteredWithDetailsAndNotWritten() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        useModel(doubleSpec("Ua"));
        decodes(Map.of("Ua", "abc"));
        Delivery d = delivery("1-0", "d1");

        processor.process(List.of(d));

        verify(bus).recordDead(eq(d), eq("FIELD_REJECTED"), argThat(m -> m.get("rejected").contains("TYPE_MISMATCH")));
        verify(bus).ack(GROUP, List.of("1-0"));
        verify(writer, never()).write(anyList());
        assertThat(counter("conduit.pipeline.rejected_fields", "TYPE_MISMATCH")).isEqualTo(1.0);
        assertThat(counter("conduit.pipeline.dead", "FIELD_REJECTED")).isEqualTo(1.0);
    }

    @Test
    void thingModelLookupFailureIsRetriedInPlace() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(thingModels.find("pk"))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(Optional.of(new ProductProfile("standard-json", Map.of())));

        processor.process(List.of(delivery("1-0", "d1")));

        verify(thingModels, times(2)).find("pk");
        verify(bus).ack(GROUP, List.of("1-0"));
    }

    @Test
    void dbOutageWrappedByMyBatisIsRetriedInPlace() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(thingModels.find("pk"))
                .thenThrow(new MyBatisSystemException(
                        new PersistenceException(new CannotGetJdbcConnectionException("down"))))
                .thenReturn(Optional.of(new ProductProfile("standard-json", Map.of())));

        processor.process(List.of(delivery("1-0", "d1")));

        verify(thingModels, times(2)).find("pk");
        verify(writer).write(anyList());
        verify(bus).ack(GROUP, List.of("1-0"));
        verify(bus, never()).recordDead(any(), anyString(), anyMap());
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
    }

    @Test
    void transientAckFailureAfterDeadRecordDoesNotDuplicateTheRecord() {
        Delivery d = delivery("1-0", "ghost");
        when(identities.find("ghost")).thenReturn(Optional.empty());
        doThrow(new RedisConnectionFailureException("down")).doNothing().when(bus).ack(GROUP, List.of("1-0"));

        processor.process(List.of(d));

        verify(bus, times(1)).recordDead(d, "UNKNOWN_DEVICE", Map.of());
        verify(bus, times(2)).ack(GROUP, List.of("1-0"));
    }

    @Test
    void identitiesAreLookedUpOncePerBatchAndLatestUpdatedOncePerBatch() {
        when(identities.find(anyString())).thenAnswer(i -> Optional.of(identity(i.getArgument(0))));

        processor.process(List.of(delivery("1-0", "d1"), delivery("2-0", "d2"), delivery("3-0", "d1")));

        verify(identities, times(1)).findAll(argThat(c -> c.stream().distinct().count() == 2 && c.size() == 2));
        // 逐条的 find 只在 findAll 的桩内部被调用（每个去重设备一次），处理循环本身不再逐条查身份
        verify(identities, times(1)).find("d1");
        // d1 的两条 ts 与测点相同，前一条被后一条完全覆盖，合并后批量调用只剩 d2 与 d1 各一次（LatestCoalescer）
        verify(latest, times(1)).updateBatch(argThat(l -> l.size() == 2
                && l.get(0).deviceKey().equals("d2") && l.get(1).deviceKey().equals("d1")));
        verify(latest, never()).update(anyString(), anyLong(), any());
        // 被合并掉的 1-0 仍然要 ACK：它已写入 InfluxDB，最新值贡献已被 3-0 覆盖
        verify(bus).ack(GROUP, List.of("1-0", "2-0", "3-0"));
    }

    @Test
    void badLatestParametersDeadLetterOnlyThatMessageAndTheRestAreBatched() {
        when(identities.find(anyString())).thenAnswer(i -> Optional.of(identity(i.getArgument(0))));
        when(decoder.decode(any(), any()))
                .thenReturn(new DecodedPayload(1L, Map.of("Ua", 1.0)))
                .thenReturn(new DecodedPayload(1L, Map.of("__last", 1.0)))
                .thenReturn(new DecodedPayload(1L, Map.of("Ua", 2.0)));
        Delivery good1 = delivery("1-0", "d1");
        Delivery reserved = delivery("2-0", "d2");
        Delivery good2 = delivery("3-0", "d3");

        processor.process(List.of(good1, reserved, good2));

        verifyDeadLettered(reserved, "PROCESSING_ERROR");
        verify(latest, times(1)).updateBatch(argThat(l -> l.size() == 2
                && l.get(0).deviceKey().equals("d1") && l.get(1).deviceKey().equals("d3")));
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
        verify(bus, times(1)).recordDead(any(), anyString(), anyMap());
    }

    @Test
    void unknownDeviceInBatchIsDeadLetteredWithoutAffectingOthers() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(identities.find("ghost")).thenReturn(Optional.empty());
        Delivery ghost = delivery("2-0", "ghost");

        processor.process(List.of(delivery("1-0", "d1"), ghost, delivery("3-0", "d1")));

        verifyDeadLettered(ghost, "UNKNOWN_DEVICE");
        // d1 的两条 ts 与测点相同，合并为一次最新值调用；两条仍都 ACK
        verify(latest, times(1)).updateBatch(argThat(l -> l.size() == 1));
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    // ---- 入库端到端延迟 conduit.pipeline.ingest.latency ----

    private PipelineProcessor processorWithClock(long nowMillis) {
        PipelineProperties props = new PipelineProperties(true, GROUP, "c", 100,
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(10), 5, 1);
        return new PipelineProcessor(bus, props, identities, thingModels, decoders, presenceHandler, writer,
                latest, presence, meters, Duration.ofMillis(1), Duration.ofMillis(5), () -> nowMillis);
    }

    private io.micrometer.core.instrument.Timer ingestLatency() {
        return meters.find("conduit.pipeline.ingest.latency").timer();
    }

    @Test
    void recordsOneLatencySamplePerIngestedMessage() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        PipelineProcessor p = processorWithClock(10_000);

        p.process(List.of(deliveryAt("1-0", "d1", 9_000), deliveryAt("2-0", "d1", 9_500)));

        var t = ingestLatency();
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(1_500.0);
    }

    @Test
    void deadLetteredMessagesDoNotRecordLatency() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(identities.find("ghost")).thenReturn(Optional.empty());
        PipelineProcessor p = processorWithClock(10_000);

        p.process(List.of(deliveryAt("1-0", "d1", 9_000), deliveryAt("2-0", "ghost", 9_000)));

        assertThat(ingestLatency().count()).isEqualTo(1);
    }

    @Test
    void clockDriftNeverProducesNegativeSample() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        PipelineProcessor p = processorWithClock(10_000);

        p.process(List.of(deliveryAt("1-0", "d1", 12_000)));

        var t = ingestLatency();
        assertThat(t.count()).isEqualTo(1);
        assertThat(t.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isZero();
        assertThat(t.max(java.util.concurrent.TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void replayPathDoesNotTouchLatencyMetric() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        processorWithClock(10_000);
        TelemetryIngestor ingestor = new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters);

        ingestor.prepare("1-0", deliveryAt("1-0", "d1", 1_000).message(), IngestSource.REPLAY);

        assertThat(ingestLatency().count()).isZero();
    }
}
