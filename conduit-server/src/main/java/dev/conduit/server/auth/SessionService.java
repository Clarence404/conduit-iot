package dev.conduit.server.auth;

import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthDtos.UserView;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import dev.conduit.server.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话的校验与签发：判断用户（及其租户）能否登录或续期，构造普通身份与代入身份，签发 access + refresh 令牌
 * （refresh 写入 HttpOnly Cookie），组装给 UI 的当前用户视图。登录、续期、进入/退出租户、修改密码都经由这里签发令牌。
 */
@Component
public class SessionService {
    @org.springframework.beans.factory.annotation.Autowired
    private dev.conduit.server.authorization.AuthorizationService authorization;

    /** refresh 令牌的 Cookie 名称（HttpOnly、SameSite=Strict、只发往 /api/v1/auth）。 */
    public static final String REFRESH_COOKIE = "conduit_refresh";

    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final JwtService jwt;
    private final JwtProperties props;

    public SessionService(AppUserMapper users, TenantMapper tenants, JwtService jwt, JwtProperties props) {
        this.users = users;
        this.tenants = tenants;
        this.jwt = jwt;
        this.props = props;
    }

    /**
     * 确认用户可以登录或续期：用户存在且启用；租户用户的租户存在且启用（平台管理员没有租户，不检查）。
     *
     * @return 原样返回用户
     * @throws ApiException 401：用户不存在或已禁用，所属租户不存在或已停用
     */
    public AppUser requireActive(AppUser user) {
        if (user == null) {
            throw ApiException.unauthorized("用户不可用");
        }
        if (!AppUser.ACTIVE.equals(user.getStatus())) {
            throw ApiException.unauthorized("账号已禁用");
        }
        if (user.getTenantId() != null) {
            Tenant tenant = tenants.selectById(user.getTenantId());
            if (tenant == null) {
                throw ApiException.unauthorized("所属租户不存在");
            }
            if (!Tenant.ACTIVE.equals(tenant.getStatus())) {
                throw ApiException.unauthorized("所属租户已停用");
            }
        }
        return user;
    }

    /** 用户自己的身份：角色、租户与「需改密码」标记都取数据库当前值。 */
    public AuthPrincipal principalOf(AppUser user) {
        return new AuthPrincipal(user.getId(), user.getTenantId(), user.getUsername(), user.getRole(), null,
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    /** 平台管理员代入目标租户的身份：有效角色 TENANT_ADMIN，imp 为平台管理员自己的 id，不带改密码标记。 */
    public AuthPrincipal impersonation(AppUser admin, Tenant tenant) {
        return new AuthPrincipal(admin.getId(), tenant.getId(), admin.getUsername(), Roles.TENANT_ADMIN,
                admin.getId(), false);
    }

    /** 签发令牌：refresh 写入 Cookie，access、有效期（秒）与当前用户视图放在响应体。 */
    public ResponseEntity<TokenResponse> issue(AuthPrincipal principal) {
        String access = jwt.issue(principal, JwtService.TokenType.ACCESS);
        String refresh = jwt.issue(principal, JwtService.TokenType.REFRESH);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refresh, props.refreshTtl()).toString())
                .body(new TokenResponse(access, props.accessTtl().toSeconds(), userView(principal)));
    }

    /** 清除 refresh Cookie（Max-Age=0），登出时使用。 */
    public ResponseCookie clearedRefreshCookie() {
        return refreshCookie("", Duration.ZERO);
    }

    /**
     * 组装当前用户视图：显示名称与「需改密码」取数据库当前值；租户编码与名称按令牌中的租户查（平台管理员为 null）；
     * 代入时 impersonatedBy 为平台管理员用户名，mustChangePassword 恒为 false。
     */
    public UserView userView(AuthPrincipal principal) {
        AppUser user = users.selectById(principal.userId());
        Tenant tenant = principal.tenantId() == null ? null : tenants.selectById(principal.tenantId());
        boolean mustChange = !principal.impersonating() && user != null
                && Boolean.TRUE.equals(user.getMustChangePassword());
        java.util.Set<String> permissions=null, tenantPermissions=null;
        boolean enforced=authorization!=null && authorization.enforcing() && principal.tenantId()!=null;
        if(enforced) {
            var scope=dev.conduit.server.tenant.TenantContext.callAs(principal.tenantId(),()->authorization.resolve(principal));
            permissions=scope.grants().stream().flatMap(g->g.permissions().stream()).filter(p->scope.writable() || p.endsWith(".read")).collect(java.util.stream.Collectors.toSet());
            tenantPermissions=scope.grants().stream().filter(g->g.scope().equals("TENANT"))
                    .flatMap(g->g.permissions().stream()).filter(p->scope.writable() || p.endsWith(".read")).collect(java.util.stream.Collectors.toSet());
        }
        return new UserView(principal.username(), user == null ? null : user.getDisplayName(), principal.role(),
                tenant == null ? null : tenant.getCode(), tenant == null ? null : tenant.getName(), mustChange,
                principal.impersonating() ? principal.username() : null,permissions,tenantPermissions,enforced);
    }

    /** refresh Cookie：HttpOnly、SameSite=Strict、仅发往 /api/v1/auth；Secure 由配置决定（生产应开启）。 */
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }
}
