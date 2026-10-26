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
