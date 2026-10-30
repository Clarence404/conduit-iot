package dev.conduit.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantDiscovery 与 SimulationPlanner 单元测试（本地 HttpServer 模拟 Conduit REST）：按 SIM_TENANTS 过滤、只进入启用中的租户、
 * 只取启用设备且每产品不超过上限、按解码器选格式、跳过无物模型/演示/不支持解码器的产品、单个租户失败不影响其他租户、
 * 平台密码为空时不发现租户。
 */
class TenantDiscoveryTest {

    HttpServer server;
    URI baseUrl;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> queries = new CopyOnWriteArrayList<>();
    final List<String> authorized = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile boolean westProductsFail;
    volatile boolean exitFails;
    volatile boolean secondLoginFails;
    volatile String resetFailsFor = "";
    final java.util.concurrent.atomic.AtomicInteger logins = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String call = ex.getRequestMethod() + " " + path;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        calls.add(call);
        queries.add(call + "?" + query);
        authorized.add(call + " " + auth);
        bodies.add(call + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int status = 200;
        String body;
        if (call.equals("POST /api/v1/auth/login")) {
            if (logins.incrementAndGet() > 1 && secondLoginFails) {
                status = 401;
                body = "{}";
            } else {
                body = "{\"accessToken\":\"tok-platform\"}";
            }
        } else if (call.equals("GET /api/v1/platform/tenants")) {
            body = """
                    {"items":[{"code":"east","name":"华东电力","status":"ACTIVE"},
                              {"code":"off","name":"停用租户","status":"DISABLED"},
                              {"code":"west","name":"西北新能源","status":"ACTIVE"}],
                     "total":3,"page":1,"size":100}""";
        } else if (call.startsWith("POST /api/v1/platform/tenants/") && call.endsWith("/enter")) {
            String code = path.split("/")[5];
            body = "{\"accessToken\":\"imp-" + code + "\"}";
        } else if (call.equals("POST /api/v1/auth/exit-tenant")) {
            if (exitFails) {
                status = 500;
                body = "{}";
            } else {
                body = "{\"accessToken\":\"tok-platform\"}";
            }
        } else if (call.equals("GET /api/v1/products") && "Bearer imp-east".equals(auth)) {
            body = """
                    {"items":[{"productKey":"pk-meter","name":"10kV 进线电表","codec":"flat-json","propertyCount":3},
                              {"productKey":"pk-empty","name":"无物模型网关","codec":"standard-json","propertyCount":0},
                              {"productKey":"pk-demo","name":"三相电表（演示）","codec":"standard-json","propertyCount":8},
                              {"productKey":"pk-binary","name":"二进制采集器","codec":"modbus-hex","propertyCount":2}],
                     "total":4,"page":1,"size":100}""";
        } else if (call.equals("GET /api/v1/products") && "Bearer imp-west".equals(auth)) {
            if (westProductsFail) {
                status = 500;
                body = "{\"detail\":\"boom\"}";
            } else {
                body = """
                        {"items":[{"productKey":"pk-pv","name":"组串式逆变器","codec":"standard-json","propertyCount":1}],
                         "total":1,"page":1,"size":100}""";
            }
        } else if (call.equals("GET /api/v1/products/pk-meter/properties")) {
            body = """
                    [{"identifier":"Ua","name":"A 相电压","dataType":"DOUBLE","unit":"V","minValue":0,"maxValue":300,
                      "decimals":1,"enumItems":null},
                     {"identifier":"st","name":"运行状态","dataType":"ENUM","unit":null,"minValue":null,"maxValue":null,
                      "decimals":null,"enumItems":[{"value":0,"label":"停机"},{"value":1,"label":"运行"}]},
                     {"identifier":"note","name":"备注","dataType":"TEXT","unit":null,"minValue":null,"maxValue":null,
                      "decimals":null,"enumItems":null}]""";
        } else if (call.equals("GET /api/v1/products/pk-pv/properties")) {
            body = """
                    [{"identifier":"Ppv","name":"光伏功率","dataType":"DOUBLE","unit":"kW","minValue":0,"maxValue":100,
                      "decimals":2,"enumItems":null}]""";
        } else if (call.equals("GET /api/v1/devices") && query.contains("productKey=pk-meter")) {
            body = """
                    {"items":[{"deviceKey":"d-1","name":"1号进线 电表01","status":"ENABLED"},
                              {"deviceKey":"d-2","name":"1号进线 电表02","status":"DISABLED"},
                              {"deviceKey":"d-3","name":"1号进线 电表03","status":"ENABLED"},
                              {"deviceKey":"d-4","name":"1号进线 电表04","status":"ENABLED"}],
                     "total":4,"page":1,"size":100}""";
        } else if (call.equals("GET /api/v1/devices") && query.contains("productKey=pk-pv")) {
            body = """
                    {"items":[{"deviceKey":"pv-1","name":"1号方阵 逆变器01","status":"ENABLED"}],
                     "total":1,"page":1,"size":100}""";
        } else if (call.startsWith("POST /api/v1/devices/") && call.endsWith("/reset-secret")) {
            String deviceKey = path.split("/")[4];
            if (deviceKey.equals(resetFailsFor)) {
                status = 500;
                body = "{}";
            } else
            body = "{\"deviceKey\":\"" + deviceKey + "\",\"deviceSecret\":\"s-" + deviceKey + "\"}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private ConduitApiClient platformClient() {
        ConduitApiClient api = new ConduitApiClient(baseUrl);
        api.login("sysadmin", "platform-pass-1");
        return api;
    }

    @Test
    void discoversOnlySelectedActiveTenantsEnabledDevicesAndSupportedProducts() {
        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.parse("east,off"), 2, Set.of("pk-demo"), Set.of());

        assertThat(products).singleElement().satisfies(p -> {
            assertThat(p.tenantCode()).isEqualTo("east");
            assertThat(p.productKey()).isEqualTo("pk-meter");
            assertThat(p.productName()).isEqualTo("10kV 进线电表");
            assertThat(p.format()).isEqualTo(PayloadFormat.FLAT);
            assertThat(p.devices()).extracting(DeviceCredentials::deviceKey).containsExactly("d-1", "d-3");
            assertThat(p.devices()).extracting(DeviceCredentials::secret).containsExactly("s-d-1", "s-d-3");
            assertThat(p.devices()).allSatisfy(d -> assertThat(d.productKey()).isEqualTo("pk-meter"));
            assertThat(p.properties()).hasSize(3);
            PropertyDef ua = p.properties().getFirst();
            assertThat(ua.unit()).isEqualTo("V");
            assertThat(ua.minValue()).isEqualTo(0.0);
            assertThat(ua.maxValue()).isEqualTo(300.0);
            assertThat(ua.decimals()).isEqualTo(1);
            assertThat(p.properties().get(1).enumItems())
                    .containsExactly(new PropertyDef.EnumOption(0, "停机"), new PropertyDef.EnumOption(1, "运行"));
            assertThat(p.properties().get(2).enumItems()).isEmpty();
        });
        assertThat(calls).contains("POST /api/v1/platform/tenants/east/enter", "POST /api/v1/auth/exit-tenant")
                .doesNotContain("POST /api/v1/platform/tenants/off/enter",
                        "POST /api/v1/platform/tenants/west/enter",
                        "GET /api/v1/products/pk-empty/properties",
                        "GET /api/v1/products/pk-demo/properties",
                        "GET /api/v1/products/pk-binary/properties",
                        "POST /api/v1/devices/d-2/reset-secret",
                        "POST /api/v1/devices/d-4/reset-secret");
        assertThat(queries).contains("GET /api/v1/platform/tenants?status=ACTIVE&page=1&size=100");
        assertThat(queries).anySatisfy(q -> assertThat(q).startsWith("GET /api/v1/devices?")
                .contains("productKey=pk-meter").contains("status=ENABLED"));
        assertThat(authorized).contains("GET /api/v1/products Bearer imp-east",
                "GET /api/v1/platform/tenants Bearer tok-platform");
    }

    @Test
    void aFailingTenantIsSkippedAndTheOthersContinue() {
        westProductsFail = true;

        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.ALL, 100, Set.of("pk-demo"), Set.of());

        assertThat(products).extracting(TenantDiscovery.DiscoveredProduct::productKey).containsExactly("pk-meter");
        assertThat(products.getFirst().devices()).hasSize(3);
        assertThat(calls).contains("POST /api/v1/platform/tenants/west/enter")
                .doesNotContain("POST /api/v1/platform/tenants/off/enter");
        assertThat(calls).filteredOn("POST /api/v1/auth/exit-tenant"::equals).hasSize(2);
    }

    /** 退出租户失败（500）时用平台管理员账号重新登录换回平台令牌，下一个租户仍能被处理。 */
    @Test
    void aFailedExitReloginsSoTheNextTenantIsStillProcessed() {
        exitFails = true;

        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.ALL, 100, Set.of("pk-demo"), Set.of());

        assertThat(products).extracting(TenantDiscovery.DiscoveredProduct::productKey)
                .containsExactly("pk-meter", "pk-pv");
        assertThat(authorized).contains("POST /api/v1/platform/tenants/west/enter Bearer tok-platform");
        assertThat(logins.get()).isEqualTo(3);
    }

    /** 退出失败且重新登录也失败时停止发现后续租户，保留已发现的产品。 */
    @Test
    void discoveryStopsButKeepsResultsWhenTheReloginFailsToo() {
        exitFails = true;
        secondLoginFails = true;

        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.ALL, 100, Set.of("pk-demo"), Set.of());

        assertThat(products).extracting(TenantDiscovery.DiscoveredProduct::productKey).containsExactly("pk-meter");
        assertThat(calls).doesNotContain("POST /api/v1/platform/tenants/west/enter");
    }

    /** SIM_EXCLUDE_PRODUCTS 按名称或 productKey 排除产品：既不模拟，也不读物模型、不重置其设备密钥（否则会作废压测脚本持有的密钥）。 */
    @Test
    void excludedProductsAreSkippedBeforeAnySecretIsReset() {
        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.ALL, 100, Set.of("pk-demo"), Set.of("10kV 进线电表", "pk-pv"));

        assertThat(products).isEmpty();
        assertThat(calls).doesNotContain("GET /api/v1/products/pk-meter/properties",
                        "GET /api/v1/products/pk-pv/properties")
                .noneMatch(c -> c.endsWith("/reset-secret"));
    }

    /** 某台设备重置密钥失败时只跳过该设备，已重置的设备保留。 */
    @Test
    void aFailedSecretResetOnlySkipsThatDevice() {
        resetFailsFor = "d-3";

        List<TenantDiscovery.DiscoveredProduct> products = TenantDiscovery.discover(platformClient(),
                TenantFilter.parse("east"), 100, Set.of("pk-demo"), Set.of());

        assertThat(products).singleElement().satisfies(p ->
                assertThat(p.devices()).extracting(DeviceCredentials::deviceKey).containsExactly("d-1", "d-4"));
    }

    @Test
    void thePlannerSkipsDiscoveryWithoutAPlatformPassword() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "CONDUIT_API_URL", baseUrl.toString()));

        List<SimulatedDevice> devices = SimulationPlanner.tenantDevices(config, () -> {
            throw new AssertionError("no platform client may be created without SIM_PLATFORM_PASSWORD");
        }, Set.of());

        assertThat(devices).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void thePlannerBuildsThingModelDevicesForTheSelectedTenants() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "CONDUIT_API_URL", baseUrl.toString(),
                "SIM_PLATFORM_PASSWORD", "platform-pass-1",
                "SIM_TENANTS", "west"));

        List<SimulatedDevice> devices = SimulationPlanner.tenantDevices(config,
                () -> new ConduitApiClient(baseUrl), Set.of());

        assertThat(devices).singleElement().satisfies(d -> {
            assertThat(d.credentials().deviceKey()).isEqualTo("pv-1");
            assertThat(d.credentials().productKey()).isEqualTo("pk-pv");
            assertThat(d.format()).isEqualTo(PayloadFormat.STANDARD);
            assertThat(d.readings().next(System.currentTimeMillis(), 10)).containsOnlyKeys("Ppv");
        });
        assertThat(bodies).anySatisfy(b -> assertThat(b).startsWith("POST /api/v1/auth/login ")
                .contains("\"username\":\"sysadmin\""));
    }

    @Test
    void thePlannerFallsBackToTheDemoProductWhenThePlatformLoginFails() {
        server.removeContext("/");
        server.createContext("/", ex -> {
            byte[] bytes = "{\"detail\":\"用户名或密码错误\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "CONDUIT_API_URL", baseUrl.toString(), "SIM_PLATFORM_PASSWORD", "wrong-pass-1"));

        assertThat(SimulationPlanner.tenantDevices(config, () -> new ConduitApiClient(baseUrl), Set.of())).isEmpty();
    }
}
