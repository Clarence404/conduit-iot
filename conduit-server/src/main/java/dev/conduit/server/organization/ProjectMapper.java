package dev.conduit.server.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目 Mapper；自动受租户拦截器约束。 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
