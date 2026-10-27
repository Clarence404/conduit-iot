package dev.conduit.server.telemetry;

import dev.conduit.server.telemetry.InfluxSchema.ColumnType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** InfluxSchema 的单元测试：类型归类、60 秒缓存、未知列强制刷新。 */
class InfluxSchemaTest {

    /** 可手动拨动的时钟。 */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    InfluxClient client = mock(InfluxClient.class);
    InfluxProperties props = new InfluxProperties("http://influx", "apiv3_test", "telemetry", "30d", 8388608);
    MutableClock clock = new MutableClock();
    InfluxSchema schema;

    @BeforeEach
    void setUp() {
        schema = new InfluxSchema(client, props, clock);
        when(client.querySql("telemetry", InfluxSchema.COLUMNS_SQL)).thenReturn(List.of(
                Map.of("column_name", "Ua", "data_type", "Float64"),
                Map.of("column_name", "cnt", "data_type", "Int64"),
                Map.of("column_name", "on", "data_type", "Boolean"),
                Map.of("column_name", "msg", "data_type", "Utf8"),
                Map.of("column_name", "tenant_id", "data_type", "Dictionary(Int32, Utf8)"),
                Map.of("column_name", "time", "data_type", "Timestamp(ns)")));
    }

    @Test
    void classifiesArrowTypes() {
        assertThat(schema.lookup(List.of("Ua", "cnt", "on", "msg", "tenant_id", "time"))).containsExactly(
                Map.entry("Ua", ColumnType.FLOAT),
                Map.entry("cnt", ColumnType.INTEGER),
                Map.entry("on", ColumnType.BOOLEAN),
                Map.entry("msg", ColumnType.STRING),
                Map.entry("tenant_id", ColumnType.STRING),
                Map.entry("time", ColumnType.OTHER));
    }

    @Test
    void keepsRequestOrderAndLeavesOutMissingColumns() {
        assertThat(schema.lookup(List.of("on", "nope", "Ua")).keySet()).containsExactly("on", "Ua");
    }

    @Test
    void servesKnownColumnsFromCacheWithinTtl() {
        schema.lookup(List.of("Ua"));
        clock.now = clock.now.plus(Duration.ofSeconds(59));
        schema.lookup(List.of("cnt"));

        verify(client, times(1)).querySql("telemetry", InfluxSchema.COLUMNS_SQL);
    }

    @Test
    void reloadsAfterTtl() {
        schema.lookup(List.of("Ua"));
        clock.now = clock.now.plus(InfluxSchema.TTL);
        schema.lookup(List.of("Ua"));

        verify(client, times(2)).querySql("telemetry", InfluxSchema.COLUMNS_SQL);
    }

    @Test
    void unknownColumnForcesOneRefreshPerLookup() {
        schema.lookup(List.of("Ua"));
        when(client.querySql("telemetry", InfluxSchema.COLUMNS_SQL)).thenReturn(List.of(
                Map.of("column_name", "Ua", "data_type", "Float64"),
                Map.of("column_name", "fresh", "data_type", "Float64")));

        assertThat(schema.lookup(List.of("Ua", "fresh"))).containsKeys("Ua", "fresh");
        assertThat(schema.lookup(List.of("never"))).isEmpty();

        verify(client, times(3)).querySql("telemetry", InfluxSchema.COLUMNS_SQL);
    }

    @Test
    void firstLoadWithUnknownColumnDoesNotReloadTwice() {
        assertThat(schema.lookup(List.of("never"))).isEmpty();

        verify(client, times(1)).querySql("telemetry", InfluxSchema.COLUMNS_SQL);
    }
}
