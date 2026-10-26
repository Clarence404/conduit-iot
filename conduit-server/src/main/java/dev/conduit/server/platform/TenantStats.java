package dev.conduit.server.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.tenant.system.PlatformStatsMapper.TenantCount;
import dev.conduit.server.tenant.system.PlatformStatsMapper.TenantDevice;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.tenant.system.PlatformStatsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按租户统计用户数、租户管理员数、产品数、设备数与在线设备数（跨租户，供平台概况与租户列表使用）。
 * 用户、产品、设备来自 PostgreSQL，在线状态来自 Redis（一次管道批量读取）；Redis 不可用时在线数为 null，其余照常。
 * 本期读出全平台设备再统计，开销为 O(设备数)，规模变大时改增量计数（见 backlog）。
 */
@Component
public class TenantStats {

    private static final Logger log = LoggerFactory.getLogger(TenantStats.class);

    private final AppUserMapper users;
    private final PlatformStatsMapper statsMapper;
    private final PresenceStore presence;

    public TenantStats(AppUserMapper users, PlatformStatsMapper statsMapper, PresenceStore presence) {
        this.users = users;
        this.statsMapper = statsMapper;
        this.presence = presence;
    }

    /**
     * 一个租户的计数。
     *
     * @param userCount   租户用户数（不含平台管理员）
     * @param adminCount  租户管理员数（含已禁用的）
     * @param onlineCount 在线设备数；Redis 不可用时为 null
     */
    public record TenantCounts(int userCount, int adminCount, int productCount, int deviceCount, Integer onlineCount) {
    }

    /**
     * 一次统计的结果。
     *
     * @param byTenant    有数据的租户的计数，键为租户 id
     * @param onlineCount 全平台在线设备数；Redis 不可用时为 null
     */
    public record Snapshot(Map<Long, TenantCounts> byTenant, Integer onlineCount) {

        /** 指定租户的计数；没有任何数据的租户为全 0（Redis 不可用时在线数为 null）。 */
        public TenantCounts of(long tenantId) {
            return byTenant.getOrDefault(tenantId, new TenantCounts(0, 0, 0, 0, onlineCount == null ? null : 0));
        }

        /** 全平台租户用户数。 */
        public int userCount() {
            return byTenant.values().stream().mapToInt(TenantCounts::userCount).sum();
        }

        /** 全平台产品数。 */
        public int productCount() {
            return byTenant.values().stream().mapToInt(TenantCounts::productCount).sum();
        }

        /** 全平台设备数。 */
        public int deviceCount() {
            return byTenant.values().stream().mapToInt(TenantCounts::deviceCount).sum();
        }
    }

    /** 统计全平台（规则见类注释）。 */
    public Snapshot snapshot() {
        Map<Long, int[]> acc = new HashMap<>();
        List<AppUser> tenantUsers = users.selectList(new QueryWrapper<AppUser>()
                .select("tenant_id", "role")
                .isNotNull("tenant_id"));
        for (AppUser u : tenantUsers) {
            int[] a = slot(acc, u.getTenantId());
            a[0]++;
            if (Roles.TENANT_ADMIN.equals(u.getRole())) {
                a[1]++;
            }
        }
        SystemStats systemStats = TenantContext.callAsSystem("platform-stats",
                () -> new SystemStats(statsMapper.productCounts(), statsMapper.devices()));
        for (TenantCount c : systemStats.productCounts()) {
            slot(acc, c.tenantId())[2] += (int) c.n();
        }
        List<TenantDevice> devices = systemStats.devices();
        for (TenantDevice d : devices) {
            slot(acc, d.tenantId())[3]++;
        }
        Set<String> online = onlineKeys(devices);
        if (online != null) {
            for (TenantDevice d : devices) {
                if (online.contains(d.deviceKey())) {
                    slot(acc, d.tenantId())[4]++;
                }
            }
        }
        Map<Long, TenantCounts> byTenant = new HashMap<>();
        acc.forEach((tenantId, a) -> byTenant.put(tenantId,
                new TenantCounts(a[0], a[1], a[2], a[3], online == null ? null : a[4])));
        return new Snapshot(Map.copyOf(byTenant), online == null ? null : online.size());
    }

    /** 一次系统模式查询取得的平台产品、设备统计原始数据。 */
    private record SystemStats(List<TenantCount> productCounts, List<TenantDevice> devices) {
    }

    /** 取某租户的累加槽位：[用户, 管理员, 产品, 设备, 在线]。 */
    private static int[] slot(Map<Long, int[]> acc, long tenantId) {
        return acc.computeIfAbsent(tenantId, id -> new int[5]);
    }

    /** 在线设备集合；没有设备时不访问 Redis 直接返回空集合；Redis 不可用时记录告警并返回 null。 */
    private Set<String> onlineKeys(List<TenantDevice> devices) {
        if (devices.isEmpty()) {
            return Set.of();
        }
        try {
            Map<String, Presence> states = presence.getAll(devices.stream().map(TenantDevice::deviceKey).toList());
            Set<String> online = new HashSet<>();
            states.forEach((deviceKey, p) -> {
                if (p.online()) {
                    online.add(deviceKey);
                }
            });
            return online;
        } catch (DataAccessException e) {
            log.warn("Presence store unavailable, platform online counts omitted", e);
            return null;
        }
    }
}
