package dev.conduit.server.telemetry;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.conduit.server.support.InfluxTestSupport.DATABASE;
import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史查询接口的集成测试（InfluxDB 3.12.0 + PostgreSQL + Redis）：原始值、聚合 avg/min/max 与桶边界、
 * 枚举/布尔取桶内最后值、不存在的列、无物模型测点按列类型、文本测点 400、其他租户 404、InfluxDB 不可达 503。
 */
class HistoryApiIT extends IntegrationTestBase {

    @Autowired
    InfluxClient client;

    @Autowired
    InfluxProperties props;

    final long now = System.currentTimeMillis();

    /** 设备所在租户的一行数据。 */
    private static String line(TestDevice d, String fields, long ts) {
        return line(Long.toString(d.tenantId()), d, fields, ts);
    }

    /** 指定 tenant_id 标签的一行数据（用于验证租户条件）。 */
    private static String line(String tenant, TestDevice d, String fields, long ts) {
        return "property,tenant_id=" + tenant + ",product_key=" + d.productKey() + ",device_key=" + d.deviceKey()
                + " " + fields + " " + ts;
    }

    private void write(List<String> lines) {
        assertThat(client.writeLines(DATABASE, lines)).isEmpty();
    }

    /** 以管理员（默认租户）身份请求历史接口并返回响应体。from/to 为 null 时不传。 */
    private String history(String deviceKey, String properties, Long from, Long to, HttpStatus expected)
            throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/devices/{dk}/history", deviceKey)
                .param("properties", properties)
                .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin()));
        if (from != null) {
            request.param("from", Long.toString(from));
        }
        if (to != null) {
            request.param("to", Long.toString(to));
        }
        return mvc.perform(request).andExpect(status().is(expected.value()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T read(String body, String path) {
        return JsonPath.read(body, path);
    }

    @Test
    void rawModeReturnsPointsInRequestOrderWithModelMetadata() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");
        String ua = unique("ua");
        String zz = unique("zz");
        insertProperty(d.productKey(), ua, "A 相电压", "DOUBLE", "V", null, null, 1, null);
        long t0 = Math.floorDiv(now - 30 * 60_000, 1_000) * 1_000;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add(line(d, ua + "=" + (220 + i) + ".0", t0 + i * 1_000L));
        }
        lines.add(line(d, ua + "=999.0", t0 + 60_000));         // to 不含
        lines.add(line("999999", d, ua + "=888.0", t0 + 500));  // 其他租户标签，不应出现
        write(lines);
        awaitCount(client, d.deviceKey(), ua, 12);

        String body = history(d.deviceKey(), zz + "," + ua, t0, t0 + 60_000, HttpStatus.OK);

        assertThat((Object) read(body, "$.bucketMs")).isNull();
        assertThat((String) read(body, "$.deviceKey")).isEqualTo(d.deviceKey());
        List<String> ids = read(body, "$.series[*].identifier");
        assertThat(ids).containsExactly(zz, ua);
        List<Object> missing = read(body, "$.series[0].points");
        assertThat(missing).isEmpty();
        assertThat((Object) read(body, "$.series[0].dataType")).isNull();
        assertThat((String) read(body, "$.series[0].mode")).isEqualTo("RAW");
        assertThat((String) read(body, "$.series[1].name")).isEqualTo("A 相电压");
        assertThat((String) read(body, "$.series[1].unit")).isEqualTo("V");
        assertThat((Integer) read(body, "$.series[1].decimals")).isEqualTo(1);
        assertThat((String) read(body, "$.series[1].dataType")).isEqualTo("DOUBLE");
        assertThat((String) read(body, "$.series[1].mode")).isEqualTo("RAW");
        List<Object> points = read(body, "$.series[1].points");
        assertThat(points).hasSize(10);
        List<Object> first = read(body, "$.series[1].points[0]");
        assertThat(first).containsExactly(t0, 220.0);
        List<Object> last = read(body, "$.series[1].points[9]");
        assertThat(last).containsExactly(t0 + 9_000, 229.0);
    }

    @Test
    void aggregatesNumericAndTakesLastStateValuesPerBucket() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");
        String f = unique("f");
        String st = unique("st");
        String on = unique("on");
        String sp = unique("sp");
        insertProperty(d.productKey(), f, "功率", "DOUBLE", "kW", null, null, 2, null);
        insertProperty(d.productKey(), st, "运行状态", "ENUM", null, null, null, null,
                "[{\"value\":0,\"label\":\"停机\"},{\"value\":1,\"label\":\"运行\"},{\"value\":2,\"label\":\"检修\"}]");
        insertProperty(d.productKey(), on, "开关", "BOOL", null, null, null, null, null);
        long t0 = Math.floorDiv(now - 40 * 60_000, 5_000) * 5_000;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            String fields = f + "=" + i + ".0," + st + "=" + (i % 3) + "i," + on + "=" + (i % 2 == 0)
                    + (i < 50 ? "," + sp + "=" + i + ".0" : "");
            lines.add(line(d, fields, t0 + i * 1_000L));
        }
        write(lines);
        awaitCount(client, d.deviceKey(), f, 1200);

        // from 不在 5 秒边界上：跨度 1197 秒、点数 1197 > 1000 → 5 秒桶，首桶起点 t0 早于 from
        String body = history(d.deviceKey(), f + "," + st + "," + on + "," + sp, t0 + 3_000, t0 + 1_200_000,
                HttpStatus.OK);

        assertThat(((Number) read(body, "$.bucketMs")).longValue()).isEqualTo(5_000L);
        assertThat((String) read(body, "$.series[0].mode")).isEqualTo("AGG");
        List<Object> fPoints = read(body, "$.series[0].points");
        assertThat(fPoints).hasSize(240);
        List<Object> f0 = read(body, "$.series[0].points[0]");
        assertThat(f0).containsExactly(t0, 3.5, 3.0, 4.0);
        List<Object> f1 = read(body, "$.series[0].points[1]");
        assertThat(f1).containsExactly(t0 + 5_000, 7.0, 5.0, 9.0);

        assertThat((String) read(body, "$.series[1].mode")).isEqualTo("LAST");
        assertThat((String) read(body, "$.series[1].enumItems[1].label")).isEqualTo("运行");
        List<Object> st0 = read(body, "$.series[1].points[0]");
        assertThat(st0).containsExactly(t0, 1);
        List<Object> st1 = read(body, "$.series[1].points[1]");
        assertThat(st1).containsExactly(t0 + 5_000, 0);

        assertThat((String) read(body, "$.series[2].mode")).isEqualTo("LAST");
        List<Object> on0 = read(body, "$.series[2].points[0]");
        assertThat(on0).containsExactly(t0, true);
        List<Object> on1 = read(body, "$.series[2].points[1]");
        assertThat(on1).containsExactly(t0 + 5_000, false);

        assertThat((String) read(body, "$.series[3].dataType")).isEqualTo("DOUBLE");
        List<Object> spPoints = read(body, "$.series[3].points");
        assertThat(spPoints).as("empty buckets keep explicit null gaps").hasSize(240);
        List<Object> emptyBucket = read(body, "$.series[3].points[10]");
        assertThat(emptyBucket).containsExactly(t0 + 50_000, null, null, null);
    }

    @Test
    void noModelColumnsUseTheInfluxColumnType() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");
        String cnt = unique("cnt");
        String flag = unique("flag");
        long t0 = Math.floorDiv(now - 10 * 60_000, 1_000) * 1_000;
        write(List.of(line(d, cnt + "=5i," + flag + "=true", t0)));
        awaitCount(client, d.deviceKey(), cnt, 1);

        String body = history(d.deviceKey(), cnt + "," + flag, t0, t0 + 60_000, HttpStatus.OK);

        assertThat((String) read(body, "$.series[0].dataType")).isEqualTo("INT");
        assertThat((String) read(body, "$.series[1].dataType")).isEqualTo("BOOL");
        assertThat((Object) read(body, "$.series[0].name")).isNull();
        List<Object> c0 = read(body, "$.series[0].points[0]");
        assertThat(c0).containsExactly(t0, 5);
        List<Object> b0 = read(body, "$.series[1].points[0]");
        assertThat(b0).containsExactly(t0, true);
    }

    @Test
    void textPropertiesAreRejected() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");
        String note = unique("note");
        String msg = unique("msg");
        insertProperty(d.productKey(), note, "备注", "TEXT", null, null, null, null, null);
        long t0 = now - 60_000;
        write(List.of(line(d, msg + "=\"hello\"", t0)));
        awaitCount(client, d.deviceKey(), msg, 1);

        String modelText = history(d.deviceKey(), note, null, null, HttpStatus.BAD_REQUEST);
        String columnText = history(d.deviceKey(), msg, null, null, HttpStatus.BAD_REQUEST);

        assertThat((String) read(modelText, "$.detail")).contains("文本测点不支持历史曲线");
        assertThat((String) read(columnText, "$.detail")).contains("文本测点不支持历史曲线");
    }

    @Test
    void invalidParametersAre400() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        String tooMany = history(d.deviceKey(), "a1,a2,a3,a4,a5,a6,a7,a8,a9", null, null, HttpStatus.BAD_REQUEST);
        String oneBound = history(d.deviceKey(), "Ua", now - 3_600_000, null, HttpStatus.BAD_REQUEST);

        assertThat((String) read(tooMany, "$.detail")).isEqualTo("最多查询 8 个测点");
        assertThat((String) read(oneBound, "$.detail")).isEqualTo("from 与 to 须同时提供");
    }

    @Test
    void defaultsToTheLastHour() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        String body = history(d.deviceKey(), "Ua", null, null, HttpStatus.OK);

        long from = ((Number) read(body, "$.from")).longValue();
        long to = ((Number) read(body, "$.to")).longValue();
        assertThat(to - from).isEqualTo(3_600_000L);
        assertThat(to).isBetween(now - 60_000, now + 60_000);
    }

    @Test
    void otherTenantsDeviceIsNotFound() throws Exception {
        TestDevice foreign = createDevice(createTenant("other-"), "ENABLED");

        history(foreign.deviceKey(), "Ua", null, null, HttpStatus.NOT_FOUND);
    }

    @Test
    void unreachableInfluxDbIs503() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (InfluxClient dead = new InfluxClient(URI.create("http://127.0.0.1:" + closedPort), "apiv3_dead")) {
            HistoryService service = new HistoryService(dead, new InfluxSchema(dead, props, Clock.systemUTC()), props);
            DeviceView device = new DeviceView("dk", "设备", "pk", "ENABLED", true, null, null);

            assertThatThrownBy(() -> service.query(1, device, Map.of(),
                    new HistoryRequest(List.of("Ua"), now - 3_600_000, now)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).isEqualTo("时序库暂不可用");
                    });
        }
    }
}
