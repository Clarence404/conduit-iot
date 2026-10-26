package dev.conduit.server.authorization;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 回退开关必须保留范围、拒绝写入，不能退回旧管理员全权。 */
@SpringBootTest(properties="conduit.authorization.mode=read-only")
class AuthorizationReadOnlyIT extends IntegrationTestBase {
    @Test void administratorCanReadButCannotWriteOrAdvertiseWritePermission() throws Exception {
        String token=bearer(loginAsAdmin());
        mvc.perform(get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION,token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"回退不得写入\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationEnforced").value(true))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("device.manage"))));
    }
}
