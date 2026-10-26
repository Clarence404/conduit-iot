package dev.conduit.server.authorization;

import java.util.*;

/** 稳定权限词汇与不可修改的内置角色。 */
public final class PermissionCatalog {
    public static final Set<String> ALL = Set.of("tenant.read", "tenant.manage", "member.read", "member.manage",
            "role.manage", "customer.read", "customer.manage", "project.read", "project.manage", "project.member.manage",
            "site.read", "site.manage", "asset.read", "asset.manage", "product.read", "product.manage",
            "device.read", "device.manage", "device.secret.rotate", "device.command", "telemetry.latest.read",
            "telemetry.history.read", "telemetry.replay", "audit.read", "audit.export");
    /** 不可变预置角色；scopes 是允许绑定的范围，不代表自动获得这些范围。 */
    public record Builtin(String name, String scopes, Set<String> permissions) { }
    public static final Map<String, Builtin> ROLES;
    static {
        Set<String> read = Set.of("tenant.read", "customer.read", "project.read", "site.read", "asset.read",
                "product.read", "device.read", "telemetry.latest.read", "telemetry.history.read");
        Set<String> operator = new HashSet<>(read);
        operator.addAll(Set.of("device.manage", "device.secret.rotate", "device.command"));
        Set<String> project = new HashSet<>(operator);
        project.addAll(Set.of("project.manage", "site.manage", "asset.manage", "project.member.manage"));
        Set<String> admin = new HashSet<>(ALL);
        admin.removeAll(Set.of("tenant.manage", "telemetry.replay"));
        ROLES = Map.of("TENANT_OWNER", new Builtin("租户所有者", "TENANT", ALL),
                "TENANT_ADMIN", new Builtin("租户管理员", "TENANT", Set.copyOf(admin)),
                "PROJECT_ADMIN", new Builtin("项目管理员", "PROJECT", Set.copyOf(project)),
                "OPERATOR", new Builtin("运维人员", "PROJECT,SITE", Set.copyOf(operator)),
                "VIEWER", new Builtin("只读成员", "TENANT,PROJECT,SITE", read));
    }
    private PermissionCatalog() { }
}
