package dev.conduit.server.authorization;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.*;
import dev.conduit.server.organization.*;
import dev.conduit.server.tenant.*;
import dev.conduit.server.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 每请求重新读取授权，撤权下一请求即生效；不依赖JWT中旧role，也不因Redis故障放行。 */
@Service
public class AuthorizationService {
    private final AuthorizationMapper mapper;
    private final AppUserMapper users;
    private final TenantMapper tenants;
    private final ProjectMapper projects;
    private final SiteMapper sites;
    private final String mode;
    /** 启动时校验发布模式，非法开关必须使启动失败，不能默认降级为全权。 */
    public AuthorizationService(AuthorizationMapper mapper,AppUserMapper users,TenantMapper tenants,
                                ProjectMapper projects,SiteMapper sites,
                                @Value("${conduit.authorization.mode:shadow}") String mode) {
        if (!Set.of("shadow","enforce","read-only").contains(mode)) throw new IllegalArgumentException("Invalid authorization mode");
        this.mapper=mapper;this.users=users;this.tenants=tenants;this.projects=projects;this.sites=sites;this.mode=mode;
    }
    public String mode() { return mode; }
    public boolean enforcing() { return !mode.equals("shadow"); }
    /** 从数据库当前账号、租户及绑定解析不可变快照；显式接管后不再回落旧角色。 */
    public AuthorizedScope resolve(AuthPrincipal actor) {
        AppUser current=users.selectById(actor.userId());
        if (current==null || !AppUser.ACTIVE.equals(current.getStatus())) throw ApiException.unauthorized("用户不可用");
        long tenant=TenantContext.require();
        Tenant t=tenants.selectById(tenant);
        if(t==null || !Tenant.ACTIVE.equals(t.getStatus())) throw ApiException.unauthorized("租户不可用");
        if(actor.impersonating()) {
            if(!Roles.PLATFORM_ADMIN.equals(current.getRole())) throw ApiException.unauthorized("代入身份无效");
            return new AuthorizedScope(tenant,List.of(grant("TENANT_OWNER","TENANT",null,null)),!mode.equals("read-only"));
        }
        if(!Objects.equals(current.getTenantId(),tenant)) throw ApiException.unauthorized("租户身份无效");
        Map<String,Object> user=mapper.user(tenant,current.getUsername());
        List<AuthorizedScope.Grant> grants=new ArrayList<>();
        if(!Boolean.TRUE.equals(user.get("authz_managed"))) {
            String role=Roles.TENANT_ADMIN.equals(current.getRole())
                    ? Objects.equals(mapper.legacyOwner(tenant),current.getId()) ? "TENANT_OWNER" : "TENANT_ADMIN" : "VIEWER";
            grants.add(grant(role,"TENANT",null,null));
        }
        for(var b:mapper.bindings(current.getId())) {
            if(!"ACTIVE".equals(b.get("role_status"))) continue;
            String role=(String)b.get("role_key");
            Set<String> permissions=PermissionCatalog.ROLES.containsKey(role) ? PermissionCatalog.ROLES.get(role).permissions()
                    : mapper.permissions(number(b,"role_id"));
            grants.add(new AuthorizedScope.Grant(role,(String)b.get("scope_type"),nullable(b,"project_id"),nullable(b,"site_id"),permissions));
        }
        return new AuthorizedScope(tenant,List.copyOf(grants),!mode.equals("read-only"));
    }
    private static AuthorizedScope.Grant grant(String role,String scope,Long project,Long site) {
        return new AuthorizedScope.Grant(role,scope,project,site,PermissionCatalog.ROLES.get(role).permissions());
    }
    /** 返回当前模式、绑定与权限并集，供诊断和管理界面展示；不把 JWT role 当作最终权限。 */
    public Map<String,Object> snapshot(AuthPrincipal actor) {
        var scope=resolve(actor);
        return Map.of("mode",mode,"grants",scope.grants(),"permissions",scope.grants().stream()
                .flatMap(g->g.permissions().stream()).distinct().sorted().toList());
    }
    /** 获取当前租户角色；幂等补齐 Flyway 后新建租户的预置角色元数据，权限以代码目录为准。 */
    public List<Map<String,Object>> roles() {
        long tenant=TenantContext.require();
        PermissionCatalog.ROLES.forEach((key,r)->mapper.ensureRole(tenant,key,r.name(),r.scopes()));
        List<Map<String,Object>> roles=mapper.roles();
        for(var role:roles) {
            var builtin=PermissionCatalog.ROLES.get(role.get("role_key"));
            role.put("permissions",builtin==null ? mapper.permissions(number(role,"id")) : builtin.permissions());
        }
        return roles;
    }
    /** 自定义角色只使用稳定权限码；内置角色权限始终以代码目录为准。 */
    public record RoleInput(String roleKey,String name,String scopeMask,String status,Set<String> permissions) { }
    private String validateScopes(String mask) {
        if(mask==null) throw ApiException.badRequest("角色范围不能为空");
        var scopes=Arrays.asList(mask.split(",",-1));
        if(scopes.isEmpty() || scopes.stream().anyMatch(s->!Set.of("TENANT","PROJECT","SITE").contains(s))
                || new HashSet<>(scopes).size()!=scopes.size()) throw ApiException.badRequest("角色范围无效");
        String canonical=String.join(",",List.of("TENANT","PROJECT","SITE").stream().filter(scopes::contains).toList());
        if(!Set.of("TENANT","PROJECT","SITE","PROJECT,SITE","TENANT,PROJECT,SITE").contains(canonical))
            throw ApiException.badRequest("角色范围组合无效");
        return canonical;
    }
    private String validateRole(AuthPrincipal actor,RoleInput input,boolean creating) {
        var scope=resolve(actor);
        if(!scope.tenantPermission("role.manage") || !scope.writable()) throw forbidden();
        if(input==null || input.roleKey()==null || !input.roleKey().matches("CUSTOM_[A-Z0-9_]{3,57}"))
            throw ApiException.badRequest("自定义角色键必须以 CUSTOM_ 开头");
        if(input.name()==null || input.name().isBlank() || input.name().length()>128)
            throw ApiException.badRequest("角色名称长度无效");
        if(input.permissions()==null || input.permissions().isEmpty() || !PermissionCatalog.ALL.containsAll(input.permissions()))
            throw ApiException.badRequest("角色权限包含未知或空权限码");
        if(input.permissions().contains("tenant.manage")) throw ApiException.badRequest("所有者权限只能由内置角色授予");
        if(!input.permissions().stream().allMatch(scope::tenantPermission)) throw forbidden();
        if(!creating && !Set.of("ACTIVE","DISABLED").contains(input.status())) throw ApiException.badRequest("角色状态无效");
        return validateScopes(input.scopeMask());
    }
    /** 创建租户内角色；角色键与权限目录均经过验证，事务内补齐权限行。 */
    @Transactional
    public void createRole(AuthPrincipal actor,RoleInput input) {
        String scopes=validateRole(actor,input,true);
        long tenant=TenantContext.require();tenants.lockById(tenant);
        if(mapper.role(tenant,input.roleKey())!=null) throw ApiException.conflict("角色键已存在");
        mapper.createRole(tenant,input.roleKey(),input.name().strip(),scopes);
        long id=number(mapper.role(tenant,input.roleKey()),"id");
        for(String permission:input.permissions()) mapper.addPermission(tenant,id,permission);
    }
    /** 修改自定义角色；已有绑定的范围不能改成不兼容，但停用会立即撤销权限。 */
    @Transactional
    public void updateRole(AuthPrincipal actor,String key,RoleInput input) {
        String scopes=validateRole(actor,input,false);
        if(!Objects.equals(key,input.roleKey())) throw ApiException.badRequest("角色键不可修改");
        long tenant=TenantContext.require();tenants.lockById(tenant);
        var existing=mapper.role(tenant,key);
        if(existing==null) throw ApiException.notFound("角色不存在");
        if(Boolean.TRUE.equals(existing.get("built_in"))) throw ApiException.conflict("内置角色不可修改");
        long id=number(existing,"id");
        if(mapper.incompatibleRoleBindings(tenant,id,scopes)>0) throw ApiException.conflict("仍有绑定使用被移除的范围");
        mapper.updateRole(tenant,key,input.name().strip(),scopes,input.status());
        mapper.clearPermissions(tenant,id);
        for(String permission:input.permissions()) mapper.addPermission(tenant,id,permission);
    }
    /** 有任意绑定的角色不能删除，停用与撤销绑定是显式独立操作。 */
    @Transactional
    public void deleteRole(AuthPrincipal actor,String key) {
        var scope=resolve(actor);
        if(!scope.tenantPermission("role.manage") || !scope.writable()) throw forbidden();
        long tenant=TenantContext.require();tenants.lockById(tenant);
        var existing=mapper.role(tenant,key);
        if(existing==null) throw ApiException.notFound("角色不存在");
        if(Boolean.TRUE.equals(existing.get("built_in"))) throw ApiException.conflict("内置角色不可删除");
        long id=number(existing,"id");
        if(mapper.roleBindings(tenant,id)>0) throw ApiException.conflict("角色仍被绑定");
        mapper.deleteRole(tenant,id);
    }
    private Project delegatedProject(AuthPrincipal actor,String code) {
        var scope=resolve(actor);
        if(!scope.any("project.member.manage")) throw forbidden();
        Project project=projects.selectOne(new LambdaQueryWrapper<Project>().eq(Project::getCode,code));
        if(project==null || !"ACTIVE".equals(project.getStatus())
                || scope.grants().stream().noneMatch(g->g.permissions().contains("project.member.manage")
                    && (g.scope().equals("TENANT") || g.scope().equals("PROJECT") && Objects.equals(g.project(),project.getId()))))
            throw ApiException.notFound("项目不存在");
        return project;
    }
    /** 项目管理员只能看自己项目已有的显式成员，不枚举租户全部账号。 */
    public List<Map<String,Object>> projectMembers(AuthPrincipal actor,String code) {
        Project project=delegatedProject(actor,code);
        return mapper.projectMembers(TenantContext.require(),project.getId());
    }
    /** 读取一个目标账号在当前项目下的绑定；其他项目和租户授权不暴露。 */
    public List<Map<String,Object>> projectMemberBindings(AuthPrincipal actor,String code,String username) {
        Project project=delegatedProject(actor,code);
        return mapper.bindings(number(requireUser(username),"id")).stream()
                .filter(b->Objects.equals(nullable(b,"project_id"),project.getId())).toList();
    }
    /** 返回在此项目可委派的项目/站点角色，排除超出操作者权限的角色。 */
    public List<Map<String,Object>> projectRoles(AuthPrincipal actor,String code) {
        Project project=delegatedProject(actor,code);
        var allowed=resolve(actor).permissionsAt(project.getId(),null);
        var delegable=PermissionCatalog.ROLES.get("PROJECT_ADMIN").permissions();
        return roles().stream().filter(r->"ACTIVE".equals(r.get("status")))
                .filter(r->Arrays.stream(((String)r.get("scope_mask")).split(",")).anyMatch(s->s.equals("PROJECT") || s.equals("SITE")))
                .filter(r->{ @SuppressWarnings("unchecked") Set<String> p=(Set<String>)r.get("permissions");
                    return delegable.containsAll(p) && allowed.containsAll(p); })
                .toList();
    }
    /** 只替换指定项目下的绑定，不能碰目标其他项目或租户级授权。 */
    @Transactional
    public void replaceProjectMember(AuthPrincipal actor,String code,String username,List<Binding> bindings) {
        if(bindings==null || bindings.size()>100 || bindings.stream().anyMatch(Objects::isNull))
            throw ApiException.badRequest("授权列表最多100项，条目不能为空");
        long tenant=TenantContext.require();tenants.lockById(tenant);
        Project project=delegatedProject(actor,code);
        var scope=resolve(actor);
        if(!scope.writable()) throw forbidden();
        var allowed=scope.permissionsAt(project.getId(),null);
        var target=requireUser(username);long userId=number(target,"id");
        if(isOwner(userId,target) || Roles.TENANT_ADMIN.equals(target.get("role"))) throw forbidden();
        if(!Boolean.TRUE.equals(target.get("authz_managed")) && bindings.isEmpty())
            throw ApiException.conflict("兼容账号须先添加项目授权，不能用空列表接管");
        Set<String> unique=new HashSet<>();List<Object[]> validated=new ArrayList<>();
        for(Binding b:bindings) {
            if(b.scopeType()==null || !Set.of("PROJECT","SITE").contains(b.scopeType()) ||
                    (b.projectCode()!=null && !code.equals(b.projectCode()))) throw ApiException.badRequest("只能管理当前项目范围");
            var role=mapper.role(tenant,b.roleKey());
            if(role==null || !"ACTIVE".equals(role.get("status")) ||
                    !Arrays.asList(((String)role.get("scope_mask")).split(",")).contains(b.scopeType()))
                throw ApiException.badRequest("角色不支持此范围");
            Set<String> permissions=PermissionCatalog.ROLES.containsKey(b.roleKey())
                    ? PermissionCatalog.ROLES.get(b.roleKey()).permissions() : mapper.permissions(number(role,"id"));
            if(!allowed.containsAll(permissions) || !PermissionCatalog.ROLES.get("PROJECT_ADMIN").permissions().containsAll(permissions))
                throw forbidden();
            Long site=null;
            if("SITE".equals(b.scopeType())) {
                Site s=sites.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getCode,b.siteCode()));
                if(s==null || !Objects.equals(s.getProjectId(),project.getId())) throw ApiException.notFound("站点不存在");
                site=s.getId();
            } else if(b.siteCode()!=null) throw ApiException.badRequest("项目范围不能指定站点");
            if(!unique.add(b.roleKey()+":"+b.scopeType()+":"+site)) throw ApiException.badRequest("重复授权");
            validated.add(new Object[]{number(role,"id"),b.scopeType(),site});
        }
        mapper.clearProjectBindings(tenant,userId,project.getId());
        if(!Boolean.TRUE.equals(target.get("authz_managed"))) mapper.manage(tenant,userId);
        for(var v:validated) mapper.bind(tenant,userId,(long)v[0],(String)v[1],project.getId(),(Long)v[2],actor.userId());
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Project membership changed: actor={} tenant={} project={} user={} bindings={}",
                actor.username(),tenant,code,username,bindings.size());
    }
    /** 授权输入使用租户内编码；持久化前解析为可信数字 ID 并核验归属。 */
    public record Binding(String roleKey,String scopeType,String projectCode,String siteCode) { }
    /** 查询当前租户目标账号的显式绑定，兼容授权不伪造成数据库绑定。 */
    public List<Map<String,Object>> bindings(String username) {
        return mapper.bindings(number(requireUser(username),"id"));
    }
    private Map<String,Object> requireUser(String username) {
        var user=mapper.user(TenantContext.require(),username);
        if(user==null) throw ApiException.notFound("用户不存在");
        return user;
    }
    /** 全量校验后在租户行锁内原子替换；保护所有者且禁止授予超出操作者的权限。 */
    @Transactional
    public void replace(AuthPrincipal actor,String username,List<Binding> bindings) {
        var scope=resolve(actor);
        if(!scope.tenantPermission("role.manage")) throw forbidden();
        if(bindings==null || bindings.size()>100 || bindings.stream().anyMatch(Objects::isNull)) throw ApiException.badRequest("授权列表最多100项，条目不能为空");
        long tenant=TenantContext.require();
        tenants.lockById(tenant);
        var user=requireUser(username);
        long userId=number(user,"id");
        boolean owner=isOwner(userId,user);
        boolean grantingOwner=bindings.stream().anyMatch(b->"TENANT_OWNER".equals(b.roleKey()));
        if((owner || grantingOwner) && !scope.tenantPermission("tenant.manage")) throw forbidden();
        if(owner && !grantingOwner) guardOwnerRemoval(userId,user);
        var definitions=roles();
        // 验证全部条目后再替换，事务失败不能留下半套权限。
        List<Object[]> validated=new ArrayList<>();
        Set<String> unique=new HashSet<>();
        for(Binding b:bindings) {
            var role=definitions.stream().filter(r->Objects.equals(r.get("role_key"),b.roleKey())).findFirst()
                    .orElseThrow(()->ApiException.badRequest("角色不存在"));
            if(!"ACTIVE".equals(role.get("status")) || !Arrays.asList(((String)role.get("scope_mask")).split(",")).contains(b.scopeType()))
                throw ApiException.badRequest("角色不支持此授权范围");
            @SuppressWarnings("unchecked") var permissions=(Set<String>)role.get("permissions");
            if(!permissions.stream().allMatch(scope::tenantPermission)) throw forbidden();
            Long project=null,site=null;
            if("PROJECT".equals(b.scopeType()) || "SITE".equals(b.scopeType())) {
                Project p=projects.selectOne(new LambdaQueryWrapper<Project>().eq(Project::getCode,b.projectCode()));
                if(p==null) throw ApiException.notFound("项目不存在");
                project=p.getId();
            }
            if("SITE".equals(b.scopeType())) {
                Site s=sites.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getCode,b.siteCode()));
                if(s==null || !s.getProjectId().equals(project)) throw ApiException.notFound("站点不存在");
                site=s.getId();
            }
            if("TENANT".equals(b.scopeType()) && (b.projectCode()!=null || b.siteCode()!=null)) throw ApiException.badRequest("租户授权不能指定项目/站点");
            if("PROJECT".equals(b.scopeType()) && b.siteCode()!=null) throw ApiException.badRequest("项目授权不能指定站点");
            if(!unique.add(b.roleKey()+":"+b.scopeType()+":"+project+":"+site)) throw ApiException.badRequest("重复授权");
            validated.add(new Object[]{number(role,"id"),b.scopeType(),project,site});
        }
        mapper.clearBindings(userId);
        mapper.manage(tenant,userId);
        for(var v:validated) mapper.bind(tenant,userId,(long)v[0],(String)v[1],(Long)v[2],(Long)v[3],actor.userId());
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Authorization changed: actor={} tenant={} user={} bindings={}",actor.username(),tenant,username,bindings.size());
    }
    /** 判断显式所有者或仍处于兼容阶段的首个启用管理员。 */
    public boolean isOwner(long id,Map<String,Object> user) {
        return (!Boolean.TRUE.equals(user.get("authz_managed")) && Objects.equals(mapper.legacyOwner(TenantContext.require()),id))
                || mapper.bindings(id).stream().anyMatch(b->"TENANT_OWNER".equals(b.get("role_key")) && "ACTIVE".equals(b.get("role_status")));
    }
    /** 在租户锁内拒绝撤销/停用/删除最后一个启用所有者。 */
    public void guardOwnerRemoval(long id,Map<String,Object> user) {
        Long legacy=mapper.legacyOwner(TenantContext.require());
        if(isOwner(id,user) && mapper.otherOwners(id)==0 && (legacy==null || legacy==id)) throw ApiException.conflict("至少保留一个启用的租户所有者");
    }
    /** 用户管理复用所有者保护；普通管理员也不能通过重置密码接管所有者。 */
    public void guardUserChange(String username,boolean removal,AuthPrincipal actor) {
        var user=requireUser(username); long id=number(user,"id");
        if(isOwner(id,user)) {
            if(!resolve(actor).tenantPermission("tenant.manage")) throw forbidden();
            if(removal) guardOwnerRemoval(id,user);
        }
    }
    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    static Long nullable(Map<String,Object> row,String key) { return row.get(key)==null ? null : number(row,key); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"无权限"); }
}
