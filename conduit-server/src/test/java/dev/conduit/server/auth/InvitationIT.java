package dev.conduit.server.auth;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.notification.InvitationNotificationChannel;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(InvitationIT.Delivery.class)
class InvitationIT extends IntegrationTestBase {
    private static final AtomicReference<String> SENT = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Delivery {
        @Bean InvitationNotificationChannel testChannel() {
            return new InvitationNotificationChannel() {
                public String key() { return "email"; }
                public void send(String address, String url) { SENT.set(url); }
            };
        }
    }

    @Test
    void invitationIsDeliveredAndCanBeAcceptedOnce() throws Exception {
        String admin = createUser(createTenant("iv-"), Roles.TENANT_ADMIN, false);
        String token = login(admin, TEST_PASSWORD);
        String username = unique("invite");
        SENT.set(null);
        mvc.perform(post("/api/v1/invitations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.test\",\"username\":\"%s\",\"role\":\"TENANT_VIEWER\"}".formatted(username)))
                .andExpect(status().isCreated());
        assertThat(SENT.get()).contains("/invitations/accept?token=");
        String inviteToken = SENT.get().split("token=")[1];
        assertThat(jdbc.queryForObject("select token_hash from user_invitation where username=?", String.class, username))
                .doesNotContain(inviteToken);
        mvc.perform(get("/api/v1/invitations/accept").param("token", inviteToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"Invite-pass-123\"}".formatted(inviteToken)))
                .andExpect(status().isNoContent());
        assertThat(login(username, "Invite-pass-123")).isNotBlank();
        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"Invite-pass-123\"}".formatted(inviteToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedAndExpiredInvitationsCannotBeAccepted() throws Exception {
        String admin = createUser(createTenant("iv-"), Roles.TENANT_ADMIN, false);
        String token = login(admin, TEST_PASSWORD);
        String username = unique("invite");
        String id = JsonPath.read(mvc.perform(post("/api/v1/invitations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.test\",\"username\":\"%s\",\"role\":\"TENANT_VIEWER\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String inviteToken = SENT.get().split("token=")[1];
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/invitations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/invitations/accept").param("token", inviteToken)).andExpect(status().isNotFound());
    }
}
