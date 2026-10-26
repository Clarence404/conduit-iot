package dev.conduit.server.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.web.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** 路径到稳定权限的唯一映射；未知业务入口失败关闭。资源查询仍在SQL内加范围，直接URL越权404。 */
public class PermissionGateFilter extends OncePerRequestFilter {
    private final AuthorizationService authorization;
    private final ObjectMapper json;
    public PermissionGateFilter(AuthorizationService authorization,ObjectMapper json) { this.authorization=authorization;this.json=json; }
    /** 每请求重新解析权限；资源越权404，集合/动作403；请求结束无论成功失败均清理上下文。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        String path=request.getRequestURI();
        if(authentication==null || !(authentication.getPrincipal() instanceof AuthPrincipal actor) || actor.tenantId()==null
                || !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/") || path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")
                || path.startsWith("/api/v1/platform/")) { chain.doFilter(request,response);return; }
        boolean read="GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        try {
            var scope=authorization.resolve(actor);
            String permission=permission(path,read);
            boolean allowed=scope.any(permission) && (read || scope.writable());
            boolean tenantOnly=path.startsWith("/api/v1/roles") || path.startsWith("/api/v1/users")
                    || (path.equals("/api/v1/projects") && "POST".equals(request.getMethod()))
                    || (!read && (path.startsWith("/api/v1/products") || path.startsWith("/api/v1/customers")));
            if(tenantOnly) allowed=allowed && scope.tenantPermission(permission);
            if(authorization.enforcing()) {
                if(!allowed) {
                    // 直接资源访问统一404；集合/动作入口403。
                    boolean resource=path.matches("/api/v1/(devices|products|projects|sites|assets|customers)/[^/]+.*");
                    throw resource ? ApiException.notFound("资源不存在") : AuthorizationService.forbidden();
                }
                AuthorizationContext.set(scope,permission);
            } else if(!allowed) org.slf4j.LoggerFactory.getLogger(getClass()).info("Authorization shadow denied: user={} method={} path={} permission={}",actor.username(),request.getMethod(),path,permission);
        } catch(ApiException e) {
            if(!authorization.enforcing()) { chain.doFilter(request,response);return; }
            var problem=org.springframework.http.ProblemDetail.forStatusAndDetail(e.status(),e.getMessage());
            response.setStatus(e.status().value());response.setContentType("application/problem+json");json.writeValue(response.getOutputStream(),problem);return;
        }
        try { chain.doFilter(request,response); } finally { AuthorizationContext.clear(); }
    }
    /** 业务资源入口到权限码的集中登记；未知入口返回不可授予的哨兵码。 */
    static String permission(String path,boolean read) {
        if(path.startsWith("/api/v1/authorization")) return "tenant.read";
        if(path.startsWith("/api/v1/roles")) return "role.manage";
        if(path.startsWith("/api/v1/users")) return path.endsWith("/bindings") ? "role.manage" : read ? "member.read" : "member.manage";
        if(path.startsWith("/api/v1/devices/")) {
            if(path.endsWith("/latest")) return "telemetry.latest.read";
            if(path.endsWith("/history")) return "telemetry.history.read";
            if(path.endsWith("/reset-secret")) return "device.secret.rotate";
        }
        for(String name:List.of("device","product","customer","project","site","asset"))
            if(path.equals("/api/v1/"+name+"s") || path.startsWith("/api/v1/"+name+"s/")) return name+(read ? ".read" : ".manage");
        if(path.equals("/api/v1/overview")) return "device.read";
        if(path.equals("/api/v1/codecs")) return "product.read";
        return "__unregistered__";
    }
}
