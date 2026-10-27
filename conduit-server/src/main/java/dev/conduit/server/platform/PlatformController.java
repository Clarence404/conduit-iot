package dev.conduit.server.platform;

import dev.conduit.server.auth.AuthDtos.TokenResponse;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.SessionService;
import dev.conduit.server.platform.PlatformDtos.CreateTenantRequest;
import dev.conduit.server.platform.PlatformDtos.PlatformOverview;
import dev.conduit.server.platform.PlatformDtos.TenantView;
import dev.conduit.server.platform.PlatformDtos.UpdateTenantRequest;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.user.UserDtos.PasswordRequest;
import dev.conduit.server.user.UserDtos.UserItem;
import dev.conduit.server.web.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台接口（/api/v1/platform，仅未代入的平台管理员，权限在 SecurityConfig）：平台概况与租户管理、进入租户。
 * 逻辑在 {@link PlatformOverviewService} 与 {@link TenantAdminService}。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformOverviewService overview;
    private final TenantAdminService tenants;
    private final SessionService sessions;

    public PlatformController(PlatformOverviewService overview, TenantAdminService tenants, SessionService sessions) {
        this.overview = overview;
        this.tenants = tenants;
        this.sessions = sessions;
    }

    /** 平台概况。 */
    @GetMapping("/overview")
    public PlatformOverview overview() {
        return overview.overview();
    }

    /** 分页列出租户（默认每页 10 条），可按名称/编码关键字与状态筛选。 */
    @GetMapping("/tenants")
    public PageView<TenantView> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") @Min(1) int page,
                                     @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return tenants.list(keyword, status, page, size);
    }

    /** 新建租户及其首个管理员（201）。 */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantView create(@Valid @RequestBody CreateTenantRequest request) {
        return tenants.create(request);
    }

    /** 修改租户名称。 */
    @PutMapping("/tenants/{code}")
    public TenantView rename(@PathVariable String code, @Valid @RequestBody UpdateTenantRequest request) {
        return tenants.rename(code, request);
    }

    /** 停用租户（幂等，默认租户 409），并清除其设备身份缓存。 */
    @PostMapping("/tenants/{code}/disable")
    public TenantView disable(@PathVariable String code) {
        return tenants.setStatus(code, Tenant.DISABLED);
    }

    /** 启用租户（幂等，默认租户 409），并清除其设备身份缓存。 */
    @PostMapping("/tenants/{code}/enable")
    public TenantView enable(@PathVariable String code) {
        return tenants.setStatus(code, Tenant.ACTIVE);
    }

    /** 删除租户（无产品与设备时；同时删除其用户，204）。 */
    @DeleteMapping("/tenants/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        tenants.delete(code);
    }

    /** 租户的全部用户（不分页，按角色、用户名排序）。 */
    @GetMapping("/tenants/{code}/users")
    public List<UserItem> users(@PathVariable String code) {
        return tenants.users(code);
    }

    /** 重置租户管理员的密码（仅 TENANT_ADMIN，否则 409）。 */
    @PostMapping("/tenants/{code}/users/{username}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String code, @PathVariable String username,
                              @Valid @RequestBody PasswordRequest request) {
        tenants.resetAdminPassword(code, username, request);
    }

    /** 进入租户：签发代入令牌（access + refresh Cookie）。 */
    @PostMapping("/tenants/{code}/enter")
    public ResponseEntity<TokenResponse> enter(@AuthenticationPrincipal AuthPrincipal actor,
                                               @PathVariable String code) {
        return sessions.continueSession(actor, tenants.enter(actor, code));
    }
}
