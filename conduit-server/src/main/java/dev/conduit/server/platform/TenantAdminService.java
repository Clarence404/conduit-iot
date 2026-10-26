package dev.conduit.server.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.DisplayNamePolicy;
import dev.conduit.server.auth.PasswordPolicy;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.auth.SessionService;
import dev.conduit.server.auth.UsernamePolicy;
import dev.conduit.server.device.DeviceIdentityCache;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.organization.OrganizationProvisioner;
import dev.conduit.server.platform.PlatformDtos.AdminAccount;
import dev.conduit.server.platform.PlatformDtos.CreateTenantRequest;
import dev.conduit.server.platform.PlatformDtos.TenantView;
import dev.conduit.server.platform.PlatformDtos.UpdateTenantRequest;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantCodePolicy;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.tenant.TenantMapper;
import dev.conduit.server.tenant.system.PlatformStatsMapper;
import dev.conduit.server.user.UserDtos.PasswordRequest;
import dev.conduit.server.user.UserDtos.UserItem;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 平台管理员的租户管理：列表、新建（含首个管理员）、改名、停用/启用、删除、查看租户用户、重置租户管理员密码、进入租户。
 * 平台接口没有租户上下文：tenant 与 app_user 不做租户过滤，按租户检查产品/设备时用 TenantContext.callAs 显式绑定。
 * 默认租户 default 不能停用、启用或删除（409）。
 */
@Service
public class TenantAdminService {

    static final int NAME_MAX = 64;

    private static final Logger log = LoggerFactory.getLogger(TenantAdminService.class);

    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final ProductMapper products;
    private final DeviceMapper devices;
    private final PlatformStatsMapper statsMapper;
    private final TenantStats stats;
    private final DeviceIdentityCache identityCache;
    private final SessionService sessions;
    private final OrganizationProvisioner organizations;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public TenantAdminService(TenantMapper tenants, AppUserMapper users, ProductMapper products, DeviceMapper devices,
                              PlatformStatsMapper statsMapper, TenantStats stats, DeviceIdentityCache identityCache,
                              SessionService sessions, OrganizationProvisioner organizations,
                              PasswordEncoder encoder, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.products = products;
        this.devices = devices;
        this.statsMapper = statsMapper;
        this.stats = stats;
        this.identityCache = identityCache;
        this.sessions = sessions;
        this.organizations = organizations;
        this.encoder = encoder;
        this.clock = clock;
    }

    /**
     * 分页列出租户（新建的在前），带用户、管理员、产品、设备与在线计数。
     *
     * @param keyword 匹配名称或编码（可空）
     * @param status  ACTIVE / DISABLED（不区分大小写，可空）
     * @throws ApiException 400：status 不合法
     */
    public PageView<TenantView> list(String keyword, String status, int page, int size) {
        LambdaQueryWrapper<Tenant> query = new LambdaQueryWrapper<Tenant>().orderByDesc(Tenant::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like(Tenant::getName, kw).or().like(Tenant::getCode, kw));
        }
        if (status != null && !status.isBlank()) {
            query.eq(Tenant::getStatus, requireStatus(status));
        }
        Page<Tenant> result = tenants.selectPage(new Page<>(page, size), query);
        TenantStats.Snapshot snapshot = stats.snapshot();
        return new PageView<>(result.getRecords().stream().map(t -> view(t, snapshot)).toList(),
                result.getTotal(), page, size);
    }

    /**
     * 新建租户及其首个租户管理员（同一事务；管理员首次登录须修改密码）。用户名在租户插入之后检查，
     * 冲突时抛出的异常让整个事务回滚，不会留下没有管理员的租户。
     *
     * @throws ApiException 400：编码、名称、用户名、密码或显示名称不合规；409：编码或用户名已存在
     */
    @Transactional
    public TenantView create(CreateTenantRequest request) {
        String code = TenantCodePolicy.requireValid(request.code());
        String name = requireName(request.name());
        AdminAccount account = request.admin();
        String username = UsernamePolicy.requireValid(account.username());
        PasswordPolicy.requireValid(account.password());
        String displayName = DisplayNamePolicy.normalize(account.displayName());
        if (tenants.exists(new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, code))) {
            throw ApiException.conflict("租户编码已存在: " + code);
        }
        Tenant tenant = new Tenant();
        tenant.setCode(code);
        tenant.setName(name);
        tenant.setStatus(Tenant.ACTIVE);
        tenants.insert(tenant);
        organizations.provision(tenant.getId());
        if (users.exists(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username))) {
            throw ApiException.conflict("用户名已存在: " + username);
        }
        AppUser admin = new AppUser();
        admin.setTenantId(tenant.getId());
        admin.setUsername(username);
        admin.setDisplayName(displayName);
        admin.setPasswordHash(encoder.encode(account.password()));
        admin.setRole(Roles.TENANT_ADMIN);
        admin.setStatus(AppUser.ACTIVE);
        admin.setMustChangePassword(true);
        users.insert(admin);
        log.info("Created tenant {} with admin {}", code, username);
        return view(tenants.selectById(tenant.getId()), stats.snapshot());
    }

    /**
     * 修改租户名称。
     *
     * @throws ApiException 404：租户不存在；400：名称为空或超过 64 个字符
     */
    public TenantView rename(String code, UpdateTenantRequest request) {
        Tenant tenant = require(code);
        String name = requireName(request.name());
        tenants.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenant.getId())
                .set(Tenant::getName, name)
                .set(Tenant::getUpdatedAt, OffsetDateTime.now(clock)));
        log.info("Renamed tenant {} to {}", code, name);
        return view(require(code), stats.snapshot());
    }

    /**
     * 停用或启用租户（重复操作幂等），并清除该租户全部设备的身份缓存，使 MQTT 鉴权立即按新状态判断。
     * 停用后其用户登录与续期被拒、设备连接被拒、已连接设备的上报进入 TENANT_DISABLED 死信。
     *
     * @param status ACTIVE 或 DISABLED
     * @throws ApiException 404：租户不存在；409：默认租户
     */
    public TenantView setStatus(String code, String status) {
        Tenant tenant = require(code);
        if (Tenant.DEFAULT_CODE.equals(tenant.getCode())) {
            throw ApiException.conflict("默认租户不能停用或启用");
        }
        tenants.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenant.getId())
                .set(Tenant::getStatus, status)
                .set(Tenant::getUpdatedAt, OffsetDateTime.now(clock)));
        evictIdentities(tenant);
        log.info("Set status of tenant {} to {}", code, status);
        return view(require(code), stats.snapshot());
    }

    /**
     * 删除租户及其用户（同一事务）。
     *
     * @throws ApiException 404：租户不存在；409：默认租户，或租户下仍有产品或设备
     */
    @Transactional
    public void delete(String code) {
        Tenant tenant = require(code);
        if (Tenant.DEFAULT_CODE.equals(tenant.getCode())) {
            throw ApiException.conflict("默认租户不能删除");
        }
        boolean hasData = TenantContext.callAs(tenant.getId(),
                () -> products.exists(null) || devices.exists(null));
        if (hasData) {
            throw ApiException.conflict("租户下仍有产品或设备，不能删除");
        }
        int removedUsers = users.delete(new LambdaQueryWrapper<AppUser>().eq(AppUser::getTenantId, tenant.getId()));
        tenants.deleteById(tenant.getId());
        log.info("Deleted tenant {} and its {} users", code, removedUsers);
    }

    /**
     * 列出租户的全部用户（不分页，按角色、用户名排序）。
     *
     * @throws ApiException 404：租户不存在
     */
    public List<UserItem> users(String code) {
        Tenant tenant = require(code);
        return users.selectList(new LambdaQueryWrapper<AppUser>()
                        .eq(AppUser::getTenantId, tenant.getId())
                        .orderByAsc(AppUser::getRole)
                        .orderByAsc(AppUser::getUsername))
                .stream().map(UserItem::of).toList();
    }

    /**
     * 重置租户管理员的密码，并要求其下次登录修改。
     *
     * @throws ApiException 404：租户或用户不存在；409：用户不是租户管理员；400：密码不合规
     */
    @Transactional
    public void resetAdminPassword(String code, String username, PasswordRequest request) {
        Tenant tenant = require(code);
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getTenantId, tenant.getId())
                .eq(AppUser::getUsername, username));
        if (user == null) {
            throw ApiException.notFound("用户不存在: " + username);
        }
        if (!Roles.TENANT_ADMIN.equals(user.getRole())) {
            throw ApiException.conflict("只能重置租户管理员的密码");
        }
        PasswordPolicy.requireValid(request.password());
        users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, user.getId())
                .set(AppUser::getPasswordHash, encoder.encode(request.password()))
                .set(AppUser::getMustChangePassword, true)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        log.info("Platform reset the password of tenant admin {} in tenant {}", username, code);
    }

    /**
     * 平台管理员进入租户：返回代入身份（有效角色 TENANT_ADMIN），记一行 INFO。
     *
     * @throws ApiException 404：租户不存在；409：租户已停用；401：平台管理员已不可用
     */
    public AuthPrincipal enter(AuthPrincipal actor, String code) {
        Tenant tenant = require(code);
        if (!Tenant.ACTIVE.equals(tenant.getStatus())) {
            throw ApiException.conflict("租户已停用，不能进入");
        }
        AppUser admin = sessions.requireActive(users.selectById(actor.userId()));
        log.info("Platform admin {} entered tenant {}", admin.getUsername(), code);
        return sessions.impersonation(admin, tenant);
    }

    /** 按编码取租户，不存在抛 404。 */
    public Tenant require(String code) {
        Tenant tenant = tenants.selectOne(new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, code));
        if (tenant == null) {
            throw ApiException.notFound("租户不存在: " + code);
        }
        return tenant;
    }

    /** 清除租户全部设备的身份缓存；Redis 不可用时只记 WARN（缓存 5 分钟后自然过期）。 */
    private void evictIdentities(Tenant tenant) {
        List<String> deviceKeys = TenantContext.callAsSystem("tenant-device-keys",
                () -> statsMapper.deviceKeysOf(tenant.getId()));
        try {
            identityCache.evictAll(deviceKeys);
        } catch (DataAccessException e) {
            log.warn("Failed to evict device identities of tenant {}", tenant.getCode(), e);
        }
    }

    /** 组装租户视图。 */
    private static TenantView view(Tenant t, TenantStats.Snapshot snapshot) {
        TenantStats.TenantCounts c = snapshot.of(t.getId());
        return new TenantView(t.getCode(), t.getName(), t.getStatus(), c.adminCount(), c.userCount(),
                c.productCount(), c.deviceCount(), c.onlineCount(), t.getCreatedAt());
    }

    /** 校验并规范化租户名称（去首尾空白，1～64 个字符），非法抛 400。 */
    static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest("租户名称不能为空");
        }
        if (trimmed.length() > NAME_MAX) {
            throw ApiException.badRequest("租户名称最多 64 个字符");
        }
        return trimmed;
    }

    /** 校验租户状态（不区分大小写），返回大写值；非法抛 400。 */
    static String requireStatus(String status) {
        String upper = status.trim().toUpperCase(Locale.ROOT);
        if (!Tenant.ACTIVE.equals(upper) && !Tenant.DISABLED.equals(upper)) {
            throw ApiException.badRequest("状态只能是 ACTIVE 或 DISABLED");
        }
        return upper;
    }
}
