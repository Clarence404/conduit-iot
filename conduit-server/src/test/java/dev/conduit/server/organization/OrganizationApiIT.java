package dev.conduit.server.organization;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B1 组织 API：完整层级、设备归属、层级保护和租户隔离。 */
class OrganizationApiIT extends IntegrationTestBase {
    private String auth;

    @BeforeEach
    void login() throws Exception {
        auth = bearer(loginAsAdmin());
    }

    @Test
    void createsHierarchyAssignsDeviceAndProtectsDependencies() throws Exception {
        postJson("/api/v1/customers", """
                {"code":"customer-a","name":"客户 A","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/projects", """
                {"code":"project-a","name":"项目 A","customerCode":"customer-a","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/sites", """
                {"code":"site-a","name":"站点 A","projectCode":"project-a","timezone":"Asia/Shanghai","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/assets", """
                {"code":"asset-root","name":"配电房","siteCode":"site-a","type":"ROOM","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/assets", """
                {"code":"asset-child","name":"配电柜","siteCode":"site-a","parentCode":"asset-root","type":"CABINET","status":"ACTIVE"}
                """).andExpect(status().isCreated());

        String productBody = postJson("/api/v1/products", "{\"name\":\"组织测试产品\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productKey = JsonPath.read(productBody, "$.productKey");
        String deviceBody = postJson("/api/v1/devices", """
                {"productKey":"%s","name":"组织测试设备","siteCode":"site-a","assetCode":"asset-child"}
                """.formatted(productKey)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String deviceKey = JsonPath.read(deviceBody, "$.device.deviceKey");

        mvc.perform(get("/api/v1/devices/{key}/assignment", deviceKey).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteCode").value("site-a"))
                .andExpect(jsonPath("$.assetCode").value("asset-child"));

        mvc.perform(delete("/api/v1/assets/asset-child").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/sites/site-a").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/projects/project-a").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/customers/customer-a").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsAssetCycleInvalidTimezoneAndOtherTenantResources() throws Exception {
        postJson("/api/v1/projects", """
                {"code":"project-cycle","name":"项目","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/sites", """
                {"code":"bad-timezone","name":"错误时区","projectCode":"project-cycle","timezone":"Mars/Base","status":"ACTIVE"}
                """).andExpect(status().isBadRequest());
        postJson("/api/v1/sites", """
                {"code":"site-cycle","name":"站点","projectCode":"project-cycle","timezone":"UTC","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/assets", """
                {"code":"parent-a","name":"A","siteCode":"site-cycle","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        postJson("/api/v1/assets", """
                {"code":"child-b","name":"B","siteCode":"site-cycle","parentCode":"parent-a","status":"ACTIVE"}
                """).andExpect(status().isCreated());
        putJson("/api/v1/assets/parent-a", """
                {"code":"parent-a","name":"A","siteCode":"site-cycle","parentCode":"child-b","status":"ACTIVE"}
                """).andExpect(status().isConflict());

        long other = createTenant("org-api-");
        jdbc.update("insert into customer (tenant_id, code, name) values (?, 'foreign-customer', 'foreign')", other);
        mvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'foreign-customer')]").isEmpty());
    }

    @Test
    void defaultProjectAndSiteCannotBeDeleted() throws Exception {
        mvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'default')]").isNotEmpty());
        mvc.perform(get("/api/v1/assets").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(delete("/api/v1/projects/default").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/sites/default").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path, String json) throws Exception {
        return mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
