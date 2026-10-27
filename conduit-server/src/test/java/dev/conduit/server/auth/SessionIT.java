package dev.conduit.server.auth;

import dev.conduit.server.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话集成测试：登录返回完整用户视图；用户禁用、租户停用后登录与续期被拒；角色变化在续期后生效；
 * 平台管理员登录没有租户；代入令牌续期在租户可用时保持代入、停用或删除后退回平台管理员；退出租户；
 * 代入的平台管理员被禁用或不再是平台管理员时，续期与退出租户都返回 401。
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionIT extends IntegrationTestBase {

    @Autowired
    JwtService jwt;

    private Cookie refreshCookie(String username) throws Exception {
        return loginResult(username, TEST_PASSWORD).getResponse().getCookie(SessionService.REFRESH_COOKIE);
    }

    private Cookie impersonationRefresh(long tenantId) throws Exception {
        return mvc.perform(post("/api/v1/platform/tenants/{code}/enter", tenantCode(tenantId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(SessionService.REFRESH_COOKIE);
    }

    @Test
    void loginReturnsTheFullUserView() throws Exception {
        long tenant = createTenant("ss-");
        String viewer = createUser(tenant, Roles.TENANT_VIEWER, false);
        jdbc.update("update app_user set display_name = '华东运维 张工' where username = ?", viewer);

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(viewer, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(viewer))
                .andExpect(jsonPath("$.user.displayName").value("华东运维 张工"))
                .andExpect(jsonPath("$.user.role").value(Roles.TENANT_VIEWER))
                .andExpect(jsonPath("$.user.tenantCode").value(tenantCode(tenant)))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.impersonatedBy").isEmpty());
    }

    @Test
    void revokingOneSessionInvalidatesItsAccessTokenButLeavesAnotherSessionActive() throws Exception {
        String username = createUser(createTenant("ss-"), Roles.TENANT_VIEWER, false);
        var first = loginResult(username, TEST_PASSWORD).getResponse();
        var second = loginResult(username, TEST_PASSWORD).getResponse();
        String firstToken = com.jayway.jsonpath.JsonPath.read(first.getContentAsString(), "$.accessToken");
        String secondToken = com.jayway.jsonpath.JsonPath.read(second.getContentAsString(), "$.accessToken");
        String firstId = jwt.parse(firstToken, JwtService.TokenType.ACCESS).sessionId().toString();

        mvc.perform(delete("/api/v1/auth/sessions/{id}", firstId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(firstToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAndAuthorizationVersionInvalidateAccessImmediately() throws Exception {
        String username = createUser(createTenant("ss-"), Roles.TENANT_VIEWER, false);
        var login = loginResult(username, TEST_PASSWORD).getResponse();
        String token = com.jayway.jsonpath.JsonPath.read(login.getContentAsString(), "$.accessToken");
        mvc.perform(post("/api/v1/auth/logout").cookie(login.getCookie(SessionService.REFRESH_COOKIE)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());

        String newToken = com.jayway.jsonpath.JsonPath.read(
                loginResult(username, TEST_PASSWORD).getResponse().getContentAsString(), "$.accessToken");
        jdbc.update("update app_user set authz_version=authz_version+1 where username=?", username);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(newToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformAdminLogsInWithoutATenant() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(PLATFORM_ADMIN, PLATFORM_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.PLATFORM_ADMIN))
                .andExpect(jsonPath("$.user.tenantCode").isEmpty())
                .andExpect(jsonPath("$.user.tenantName").isEmpty());
    }

    @Test
    void disabledUsersCannotLogInOrRefresh() throws Exception {
        String viewer = createUser(createTenant("ss-"), Roles.TENANT_VIEWER, false);
        Cookie refresh = refreshCookie(viewer);

        jdbc.update("update app_user set status = 'DISABLED' where username = ?", viewer);

        assertThat(loginResult(viewer, TEST_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledTenantsBlockLoginAndRefresh() throws Exception {
        long tenant = createTenant("ss-");
        String admin = createUser(tenant, Roles.TENANT_ADMIN, false);
        Cookie refresh = refreshCookie(admin);

        jdbc.update("update tenant set status = 'DISABLED' where id = ?", tenant);

        assertThat(loginResult(admin, TEST_PASSWORD).getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("所属租户已停用");
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordDoesNotRevealADisabledAccount() throws Exception {
        String viewer = createUser(createTenant("ss-"), Roles.TENANT_VIEWER, false);
        jdbc.update("update app_user set status = 'DISABLED' where username = ?", viewer);

        assertThat(loginResult(viewer, "Wrong-pass-1").getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("用户名或密码错误");
    }

    @Test
    void roleChangesTakeEffectOnRefresh() throws Exception {
        String user = createUser(createTenant("ss-"), Roles.TENANT_VIEWER, false);
        Cookie refresh = refreshCookie(user);

        jdbc.update("update app_user set role = 'TENANT_ADMIN' where username = ?", user);

        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.TENANT_ADMIN));
    }

    @Test
    void impersonationRefreshKeepsAnActiveTenant() throws Exception {
        long tenant = createTenant("ss-");

        mvc.perform(post("/api/v1/auth/refresh").cookie(impersonationRefresh(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.TENANT_ADMIN))
                .andExpect(jsonPath("$.user.impersonatedBy").value(PLATFORM_ADMIN))
                .andExpect(jsonPath("$.user.tenantCode").value(tenantCode(tenant)));
    }

    @Test
    void impersonationRefreshFallsBackWhenTheTenantIsDisabled() throws Exception {
        long tenant = createTenant("ss-");
        Cookie refresh = impersonationRefresh(tenant);
        jdbc.update("update tenant set status = 'DISABLED' where id = ?", tenant);

        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.PLATFORM_ADMIN))
                .andExpect(jsonPath("$.user.impersonatedBy").isEmpty())
                .andExpect(jsonPath("$.user.tenantCode").isEmpty());
    }

    @Test
    void impersonationRefreshFallsBackWhenTheTenantIsDeleted() throws Exception {
        long tenant = createTenant("ss-");
        Cookie refresh = impersonationRefresh(tenant);
        jdbc.update("delete from tenant where id = ?", tenant);

        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.PLATFORM_ADMIN));
    }

    @Test
    void exitTenantReturnsThePlatformAdminSession(CapturedOutput output) throws Exception {
        long tenant = createTenant("ss-");
        String access = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/api/v1/platform/tenants/{code}/enter", tenantCode(tenant))
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(post("/api/v1/auth/exit-tenant").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("conduit_refresh=")))
                .andExpect(jsonPath("$.user.role").value(Roles.PLATFORM_ADMIN))
                .andExpect(jsonPath("$.user.impersonatedBy").isEmpty());
        assertThat(output).contains("exited tenant " + tenantCode(tenant));
    }

    @Test
    void exitTenantWithoutImpersonationIsAConflict() throws Exception {
        mvc.perform(post("/api/v1/auth/exit-tenant").header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isConflict());
    }

    private String impersonationAccess(long tenantId) {
        long adminId = userId(PLATFORM_ADMIN);
        return jwt.issue(new AuthPrincipal(adminId, tenantId, PLATFORM_ADMIN, Roles.TENANT_ADMIN, adminId, false),
                JwtService.TokenType.ACCESS);
    }

    @Test
    void exitTenantFailsWhenThePlatformAdminIsDisabled() throws Exception {
        String access = impersonationAccess(createTenant("ss-"));
        jdbc.update("update app_user set status = 'DISABLED' where username = ?", PLATFORM_ADMIN);
        try {
            mvc.perform(post("/api/v1/auth/exit-tenant").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("update app_user set status = 'ACTIVE' where username = ?", PLATFORM_ADMIN);
        }
    }

    @Test
    void exitTenantFailsWhenTheImpersonatorIsNoLongerAPlatformAdmin() throws Exception {
        long tenant = createTenant("ss-");
        String demoted = createUser(tenant, Roles.TENANT_ADMIN, false);
        long id = userId(demoted);
        String access = jwt.issue(new AuthPrincipal(id, tenant, demoted, Roles.TENANT_ADMIN, id, false),
                JwtService.TokenType.ACCESS);

        mvc.perform(post("/api/v1/auth/exit-tenant").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void impersonationRefreshFailsWhenThePlatformAdminIsDisabled() throws Exception {
        Cookie refresh = impersonationRefresh(createTenant("ss-"));
        jdbc.update("update app_user set status = 'DISABLED' where username = ?", PLATFORM_ADMIN);
        try {
            mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("update app_user set status = 'ACTIVE' where username = ?", PLATFORM_ADMIN);
        }
    }

    @Test
    void impersonationRefreshFailsWhenTheImpersonatorIsNoLongerAPlatformAdmin() throws Exception {
        long tenant = createTenant("ss-");
        String demoted = createUser(tenant, Roles.TENANT_ADMIN, false);
        long id = userId(demoted);
        String token = jwt.issue(new AuthPrincipal(id, tenant, demoted, Roles.TENANT_ADMIN, id, false),
                JwtService.TokenType.REFRESH);

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(SessionService.REFRESH_COOKIE, token)))
                .andExpect(status().isUnauthorized());
    }
}
