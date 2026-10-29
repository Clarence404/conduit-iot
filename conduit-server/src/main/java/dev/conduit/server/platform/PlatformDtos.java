package dev.conduit.server.platform;

import dev.conduit.server.metrics.ThroughputSampler.ThroughputView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/** 平台接口（/api/v1/platform/**）的请求与响应对象集合。在线数在 Redis 不可用时为 null。 */
public final class PlatformDtos {

    private PlatformDtos() {
    }

    /** 设备最多的租户中的一项。 */
    public record TenantStat(String code, String name, int deviceCount, Integer onlineCount) {
    }

    /**
     * 平台概况。
     *
     * @param userCount  租户用户总数（不含平台管理员）
     * @param topTenants 设备数最多的 5 个租户（设备数降序，相同时按编码）
     * @param throughput 本进程的入库吞吐，重启归零；速率在采样不足时为 null
     */
    public record PlatformOverview(int tenantCount, int activeTenantCount, int disabledTenantCount, int userCount,
                                   int productCount, int deviceCount, Integer onlineCount,
                                   List<TenantStat> topTenants, ThroughputView throughput) {
    }

    /**
     * 租户列表项与详情。
     *
     * @param status     ACTIVE 或 DISABLED
     * @param adminCount 租户管理员数（含已禁用）
     */
    public record TenantView(String code, String name, String status, int adminCount, int userCount,
                             int productCount, int deviceCount, Integer onlineCount, OffsetDateTime createdAt) {
    }

    /** 新租户的首个管理员（首次登录须修改密码）。 */
    public record AdminAccount(@NotBlank String username, String displayName, @NotBlank String password) {
    }

    /** 新建租户：编码、名称与首个管理员，同一事务创建。 */
    public record CreateTenantRequest(@NotBlank String code, @NotBlank String name,
                                      @Valid @NotNull AdminAccount admin) {
    }

    /** 修改租户名称（编码不可修改）。 */
    public record UpdateTenantRequest(@NotBlank String name) {
    }
}
