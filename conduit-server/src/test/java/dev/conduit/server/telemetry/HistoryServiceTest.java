package dev.conduit.server.telemetry;

import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.telemetry.HistoryDtos.HistoryView;
import dev.conduit.server.telemetry.HistoryDtos.Mode;
import dev.conduit.server.telemetry.HistoryDtos.SeriesView;
import dev.conduit.server.telemetry.InfluxSchema.ColumnType;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.EnumItem;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HistoryService 的单元测试：类型判定、原始/聚合模式选择、结果组装与异常映射（InfluxDB 用 mock）。 */
class HistoryServiceTest {

    static final long FROM = 1760400000000L; // 2025-10-14T00:00:00Z
    static final long TO = FROM + 3_600_000;

    InfluxClient client = mock(InfluxClient.class);
    InfluxSchema schema = mock(InfluxSchema.class);
    InfluxProperties props = new InfluxProperties("http://influx", "apiv3_test", "telemetry", "30d");
    HistoryService service = new HistoryService(client, schema, props);
    DeviceView device = new DeviceView("dk1", "1号楼配电室 电表01", "pk1", "ENABLED", true, null, null);

    static PropertySpec spec(String id, String name, DataType type, String unit, Integer decimals) {
        return new PropertySpec(id, name, type, unit, null, null, decimals, Map.of());
    }

    static PropertySpec enumSpec(String id) {
        Map<Long, String> labels = new LinkedHashMap<>();
        labels.put(0L, "停机");
        labels.put(1L, "运行");
        return new PropertySpec(id, "运行状态", DataType.ENUM, null, null, null, null, labels);
    }

    static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private HistoryView query(Map<String, PropertySpec> model, String... ids) {
        return service.query(7, device, model, new HistoryRequest(List.of(ids), FROM, TO));
    }

    private void counts(Map<String, Object> countRow) {
        when(client.querySql(eq("telemetry"), startsWith("SELECT COUNT"), anyMap())).thenReturn(List.of(countRow));
    }

    @Test
    void rawWhenEveryPropertyHasAtMostMaxPoints() {
        when(schema.lookup(List.of("Ua", "st"))).thenReturn(Map.of("Ua", ColumnType.FLOAT, "st", ColumnType.INTEGER));
        counts(row("n_0", 1000, "n_1", 2));
        when(client.querySql(eq("telemetry"), startsWith("SELECT time"), anyMap())).thenReturn(List.of(
                row("time", "2025-10-14T00:00:01", "v_0", 220.5, "v_1", 1),
                row("time", "2025-10-14T00:00:02", "v_0", 221.0, "v_1", null),
                row("time", "2025-10-14T00:00:03", "v_0", 222.0)));

        HistoryView view = query(Map.of("Ua", spec("Ua", "A 相电压", DataType.DOUBLE, "V", 1), "st", enumSpec("st")),
                "Ua", "st");

        assertThat(view.bucketMs()).isNull();
        assertThat(view.from()).isEqualTo(FROM);
        assertThat(view.to()).isEqualTo(TO);
        SeriesView ua = view.series().get(0);
        assertThat(ua.identifier()).isEqualTo("Ua");
        assertThat(ua.name()).isEqualTo("A 相电压");
        assertThat(ua.unit()).isEqualTo("V");
        assertThat(ua.decimals()).isEqualTo(1);
        assertThat(ua.mode()).isEqualTo(Mode.RAW);
        assertThat(ua.points()).containsExactly(
                List.of(FROM + 1_000, 220.5), List.of(FROM + 2_000, 221.0), List.of(FROM + 3_000, 222.0));
        SeriesView st = view.series().get(1);
        assertThat(st.dataType()).isEqualTo(DataType.ENUM);
        assertThat(st.mode()).isEqualTo(Mode.RAW);
        assertThat(st.enumItems()).containsExactly(new EnumItem(0, "停机"), new EnumItem(1, "运行"));
        assertThat(st.points()).containsExactly(List.of(FROM + 1_000, 1));
    }

    @Test
    void aggregatesWhenAnyPropertyExceedsMaxPoints() {
        when(schema.lookup(List.of("Ua", "st"))).thenReturn(Map.of("Ua", ColumnType.FLOAT, "st", ColumnType.INTEGER));
        counts(row("n_0", 1001, "n_1", 5));
        when(client.querySql(eq("telemetry"), startsWith("SELECT date_bin"), anyMap())).thenReturn(List.of(
                row("t", "2025-10-14T00:00:00", "avg_0", 220.0, "min_0", 219.0, "max_0", 221.0, "last_1", 2),
                row("t", "2025-10-14T00:00:05", "avg_0", null, "min_0", null, "max_0", null, "last_1", 0),
                row("t", "2025-10-14T00:00:15", "avg_0", 0.0, "min_0", 0.0, "max_0", 0.0)));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        HistoryView view = query(Map.of("st", enumSpec("st")), "Ua", "st");

        verify(client, times(2)).querySql(eq("telemetry"), sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(s -> assertThat(s).contains("INTERVAL '5 seconds'"));
        assertThat(view.bucketMs()).isEqualTo(5_000L);
        SeriesView ua = view.series().get(0);
        assertThat(ua.dataType()).isEqualTo(DataType.DOUBLE);
        assertThat(ua.mode()).isEqualTo(Mode.AGG);
        assertThat(ua.points()).hasSize(720);
        assertThat(ua.points().getFirst()).isEqualTo(List.of(FROM, 220.0, 219.0, 221.0));
        assertThat(ua.points().get(1)).containsExactly(FROM + 5_000, null, null, null);
        assertThat(ua.points().get(2)).containsExactly(FROM + 10_000, null, null, null);
        assertThat(ua.points().get(3)).containsExactly(FROM + 15_000, 0.0, 0.0, 0.0);
        assertThat(ua.points().getLast()).containsExactly(TO - 5_000, null, null, null);
        SeriesView st = view.series().get(1);
        assertThat(st.mode()).isEqualTo(Mode.LAST);
        assertThat(st.points()).hasSize(720).startsWith(List.of(FROM, 2), List.of(FROM + 5_000, 0));
        assertThat(st.points().get(2)).containsExactly(FROM + 10_000, null);
    }

    @Test
    void missingColumnsAreNeitherCountedNorQueried() {
        when(schema.lookup(List.of("Ua", "st"))).thenReturn(Map.of("Ua", ColumnType.FLOAT));
        counts(row("n_0", 0));
        when(client.querySql(eq("telemetry"), startsWith("SELECT time"), anyMap())).thenReturn(List.of());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        HistoryView view = query(Map.of("st", enumSpec("st")), "Ua", "st");

        verify(client, times(2)).querySql(eq("telemetry"), sql.capture(), anyMap());
        assertThat(sql.getAllValues()).allSatisfy(s -> assertThat(s).doesNotContain("\"st\""));
        assertThat(view.series().get(1).points()).isEmpty();
        assertThat(view.series().get(1).dataType()).isEqualTo(DataType.ENUM);
        assertThat(view.series().get(1).mode()).isEqualTo(Mode.RAW);
    }

    @Test
    void noExistingColumnMeansNoSql() {
        when(schema.lookup(List.of("Zz"))).thenReturn(Map.of());

        HistoryView view = query(Map.of(), "Zz");

        verify(client, never()).querySql(anyString(), anyString(), anyMap());
        assertThat(view.bucketMs()).isNull();
        assertThat(view.series()).singleElement().satisfies(s -> {
            assertThat(s.dataType()).isNull();
            assertThat(s.name()).isNull();
            assertThat(s.mode()).isEqualTo(Mode.RAW);
            assertThat(s.points()).isEmpty();
        });
    }

    @Test
    void noModelColumnsTakeTheInfluxType() {
        when(schema.lookup(List.of("a", "b", "c")))
                .thenReturn(Map.of("a", ColumnType.INTEGER, "b", ColumnType.BOOLEAN, "c", ColumnType.FLOAT));
        counts(row("n_0", 1, "n_1", 1, "n_2", 1));
        when(client.querySql(eq("telemetry"), startsWith("SELECT time"), anyMap())).thenReturn(List.of(
                row("time", "2025-10-14T00:00:00", "v_0", 5, "v_1", true, "v_2", 1.5)));

        HistoryView view = query(Map.of(), "a", "b", "c");

        assertThat(view.series()).extracting(SeriesView::dataType)
                .containsExactly(DataType.INT, DataType.BOOL, DataType.DOUBLE);
        assertThat(view.series()).extracting(SeriesView::name).containsOnlyNulls();
        assertThat(view.series().get(1).points()).containsExactly(List.of(FROM, true));
        assertThat(view.series().get(0).enumItems()).isNull();
    }

    @Test
    void textPropertiesAreRejected() {
        when(schema.lookup(List.of("note"))).thenReturn(Map.of());
        assertBadRequest(() -> query(Map.of("note", spec("note", "备注", DataType.TEXT, null, null)), "note"));

        when(schema.lookup(List.of("msg"))).thenReturn(Map.of("msg", ColumnType.STRING));
        assertBadRequest(() -> query(Map.of(), "msg"));
        assertBadRequest(() -> query(Map.of("msg", spec("msg", "消息", DataType.DOUBLE, null, null)), "msg"));
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains("文本测点不支持历史曲线");
        });
    }

    @Test
    void unreachableInfluxIs503() {
        when(schema.lookup(List.of("Ua"))).thenThrow(new UncheckedIOException(new ConnectException("refused")));

        assertThatThrownBy(() -> query(Map.of(), "Ua")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).isEqualTo("时序库暂不可用");
        });
    }

    @Test
    void influxServerErrorIs503() {
        when(schema.lookup(List.of("Ua"))).thenReturn(Map.of("Ua", ColumnType.FLOAT));
        when(client.querySql(anyString(), anyString(), anyMap())).thenThrow(new InfluxHttpException(500, "boom"));

        assertThatThrownBy(() -> query(Map.of(), "Ua")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void otherInfluxErrorsAre500WithoutSql() {
        when(schema.lookup(List.of("Ua"))).thenReturn(Map.of("Ua", ColumnType.FLOAT));
        when(client.querySql(anyString(), anyString(), anyMap()))
                .thenThrow(new InfluxHttpException(400, "error in SELECT COUNT(\"Ua\") FROM property"));

        assertThatThrownBy(() -> query(Map.of(), "Ua")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.getMessage()).isEqualTo("历史查询失败").doesNotContain("SELECT");
        });
    }
}
