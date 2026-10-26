package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.auth.JwtService;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 总览接口集成测试：两个新建租户各自只看到自己的产品、属性、设备、在线数、最近上报设备与死信；未登录 401。
 * 两个租户都是新建的，计数不受共享库中其他测试数据的影响。
 */
class OverviewApiIT extends IntegrationTestBase {

    @Autowired
    JwtService jwt;

    @Autowired
    PresenceStore presence;

    @Autowired
    LatestValueStore latest;

    @Autowired
    MessageBusProperties bus;

    /** 测试产品：id 与 productKey。 */
    record TestProduct(long id, String key) {
    }

    @Test
    void eachTenantSeesOnlyItsOwnData() throws Exception {
        long tenantA = createTenant("ov-a-");
        long tenantB = createTenant("ov-b-");
        TestProduct meter = insertProduct(tenantA, "三相电表（总览测试）");
        TestProduct water = insertProduct(tenantA, "水表（总览测试）");
        TestProduct other = insertProduct(tenantB, "其他租户产品（总览测试）");
        insertProperty(meter.key(), "Ua", "A 相电压", "DOUBLE", "V", null, null, 1, null);
        insertProperty(meter.key(), "Ub", "B 相电压", "DOUBLE", "V", null, null, 1, null);
        insertProperty(water.key(), "flow", "瞬时流量", "DOUBLE", "m³/h", null, null, 2, null);
        String m1 = insertDevice(tenantA, meter.id(), "1号楼配电室 电表01", "ENABLED");
        String m2 = insertDevice(tenantA, meter.id(), "1号楼配电室 电表02", "ENABLED");
        String w1 = insertDevice(tenantA, water.id(), "1号楼 水表01", "DISABLED");
        String o1 = insertDevice(tenantB, other.id(), "其他租户设备", "ENABLED");
        long now = System.currentTimeMillis();
        presence.apply(m1, true, now, "");
        presence.apply(m2, false, now, "");
        presence.apply(o1, true, now, "");
        latest.update(m1, now - 5000, Map.of("Ua", 220.0));
        latest.update(w1, now - 1000, Map.of("flow", 1.5));
        latest.update(o1, now, Map.of("Ua", 230.0));
        dead(meter.key(), "FIELD_REJECTED");
        dead(meter.key(), "FIELD_REJECTED");
        dead(water.key(), "MALFORMED_JSON");
        dead(other.key(), "PROCESSING_ERROR");

        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(2))
                .andExpect(jsonPath("$.propertyCount").value(3))
                .andExpect(jsonPath("$.deviceCount").value(3))
                .andExpect(jsonPath("$.enabledCount").value(2))
                .andExpect(jsonPath("$.disabledCount").value(1))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.deadLastHour.total").value(3))
                .andExpect(jsonPath("$.deadLastHour.byReason.FIELD_REJECTED").value(2))
                .andExpect(jsonPath("$.deadLastHour.byReason.MALFORMED_JSON").value(1))
                .andExpect(jsonPath("$.deadLastHour.byReason.PROCESSING_ERROR").doesNotExist())
                .andExpect(jsonPath("$.recentDevices[*].deviceKey", contains(w1, m1)))
                .andExpect(jsonPath("$.recentDevices[0].productName").value("水表（总览测试）"))
                .andExpect(jsonPath("$.products[*].productKey", contains(meter.key(), water.key())))
                .andExpect(jsonPath("$.products[0].deviceCount").value(2))
                .andExpect(jsonPath("$.products[0].onlineCount").value(1))
                .andExpect(jsonPath("$.products[1].onlineCount").value(0));

        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(1))
                .andExpect(jsonPath("$.propertyCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(1))
                .andExpect(jsonPath("$.onlineCount").value(1))
                .andExpect(jsonPath("$.deadLastHour.total").value(1))
                .andExpect(jsonPath("$.deadLastHour.byReason.PROCESSING_ERROR").value(1))
                .andExpect(jsonPath("$.recentDevices[*].deviceKey", contains(o1)))
                .andExpect(jsonPath("$.products[*].productKey", contains(other.key())));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized());
    }

    /** 为指定租户签发 access token（过滤器只校验令牌、不查用户，因此不必建测试用户）。 */
    private String tokenFor(long tenantId) {
        return bearer(jwt.issue(new AuthPrincipal(0L, tenantId, "overview-it", Roles.TENANT_ADMIN),
                JwtService.TokenType.ACCESS));
    }

    /** 直接写库建产品，返回 id 与 productKey。 */
    private TestProduct insertProduct(long tenantId, String name) {
        String key = unique("pk");
        Long id = jdbc.queryForObject("insert into product (tenant_id, product_key, name) values (?, ?, ?) returning id",
                Long.class, tenantId, key, name);
        return new TestProduct(id, key);
    }

    /** 直接写库在指定产品下建设备，返回 deviceKey。 */
    private String insertDevice(long tenantId, long productId, String name, String status) {
        String deviceKey = unique("dk");
        String salt = secretHasher.newSalt();
        jdbc.update("insert into device (tenant_id, product_id, site_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, (select id from site where tenant_id = ? and is_default), ?, ?, ?, ?, ?)",
                tenantId, productId, tenantId, deviceKey, name, secretHasher.hash("secret-" + deviceKey, salt), salt, status);
        return deviceKey;
    }

    /** 往死信流追加一条记录（自动 ID，即当前 Redis 时间）。 */
    private void dead(String productKey, String reason) {
        redis.opsForStream().add(bus.deadKey(), Map.of("pk", productKey, "dk", "dk-overview-it", "reason", reason,
                "failedAt", Long.toString(System.currentTimeMillis())));
    }
}
