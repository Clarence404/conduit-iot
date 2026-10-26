package dev.conduit.server.authorization;

/** 请求范围在 finally 清理；后台接入/重放只按可信设备身份和租户处理，不复用用户范围。 */
public final class AuthorizationContext {
    /** 本次请求实际使用的权限码及范围，供 SQL 拦截器共享。 */
    public record State(AuthorizedScope scope, String permission) { }
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    public static State current() { return CURRENT.get(); }
    public static void set(AuthorizedScope scope,String permission) { CURRENT.set(new State(scope,permission)); }
    public static void clear() { CURRENT.remove(); }
    /** 仅供界面提示；请求仍必须经过 SQL 范围校验。 */
    public static java.util.Set<String> permissions(Long project, Long site) {
        State state=current();
        if(state==null) return null;
        return state.scope().grants().stream().filter(g->g.scope().equals("TENANT")
                || (project!=null && g.scope().equals("PROJECT") && project.equals(g.project()))
                || (site!=null && g.scope().equals("SITE") && site.equals(g.site())))
                .flatMap(g->g.permissions().stream()).filter(p->state.scope().writable() || p.endsWith(".read"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private AuthorizationContext() { }
}
