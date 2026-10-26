package dev.conduit.server.authorization;

import java.util.*;
import java.util.stream.Collectors;

/** 请求内不可变权限快照；只用数据库读到的数字ID生成 SQL，禁止使用请求字符串拼接。 */
public record AuthorizedScope(long tenant, List<Grant> grants, boolean writable) {
    /** 一条有效绑定；project/site 只包含受组合外键约束的数据库标识。 */
    public record Grant(String role, String scope, Long project, Long site, Set<String> permissions) { }
    public boolean tenantPermission(String permission) {
        return grants.stream().anyMatch(g -> g.scope.equals("TENANT") && g.permissions.contains(permission));
    }
    public boolean any(String permission) { return grants.stream().anyMatch(g -> g.permissions.contains(permission)); }
    /** 指定项目/站点内实际可用的权限；成员委派不得越过操作者在该范围的权限。 */
    public Set<String> permissionsAt(long project,Long site) {
        return grants.stream().filter(g->g.scope.equals("TENANT")
                || (g.scope.equals("PROJECT") && Objects.equals(g.project,project))
                || (site!=null && g.scope.equals("SITE") && Objects.equals(g.site,site)))
                .flatMap(g->g.permissions.stream()).filter(p->writable || p.endsWith(".read"))
                .collect(Collectors.toUnmodifiableSet());
    }
    /** 为组织或设备表生成权限范围；共享模板无需组织条件，所有子查询显式带租户。 */
    public String predicate(String table, String qualifier, String permission) {
        boolean write=!permission.endsWith(".read");
        String activeSites="select azactive.id from site azactive join project azproject on azproject.id=azactive.project_id and azproject.tenant_id=azactive.tenant_id where azactive.tenant_id="+tenant+" and azactive.status='ACTIVE' and azproject.status='ACTIVE'";
        // 仅设备/资产操作限制活动组织；产品删除等依赖检查必须包含停用组织中的设备。
        boolean operatesResource=write && (permission.startsWith("device.") || permission.equals("asset.manage"));
        String active=operatesResource ? switch(table) {
            case "device", "asset" -> qualifier+".site_id in ("+activeSites+")";
            case "site" -> qualifier+".id in ("+activeSites+")";
            case "project" -> qualifier+".status='ACTIVE'";
            default -> null;
        } : null;
        if (tenantPermission(permission)) return active;
        Set<Long> projects = new HashSet<>(), sites = new HashSet<>();
        for (Grant g : grants) if (g.permissions.contains(permission)) {
            if (g.scope.equals("PROJECT")) projects.add(g.project);
            if (g.scope.equals("SITE")) sites.add(g.site);
        }
        String projectIds = ids(projects), siteIds = ids(sites);
        String allowedSites = "select azs.id from site azs where azs.tenant_id=" + tenant
                + " and (azs.project_id in (" + projectIds + ") or azs.id in (" + siteIds + "))";
        String scoped=switch(table) {
            case "device", "asset" -> qualifier + ".site_id in (" + allowedSites + ")";
            case "site" -> "(" + qualifier + ".project_id in (" + projectIds + ") or " + qualifier + ".id in (" + siteIds + "))";
            case "project" -> "(" + qualifier + ".id in (" + projectIds + ") or " + qualifier
                    + ".id in (select azp.project_id from site azp where azp.tenant_id=" + tenant + " and azp.id in (" + siteIds + ")))";
            case "customer" -> qualifier + ".id in (select azc.customer_id from project azc where azc.tenant_id=" + tenant
                    + " and (azc.id in (" + projectIds + ") or azc.id in (select azp.project_id from site azp where azp.tenant_id="
                    + tenant + " and azp.id in (" + siteIds + "))))";
            default -> null;
        };
        return scoped==null ? active : active==null ? scoped : "("+scoped+") and ("+active+")";
    }
    private static String ids(Set<Long> ids) {
        return ids.isEmpty() ? "-1" : ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
