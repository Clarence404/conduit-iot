package dev.conduit.server.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 产品的 MyBatis-Plus Mapper，所有查询均受租户拦截器过滤。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 在事务中锁定产品行，串行化同一产品的属性新增与导入（保证 200 个上限）。id 已经过租户校验。 */
    @Select("select id from product where id = #{id} for update")
    Long lockById(@Param("id") long id);
}
