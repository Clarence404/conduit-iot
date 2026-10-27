package dev.conduit.server.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.DisplayNamePolicy;
import dev.conduit.server.auth.PasswordPolicy;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.auth.UsernamePolicy;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.tenant.TenantMapper;
import dev.conduit.server.user.UserDtos.CreateUserRequest;
import dev.conduit.server.user.UserDtos.PasswordRequest;
import dev.conduit.server.user.UserDtos.UpdateUserRequest;
import dev.conduit.server.user.UserDtos.UserItem;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 租户用户管理（当前租户，由令牌绑定的 TenantContext 决定）：列表、新建、编辑、启用/禁用、重置密码、删除。
 * app_user 不做租户行过滤，因此所有查询显式带 tenant_id。保护规则（409）：不能删除、禁用自己或修改自己的角色
 * （代入的平台管理员不算本租户用户，不受此限）；本租户最后一个启用的租户管理员不能被降级、禁用或删除
 * （在租户行锁内检查，防止并发绕过）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final PasswordEncoder encoder;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private dev.conduit.server.authorization.AuthorizationService authorization;
    @org.springframework.beans.factory.annotation.Autowired
    private dev.conduit.server.auth.UserSessionRegistry sessionRegistry;

    public UserService(AppUserMapper users, TenantMapper tenants, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
        this.clock = clock;
    }

    /**
     * 分页列出当前租户的用户，按角色、用户名排序。
     *
     * @param keyword 匹配用户名或显示名称（可空）
     * @param role    TENANT_ADMIN / TENANT_VIEWER（不区分大小写，可空）
     * @param status  ACTIVE / DISABLED（不区分大小写，可空）
     * @throws ApiException 400：role 或 status 不合法
     */
    public PageView<UserItem> list(String keyword, String role, String status, int page, int size) {
        long tenantId = TenantContext.require();
        LambdaQueryWrapper<AppUser> query = new LambdaQueryWrapper<AppUser>().eq(AppUser::getTenantId, tenantId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(AppUser::getUsername, kw).or().like(AppUser::getDisplayName, kw));
        }
        if (role != null && !role.isBlank()) {
            query.eq(AppUser::getRole, requireTenantRole(role));
        }
        if (status != null && !status.isBlank()) {
            query.eq(AppUser::getStatus, requireStatus(status));
        }
        query.orderByAsc(AppUser::getRole).orderByAsc(AppUser::getUsername);
        Page<AppUser> result = users.selectPage(new Page<>(page, size), query);
        return new PageView<>(result.getRecords().stream().map(UserItem::of).toList(), result.getTotal(), page, size);
    }

    /**
     * 在当前租户新建用户，要求首次登录修改密码。
     *
     * @throws ApiException 400：用户名、角色、密码或显示名称不合规；409：用户名已存在（全平台唯一）
     */
    @Transactional
    public UserItem create(CreateUserRequest request) {
        long tenantId = TenantContext.require();
        String username = UsernamePolicy.requireValid(request.username());
        String role = requireTenantRole(request.role());
        PasswordPolicy.requireValid(request.password());
        String displayName = DisplayNamePolicy.normalize(request.displayName());
        if (users.exists(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username))) {
            throw ApiException.conflict("用户名已存在: " + username);
        }
        AppUser user = new AppUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(encoder.encode(request.password()));
        user.setRole(role);
        user.setStatus(AppUser.ACTIVE);
        user.setMustChangePassword(true);
        users.insert(user);
        log.info("Created user {} (role={}) in tenant id={}", username, role, tenantId);
        return UserItem.of(users.selectById(user.getId()));
    }

    /**
     * 修改显示名称与角色。
     *
     * @throws ApiException 404：用户不在当前租户；409：修改自己的角色，或降级最后一个启用的租户管理员
     */
    @Transactional
    public UserItem update(AuthPrincipal actor, String username, UpdateUserRequest request) {
        long tenantId = lockTenant();
        AppUser target = require(tenantId, username);
        String role = requireTenantRole(request.role());
        String displayName = DisplayNamePolicy.normalize(request.displayName());
        boolean roleChanged = !role.equals(target.getRole());
        if(authorization!=null && authorization.enforcing()) authorization.guardUserChange(username,roleChanged,actor);
        if (roleChanged) {
            if (isSelf(actor, target)) {
                throw ApiException.conflict("不能修改自己的角色");
            }
            guardLastAdmin(tenantId, target, role, target.getStatus());
        }
        users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, target.getId())
                .set(AppUser::getDisplayName, displayName)
                .set(AppUser::getRole, role)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        if (roleChanged) {
            sessionRegistry.bumpVersion(target.getId());
            log.info("Changed role of user {} from {} to {} by {}", username, target.getRole(), role, actor.username());
        }
        return UserItem.of(users.selectById(target.getId()));
    }

    /**
     * 启用或禁用用户（重复操作幂等）。状态变化递增授权版本，旧令牌下一请求失效。
     *
     * @param status ACTIVE 或 DISABLED
     * @throws ApiException 404：用户不在当前租户；409：禁用自己，或禁用最后一个启用的租户管理员
     */
    @Transactional
    public UserItem setStatus(AuthPrincipal actor, String username, String status) {
        long tenantId = lockTenant();
        AppUser target = require(tenantId, username);
        if(authorization!=null && authorization.enforcing()) authorization.guardUserChange(username,AppUser.DISABLED.equals(status),actor);
        if (AppUser.DISABLED.equals(status) && AppUser.ACTIVE.equals(target.getStatus())) {
            if (isSelf(actor, target)) {
                throw ApiException.conflict("不能禁用自己");
            }
            guardLastAdmin(tenantId, target, target.getRole(), AppUser.DISABLED);
        }
        users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, target.getId())
                .set(AppUser::getStatus, status)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        if (!status.equals(target.getStatus())) sessionRegistry.bumpVersion(target.getId());
        log.info("Set status of user {} to {} by {}", username, status, actor.username());
        return UserItem.of(users.selectById(target.getId()));
    }

    /**
     * 重置密码并要求对方下次登录修改。
     *
     * <p>不加租户锁：该操作不改变角色与状态，不会影响「最后一个启用的租户管理员」判断，
     * 与其他用户管理操作并发时互不依赖，无需串行化。
     *
     * @throws ApiException 404：用户不在当前租户（含更新时恰好被并发删除，影响 0 行）；400：密码不合规
     */
    @Transactional
    public void resetPassword(AuthPrincipal actor, String username, PasswordRequest request) {
        long tenantId = TenantContext.require();
        AppUser target = require(tenantId, username);
        if(authorization!=null && authorization.enforcing()) authorization.guardUserChange(username,false,actor);
        PasswordPolicy.requireValid(request.password());
        int updated = users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, target.getId())
                .set(AppUser::getPasswordHash, encoder.encode(request.password()))
                .set(AppUser::getMustChangePassword, true)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        if (updated == 0) {
            throw ApiException.notFound("用户不存在: " + username);
        }
        sessionRegistry.bumpVersion(target.getId());
        sessionRegistry.revokeAll(target.getId());
        log.info("Reset password of user {} by {}", username, actor.username());
    }

    /**
     * 删除用户。
     *
     * @throws ApiException 404：用户不在当前租户；409：删除自己，或删除最后一个启用的租户管理员
     */
    @Transactional
    public void delete(AuthPrincipal actor, String username) {
        long tenantId = lockTenant();
        AppUser target = require(tenantId, username);
        if(authorization!=null && authorization.enforcing()) authorization.guardUserChange(username,true,actor);
        if (isSelf(actor, target)) {
            throw ApiException.conflict("不能删除自己");
        }
        guardLastAdmin(tenantId, target, null, null);
        users.deleteById(target.getId());
        log.info("Deleted user {} from tenant id={} by {}", username, tenantId, actor.username());
    }

    /** 锁定当前租户行并返回租户 id（必须在事务中调用）。 */
    private long lockTenant() {
        long tenantId = TenantContext.require();
        tenants.lockById(tenantId);
        return tenantId;
    }

    /** 取当前租户中的用户，不存在（含属于其他租户、平台管理员）抛 404。 */
    private AppUser require(long tenantId, String username) {
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getTenantId, tenantId)
                .eq(AppUser::getUsername, username));
        if (user == null) {
            throw ApiException.notFound("用户不存在: " + username);
        }
        return user;
    }

    /**
     * 「最后一个启用的租户管理员」保护：目标当前是启用的租户管理员、而操作后不再是（newRole/newStatus 为 null 表示删除）时，
     * 若本租户启用的租户管理员只剩它一个则抛 409。
     */
    private void guardLastAdmin(long tenantId, AppUser target, String newRole, String newStatus) {
        boolean activeAdminNow = Roles.TENANT_ADMIN.equals(target.getRole()) && AppUser.ACTIVE.equals(target.getStatus());
        boolean activeAdminAfter = Roles.TENANT_ADMIN.equals(newRole) && AppUser.ACTIVE.equals(newStatus);
        if (!activeAdminNow || activeAdminAfter) {
            return;
        }
        long activeAdmins = users.selectCount(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getTenantId, tenantId)
                .eq(AppUser::getRole, Roles.TENANT_ADMIN)
                .eq(AppUser::getStatus, AppUser.ACTIVE));
        if (activeAdmins <= 1) {
            throw ApiException.conflict("租户至少需要保留一个启用的租户管理员");
        }
    }

    /** 操作者是否就是目标用户本人（代入的平台管理员不算本租户用户）。 */
    static boolean isSelf(AuthPrincipal actor, AppUser target) {
        return !actor.impersonating() && actor.userId() == target.getId();
    }

    /** 校验租户角色（不区分大小写），返回大写值；非法抛 400。 */
    static String requireTenantRole(String role) {
        String upper = role == null ? null : role.trim().toUpperCase(Locale.ROOT);
        if (!Roles.isTenantRole(upper)) {
            throw ApiException.badRequest("角色只能是 TENANT_ADMIN 或 TENANT_VIEWER");
        }
        return upper;
    }

    /** 校验用户状态（不区分大小写），返回大写值；非法抛 400。 */
    static String requireStatus(String status) {
        String upper = status.trim().toUpperCase(Locale.ROOT);
        if (!AppUser.ACTIVE.equals(upper) && !AppUser.DISABLED.equals(upper)) {
            throw ApiException.badRequest("状态只能是 ACTIVE 或 DISABLED");
        }
        return upper;
    }
}
