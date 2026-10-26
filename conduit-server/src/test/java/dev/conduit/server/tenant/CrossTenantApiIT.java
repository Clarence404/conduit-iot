package dev.conduit.server.tenant;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接口级越权测试：默认租户令牌访问另一租户的路径资源一律返回 404，列表与总览不泄露数据。 */
class CrossTenantApiIT extends IntegrationTestBase {

    @Test
    void foreignProductDeviceAndUserResourcesAreHidden() throws Exception {
        String auth = bearer(loginAsAdmin());
        long foreignTenant = createTenant("cross-");
        TestDevice foreign = createDevice(foreignTenant, "ENABLED");
        String foreignUser = createUser(foreignTenant, "TENANT_VIEWER", false);
        jdbc.update("""
                insert into product_property (tenant_id, product_id, identifier, name, data_type)
                select tenant_id, id, 'Ua', 'A相电压', 'DOUBLE' from product where product_key = ?
                """, foreign.productKey());

        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/v1/products/{key}", foreign.productKey()),
                put("/api/v1/products/{key}", foreign.productKey()).content(jsonProduct()),
                delete("/api/v1/products/{key}", foreign.productKey()),
                get("/api/v1/products/{key}/properties", foreign.productKey()),
                post("/api/v1/products/{key}/properties", foreign.productKey()).content(jsonProperty()),
                put("/api/v1/products/{key}/properties/Ua", foreign.productKey()).content(jsonProperty()),
                delete("/api/v1/products/{key}/properties/Ua", foreign.productKey()),
                get("/api/v1/products/{key}/thing-model", foreign.productKey()),
                put("/api/v1/products/{key}/thing-model", foreign.productKey()).content("{\"properties\":[]}"),
                get("/api/v1/devices/{key}", foreign.deviceKey()),
                put("/api/v1/devices/{key}", foreign.deviceKey())
                        .content("{\"name\":\"foreign\",\"status\":\"ENABLED\"}"),
                delete("/api/v1/devices/{key}", foreign.deviceKey()),
                post("/api/v1/devices/{key}/reset-secret", foreign.deviceKey()),
                get("/api/v1/devices/{key}/latest", foreign.deviceKey()),
                get("/api/v1/devices/{key}/history", foreign.deviceKey()).param("properties", "Ua"),
                put("/api/v1/users/{username}", foreignUser)
                        .content("{\"displayName\":\"foreign\",\"role\":\"TENANT_VIEWER\"}"),
                post("/api/v1/users/{username}/disable", foreignUser),
                post("/api/v1/users/{username}/enable", foreignUser),
                post("/api/v1/users/{username}/reset-password", foreignUser)
                        .content("{\"password\":\"Other-pass-123\"}"),
                delete("/api/v1/users/{username}", foreignUser));

        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"%s\",\"name\":\"cross\"}".formatted(foreign.productKey())))
                .andExpect(status().isNotFound());

        assertTenantListsAndOverviewExclude(auth, foreign, foreignUser);
    }

    /** 列表响应不出现外租户标识，总览计数等于默认租户数据库实数。 */
    private void assertTenantListsAndOverviewExclude(String auth, TestDevice foreign, String foreignUser)
            throws Exception {
        for (String path : List.of("/api/v1/products?size=100", "/api/v1/devices?size=100",
                "/api/v1/users?size=100")) {
            String body = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(foreign.productKey(), foreign.deviceKey(), foreignUser);
        }
        long tenant = defaultTenantId();
        int products = jdbc.queryForObject("select count(*) from product where tenant_id = ?", Integer.class, tenant);
        int devices = jdbc.queryForObject("select count(*) from device where tenant_id = ?", Integer.class, tenant);
        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(products))
                .andExpect(jsonPath("$.deviceCount").value(devices));
    }

    private static String jsonProduct() {
        return "{\"name\":\"foreign\"}";
    }

    private static String jsonProperty() {
        return "{\"identifier\":\"Ua\",\"name\":\"A相电压\",\"dataType\":\"DOUBLE\"}";
    }
}
