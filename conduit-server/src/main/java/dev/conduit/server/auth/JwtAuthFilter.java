package dev.conduit.server.auth;

import dev.conduit.server.tenant.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 Bearer access token，设置认证信息（权限为 ROLE_<有效角色>）并绑定租户；未代入的平台管理员没有租户，不绑定。
 * token 无效时保持未认证，由入口点返回 401。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserSessionRegistry registry;

    public JwtAuthFilter(JwtService jwt, UserSessionRegistry registry) {
        this.jwt = jwt;
        this.registry = registry;
    }

    /**
     * 有合法 Bearer access token 时写入认证信息，令牌带租户时绑定 TenantContext；令牌无效不报错，仅保持未认证。
     * 无论请求如何结束都会在 finally 中清除 TenantContext，避免线程复用时租户串用。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthPrincipal principal = jwt.parse(header.substring(7), JwtService.TokenType.ACCESS);
                if (!registry.valid(principal)) throw new JwtException("Session inactive");
                var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                if (principal.tenantId() != null) {
                    TenantContext.set(principal.tenantId());
                }
            } catch (JwtException | IllegalArgumentException | org.springframework.dao.DataAccessException ignored) {
                // 保持未认证
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
