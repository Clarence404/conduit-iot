package dev.conduit.server.product;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.presence.PresenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 产品接口集成测试：增删改查、分页、校验、租户隔离、解码器、属性数，以及设备数与在线数。 */
class ProductApiIT extends IntegrationTestBase {

    String auth;

    @Autowired
    PresenceStore presence;

    @BeforeEach
    void login() throws Exception {
        auth = bearer(loginAsAdmin());
    }

    private String createProduct(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"desc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productKey").value(org.hamcrest.Matchers.matchesPattern("[a-z0-9]{16}")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.productKey");
    }

    @Test
    void createGetUpdateDelete() throws Exception {
        String pk = createProduct("Meter A");

        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Meter A"))
                .andExpect(jsonPath("$.description").value("desc"));

        mvc.perform(put("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Meter B\",\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Meter B"));

        mvc.perform(delete("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIsPaged() throws Exception {
        createProduct("List 1");
        createProduct("List 2");

        mvc.perform(get("/api/v1/products").param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void listDefaultsToTenPerPage() throws Exception {
        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void deleteWithDevicesIsConflict() throws Exception {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");

        mvc.perform(delete("/api/v1/products/{pk}", device.productKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
    }

    @Test
    void otherTenantsProductIsNotFound() throws Exception {
        TestDevice foreign = createDevice(createTenant("other-"), "ENABLED");

        mvc.perform(get("/api/v1/products/{pk}", foreign.productKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankNameIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedPageIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("size", "1000").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void zeroPageIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/products").param("page", "0").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void codecDefaultsIsValidatedAndIsKeptWhenOmittedOnUpdate() throws Exception {
        String pk = createProduct("Codec A");
        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.codec").value("standard-json"))
                .andExpect(jsonPath("$.propertyCount").value(0));

        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bad\",\"codec\":\"nope\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Codec A\",\"codec\":\"flat-json\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.codec").value("flat-json"));
        mvc.perform(put("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Codec A2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.codec").value("flat-json"));
        mvc.perform(put("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Codec A\",\"codec\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithFlatJsonCodec() throws Exception {
        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Flat\",\"codec\":\"flat-json\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.codec").value("flat-json"));
    }

    @Test
    void propertyCountIsReportedInGetAndList() throws Exception {
        String pk = createProduct("Counted");
        defineProperty(pk, "Ua", "DOUBLE", null, null);
        defineProperty(pk, "Ub", "DOUBLE", null, null);

        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.propertyCount").value(2));
        mvc.perform(get("/api/v1/products").param("size", "100").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items[?(@.productKey == '" + pk + "')].propertyCount",
                        org.hamcrest.Matchers.contains(2)));
    }

    @Test
    void deviceAndOnlineCountsAreReportedInCreateGetAndList() throws Exception {
        String pk = createProduct("设备计数产品");
        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.onlineCount").value(0));

        long productId = jdbc.queryForObject("select id from product where product_key = ?", Long.class, pk);
        String online = insertDevice(productId, "ENABLED");
        String offline = insertDevice(productId, "ENABLED");
        insertDevice(productId, "DISABLED");
        long now = System.currentTimeMillis();
        presence.apply(online, true, now, "");
        presence.apply(offline, false, now, "");

        mvc.perform(get("/api/v1/products/{pk}", pk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(3))
                .andExpect(jsonPath("$.onlineCount").value(1));
        mvc.perform(get("/api/v1/products").param("size", "100").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items[?(@.productKey == '" + pk + "')].deviceCount",
                        org.hamcrest.Matchers.contains(3)))
                .andExpect(jsonPath("$.items[?(@.productKey == '" + pk + "')].onlineCount",
                        org.hamcrest.Matchers.contains(1)));
    }

    /** 直接写库在默认租户的指定产品下建一台设备，返回 deviceKey。 */
    private String insertDevice(long productId, String status) {
        String deviceKey = unique("dk");
        String salt = secretHasher.newSalt();
        long tenantId = defaultTenantId();
        jdbc.update("insert into device (tenant_id, product_id, site_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, (select id from site where tenant_id = ? and is_default), ?, '计数测试设备', ?, ?, ?)",
                tenantId, productId, tenantId, deviceKey, secretHasher.hash("secret-" + deviceKey, salt), salt, status);
        return deviceKey;
    }
}
