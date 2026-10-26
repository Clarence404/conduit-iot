package dev.conduit.server.tenant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@link Tenant} 的 MyBatis-Plus Mapper（PostgreSQL 的 tenant 表，不做租户行过滤）。 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 在事务中锁定租户行（select … for update），串行化同一租户内的用户管理变更，
     * 保证「至少保留一个启用的租户管理员」不会被并发操作绕过。租户不存在时返回 null。
     */
    @Select("select id from tenant where id = #{id} for update")
    Long lockById(@Param("id") long id);
}
