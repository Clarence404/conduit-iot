package dev.conduit.server.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 站点 Mapper；自动受租户拦截器约束。 */
@Mapper
public interface SiteMapper extends BaseMapper<Site> {
}
