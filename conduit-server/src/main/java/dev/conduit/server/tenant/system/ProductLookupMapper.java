package dev.conduit.server.tenant.system;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import dev.conduit.server.product.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 系统模式下跨租户按全局 productKey 查询产品。 */
@Mapper
public interface ProductLookupMapper {

    /** 产品不存在时返回 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("select * from product where product_key = #{productKey}")
    Product findByKeyAnyTenant(@Param("productKey") String productKey);
}
