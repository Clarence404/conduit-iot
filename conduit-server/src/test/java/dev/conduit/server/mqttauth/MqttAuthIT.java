package dev.conduit.server.mqttauth;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MqttAuthIT extends IntegrationTestBase {

    private ResultActions auth(String token, String clientId, String username, String password) throws Exception {
        var request = post("/internal/mqtt/auth").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientid":"%s","username":"%s","password":"%s"}""".formatted(clientId, username, password));
        if (token != null) {
            request.header("X-Conduit-Internal-Token", token);
        }
        return mvc.perform(request);
    }

    @Test
    void allowsDeviceWithCorrectSecret() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"))
                .andExpect(jsonPath("$.is_superuser").value(false));
    }

    @Test
    void deniesWrongSecret() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), "wrong")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesDisabledDevice() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "DISABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesDevicesOfADisabledTenant() throws Exception {
        long tenant = createTenant("mq-");
        jdbc.update("update tenant set status = 'DISABLED' where id = ?", tenant);
        TestDevice d = createDevice(tenant, "ENABLED");

        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void disablingATenantTakesEffectDespiteTheCacheAndEnablingRestoresIt() throws Exception {
        long tenant = createTenant("mq-");
        String code = tenantCode(tenant);
        TestDevice d = createDevice(tenant, "ENABLED");
        String sys = loginAsPlatformAdmin();
        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("allow"));

        mvc.perform(post("/api/v1/platform/tenants/{c}/disable", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk());
        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));

        mvc.perform(post("/api/v1/platform/tenants/{c}/enable", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk());
        auth("test-internal-token", d.deviceKey(), d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("allow"));
    }

    @Test
    void deniesClientIdMismatch() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        auth("test-internal-token", "someone-else", d.deviceKey(), d.secret())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void deniesUnknownDevice() throws Exception {
        auth("test-internal-token", "ghost", "ghost", "x")
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void allowsTransportAsSuperuser() throws Exception {
        auth("test-internal-token", "conduit-transport-1", "conduit-transport", "transport-pass")
                .andExpect(jsonPath("$.result").value("allow"))
                .andExpect(jsonPath("$.is_superuser").value(true));
    }

    @Test
    void deniesTransportWithWrongPasswordOrClientId() throws Exception {
        auth("test-internal-token", "conduit-transport-1", "conduit-transport", "nope")
                .andExpect(jsonPath("$.result").value("deny"));
        auth("test-internal-token", "evil", "conduit-transport", "transport-pass")
                .andExpect(jsonPath("$.result").value("deny"));
    }

    @Test
    void rejectsMissingOrWrongInternalToken() throws Exception {
        auth(null, "c", "u", "p").andExpect(status().isUnauthorized());
        auth("wrong", "c", "u", "p").andExpect(status().isUnauthorized());
    }

    @Test
    void 内部监听器的回调只放行transport() throws Exception {
        TestDevice d = createDevice(defaultTenantId(), "ENABLED");

        mvc.perform(post("/internal/mqtt/auth").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Conduit-Internal-Token", "test-internal-token")
                        .header("X-Conduit-Listener", "internal")
                        .content("""
                                {"clientid":"%s","username":"%s","password":"%s"}""".formatted(
                                d.deviceKey(), d.deviceKey(), d.secret())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("deny"));
        mvc.perform(post("/internal/mqtt/auth").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Conduit-Internal-Token", "test-internal-token")
                        .header("X-Conduit-Listener", "internal")
                        .content("""
                                {"clientid":"conduit-transport-5","username":"conduit-transport","password":"transport-pass"}"""))
                .andExpect(jsonPath("$.result").value("allow"))
                .andExpect(jsonPath("$.is_superuser").value(true));
    }
}
