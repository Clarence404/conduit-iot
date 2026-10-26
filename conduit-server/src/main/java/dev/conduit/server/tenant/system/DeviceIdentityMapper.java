package dev.conduit.server.tenant.system;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import dev.conduit.server.device.DeviceIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 系统模式下跨租户按全局 deviceKey 查询可信设备身份。 */
@Mapper
public interface DeviceIdentityMapper {

    /** 联查产品和租户状态；设备不存在返回 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select d.device_key, d.tenant_id, d.status, d.secret_hash, d.secret_salt, p.product_key,
                   t.status as tenant_status
            from device d
            join product p on p.id = d.product_id
            join tenant t on t.id = d.tenant_id
            where d.device_key = #{deviceKey}
            """)
    DeviceIdentity findIdentity(@Param("deviceKey") String deviceKey);
}
