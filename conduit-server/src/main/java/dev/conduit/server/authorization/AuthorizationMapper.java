package dev.conduit.server.authorization;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** 所有用户查询显式含 tenant_id；业务资源继续受 MyBatis-Plus 租户与范围拦截器双重约束。 */
@Mapper
public interface AuthorizationMapper {
    @Select("select * from role_definition order by id")
    List<Map<String,Object>> roles();
    @Select("select * from role_definition where tenant_id=#{tenant} and role_key=#{key}")
    Map<String,Object> role(long tenant,String key);
    @Insert("insert into role_definition(tenant_id,role_key,name,scope_mask,built_in,status) values(#{tenant},#{key},#{name},#{scopes},false,'ACTIVE')")
    void createRole(long tenant,String key,String name,String scopes);
    @Update("update role_definition set name=#{name},scope_mask=#{scopes},status=#{status} where tenant_id=#{tenant} and role_key=#{key} and not built_in")
    int updateRole(long tenant,String key,String name,String scopes,String status);
    @Delete("delete from role_permission where tenant_id=#{tenant} and role_id=#{role}")
    void clearPermissions(long tenant,long role);
    @Select("select count(*) from role_binding where tenant_id=#{tenant} and role_id=#{role}")
    int roleBindings(long tenant,long role);
    @Select("select count(*) from role_binding where tenant_id=#{tenant} and role_id=#{role} and scope_type != all(string_to_array(#{scopes},','))")
    int incompatibleRoleBindings(long tenant,long role,String scopes);
    @Delete("delete from role_definition where tenant_id=#{tenant} and id=#{role} and not built_in")
    int deleteRole(long tenant,long role);
    @Select("select permission_code from role_permission where role_id=#{role}")
    Set<String> permissions(long role);
    @Select("select b.*, r.role_key, r.status role_status, p.code project_code, s.code site_code from role_binding b join role_definition r on r.id=b.role_id and r.tenant_id=b.tenant_id left join project p on p.id=b.project_id and p.tenant_id=b.tenant_id left join site s on s.id=b.site_id and s.tenant_id=b.tenant_id where b.user_id=#{user}")
    List<Map<String,Object>> bindings(long user);
    @Insert("insert into role_definition(tenant_id,role_key,name,scope_mask,built_in) values(#{tenant},#{key},#{name},#{scopes},true) on conflict(tenant_id,role_key) do nothing")
    void ensureRole(long tenant,String key,String name,String scopes);
    @Insert("insert into role_permission(tenant_id,role_id,permission_code) values(#{tenant},#{role},#{permission}) on conflict do nothing")
    void addPermission(long tenant,long role,String permission);
    @Select("select * from app_user where tenant_id=#{tenant} and username=#{username}")
    Map<String,Object> user(long tenant,String username);
    @Select("select id from app_user where tenant_id=#{tenant} and role='TENANT_ADMIN' and status='ACTIVE' and not authz_managed order by created_at,id limit 1")
    Long legacyOwner(long tenant);
    @Update("update app_user set authz_managed=true where tenant_id=#{tenant} and id=#{user}")
    void manage(long tenant,long user);
    @Delete("delete from role_binding where user_id=#{user}")
    void clearBindings(long user);
    @Insert("insert into role_binding(tenant_id,user_id,role_id,scope_type,project_id,site_id,created_by) values(#{tenant},#{user},#{role},#{scope},#{project},#{site},#{actor})")
    void bind(long tenant,long user,long role,String scope,Long project,Long site,long actor);
    @Select("select count(*) from role_binding b join role_definition r on r.id=b.role_id and r.tenant_id=b.tenant_id join app_user u on u.id=b.user_id and u.tenant_id=b.tenant_id where r.role_key='TENANT_OWNER' and b.scope_type='TENANT' and r.status='ACTIVE' and u.status='ACTIVE' and b.user_id != #{excluded}")
    int otherOwners(long excluded);
    @Select("select count(*) from role_binding where project_id=#{project}")
    int projectBindings(long project);
    @Select("select count(*) from role_binding where site_id=#{site}")
    int siteBindings(long site);
    @Select("select distinct u.username,u.display_name,u.status from app_user u join role_binding b on b.user_id=u.id and b.tenant_id=u.tenant_id where b.tenant_id=#{tenant} and b.project_id=#{project} order by u.username")
    List<Map<String,Object>> projectMembers(long tenant,long project);
    @Delete("delete from role_binding where tenant_id=#{tenant} and user_id=#{user} and project_id=#{project}")
    void clearProjectBindings(long tenant,long user,long project);
}
