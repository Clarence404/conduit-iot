package dev.conduit.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserSessionRegistryTest {
    @Test
    void productionModeRejectsLegacyJwtWithoutSessionId() {
        var registry = new UserSessionRegistry(mock(JdbcTemplate.class), Clock.systemUTC(), false);
        assertThat(registry.valid(new AuthPrincipal(1, 1L, "member", Roles.TENANT_VIEWER))).isFalse();
    }
}
