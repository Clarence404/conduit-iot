package dev.conduit.server.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.AuthDtos.LoginRequest;
import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthDtos.UserView;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import dev.conduit.server.web.ApiException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST 接口（UI 使用）：登录签发短期 access token（响应体）与长期 refresh token（HttpOnly Cookie），刷新、登出、
 * 查询当前用户，以及平台管理员退出代入的租户。登录、刷新、登出公开，其余需要登录。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final SessionService sessions;

    public AuthController(AppUserMapper users, TenantMapper tenants, PasswordEncoder encoder, JwtService jwt,
                          SessionService sessions) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.sessions = sessions;
    }

    /**
     * 用户名密码登录。用户不存在或密码错误返回同一条 401「用户名或密码错误」（不泄露账号是否存在）；
     * 密码正确后才检查账号禁用、租户停用（各自的 401）。需改密码的用户照常登录，令牌带 mcp。
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, request.username()));
        if (user == null || !encoder.matches(request.password(), user.getPasswordHash())) {
            log.debug("Login rejected: bad credentials");
            throw ApiException.unauthorized("用户名或密码错误");
        }
        sessions.requireActive(user);
        log.debug("Login succeeded for {} (role={})", user.getUsername(), user.getRole());
        return sessions.issue(sessions.principalOf(user));
    }

    /**
     * 用 refresh Cookie 换发新的令牌（滚动续期），以数据库当前状态为准：用户禁用、租户停用 401，角色变化在此生效。
     * 代入令牌：平台管理员仍可用时，目标租户可用则继续代入，已停用或已删除则退回平台管理员自己的令牌（INFO 日志）。
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = SessionService.REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("缺少刷新凭证");
        }
        AuthPrincipal principal;
        try {
            principal = jwt.parse(refreshToken, JwtService.TokenType.REFRESH);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("刷新凭证无效");
        }
        if (principal.impersonating()) {
            return refreshImpersonation(principal, refreshToken);
        }
        AppUser user = sessions.requireActive(users.selectById(principal.userId()));
        return sessions.refresh(principal, sessions.principalOf(user), refreshToken);
    }

    /** 代入令牌的续期（规则见 {@link #refresh}）。 */
    private ResponseEntity<TokenResponse> refreshImpersonation(AuthPrincipal principal, String refreshToken) {
        AppUser admin = users.selectById(principal.impersonatorId());
        if (admin == null || !Roles.PLATFORM_ADMIN.equals(admin.getRole())) {
            throw ApiException.unauthorized("用户不可用");
        }
        sessions.requireActive(admin);
        Tenant tenant = principal.tenantId() == null ? null : tenants.selectById(principal.tenantId());
        if (tenant == null || !Tenant.ACTIVE.equals(tenant.getStatus())) {
            log.info("Platform admin {} left tenant id={} on refresh: tenant {}", admin.getUsername(),
                    principal.tenantId(), tenant == null ? "deleted" : "disabled");
            return sessions.refresh(principal, sessions.principalOf(admin), refreshToken);
        }
        return sessions.refresh(principal, sessions.impersonation(admin, tenant), refreshToken);
    }

    /** 登出：撤销服务端会话并清除 refresh Cookie；access token 下一请求即失效。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal,
            @CookieValue(name = SessionService.REFRESH_COOKIE, required = false) String refreshToken) {
        if (principal == null && refreshToken != null) {
            try {
                principal = jwt.parse(refreshToken, JwtService.TokenType.REFRESH);
            } catch (JwtException | IllegalArgumentException ignored) {
                // Always clear the browser cookie, even if it has expired.
            }
        }
        sessions.revoke(principal);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessions.clearedRefreshCookie().toString())
                .build();
    }

    /** 当前登录用户（含显示名称、租户、是否需改密码、代入者）。 */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthPrincipal principal) {
        return sessions.userView(principal);
    }

    /**
     * 平台管理员退出代入的租户：换回自己的令牌，记一行 INFO。
     *
     * @throws ApiException 409：当前令牌不是代入令牌；401：平台管理员已被删除、禁用或不再是平台管理员（与代入续期同一检查）
     */
    @PostMapping("/exit-tenant")
    public ResponseEntity<TokenResponse> exitTenant(@AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.impersonating()) {
            throw ApiException.conflict("当前没有进入任何租户");
        }
        AppUser admin = users.selectById(principal.impersonatorId());
        if (admin == null || !Roles.PLATFORM_ADMIN.equals(admin.getRole())) {
            throw ApiException.unauthorized("用户不可用");
        }
        sessions.requireActive(admin);
        Tenant tenant = tenants.selectById(principal.tenantId());
        log.info("Platform admin {} exited tenant {}", admin.getUsername(),
                tenant == null ? String.valueOf(principal.tenantId()) : tenant.getCode());
        return sessions.continueSession(principal, sessions.principalOf(admin));
    }
}
