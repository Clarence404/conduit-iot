package dev.conduit.server.platform;

import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.tenant.system.PlatformStatsMapper;
import dev.conduit.server.tenant.system.PlatformStatsMapper.TenantCount;
import dev.conduit.server.tenant.system.PlatformStatsMapper.TenantDevice;
import dev.conduit.server.platform.TenantStats.Snapshot;
import dev.conduit.server.platform.TenantStats.TenantCounts;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TenantStats 单元测试：按租户统计用户、管理员、产品、设备与在线数；Redis 不可用时在线数为 null；没有设备时不读 Redis。 */
class TenantStatsTest {

    private final AppUserMapper users = mock(AppUserMapper.class);
    private final PlatformStatsMapper statsMapper = mock(PlatformStatsMapper.class);
    private final PresenceStore presence = mock(PresenceStore.class);
    private final TenantStats stats = new TenantStats(users, statsMapper, presence);

    private static AppUser user(long tenantId, String role) {
        AppUser u = new AppUser();
        u.setTenantId(tenantId);
        u.setRole(role);
        return u;
    }

    private void givenThreeTenants() {
        when(users.selectList(any())).thenReturn(List.of(
                user(1, Roles.TENANT_ADMIN), user(1, Roles.TENANT_VIEWER), user(2, Roles.TENANT_ADMIN)));
        when(statsMapper.productCounts()).thenReturn(List.of(new TenantCount(1, 2), new TenantCount(3, 1)));
        when(statsMapper.devices()).thenReturn(List.of(
                new TenantDevice(1, "a"), new TenantDevice(1, "b"), new TenantDevice(3, "c")));
    }

    @Test
    void countsPerTenant() {
        givenThreeTenants();
        when(presence.getAll(List.of("a", "b", "c"))).thenReturn(Map.of(
                "a", new Presence(true, 1, ""), "c", new Presence(false, 1, "")));

        Snapshot s = stats.snapshot();

        assertThat(s.of(1)).isEqualTo(new TenantCounts(2, 1, 2, 2, 1));
        assertThat(s.of(2)).isEqualTo(new TenantCounts(1, 1, 0, 0, 0));
        assertThat(s.of(3)).isEqualTo(new TenantCounts(0, 0, 1, 1, 0));
        assertThat(s.of(99)).isEqualTo(new TenantCounts(0, 0, 0, 0, 0));
        assertThat(s.userCount()).isEqualTo(3);
        assertThat(s.productCount()).isEqualTo(3);
        assertThat(s.deviceCount()).isEqualTo(3);
        assertThat(s.onlineCount()).isEqualTo(1);
    }

    @Test
    void redisUnavailableLeavesOnlineCountsNull() {
        givenThreeTenants();
        when(presence.getAll(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        Snapshot s = stats.snapshot();

        assertThat(s.onlineCount()).isNull();
        assertThat(s.of(1)).isEqualTo(new TenantCounts(2, 1, 2, 2, null));
        assertThat(s.of(99).onlineCount()).isNull();
        assertThat(s.deviceCount()).isEqualTo(3);
    }

    @Test
    void noDevicesDoesNotTouchRedis() {
        when(users.selectList(any())).thenReturn(List.of());
        when(statsMapper.productCounts()).thenReturn(List.of());
        when(statsMapper.devices()).thenReturn(List.of());

        Snapshot s = stats.snapshot();

        assertThat(s.onlineCount()).isZero();
        verify(presence, never()).getAll(anyList());
    }
}
