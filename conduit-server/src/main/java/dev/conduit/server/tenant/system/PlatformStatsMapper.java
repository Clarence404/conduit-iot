package dev.conduit.server.tenant.system;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 系统模式下执行平台级跨租户统计与缓存清理查询。 */
@Mapper
public interface PlatformStatsMapper {

    /** 某租户的计数（一行）。 */
    record TenantCount(long tenantId, long n) {
    }

    /** 某租户的一台设备。 */
    record TenantDevice(long tenantId, String deviceKey) {
    }

    /** 各租户的产品数（没有产品的租户不出现）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select tenant_id, count(*) as n from product group by tenant_id")
    List<TenantCount> productCounts();

    /** 全平台设备及其租户。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select tenant_id, device_key from device")
    List<TenantDevice> devices();

    /** 指定租户全部设备的 deviceKey。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select device_key from device where tenant_id = #{tenantId}")
    List<String> deviceKeysOf(@Param("tenantId") long tenantId);
}
