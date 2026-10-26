package dev.conduit.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.authorization.AuthorizationService;
import dev.conduit.server.authorization.PermissionGateFilter;
import dev.conduit.server.tenant.TenantMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置：无状态 JWT 认证与权限矩阵（spec §3.3，后端统一按路径与方法判断，前端只做隐藏）：
 * <ul>
 *   <li>登录、刷新、登出、/internal/**（EMQX 回调，自行校验）、健康检查（/actuator/health/**）公开；其余 /actuator/** 仅平台管理员；</li>
 *   <li>/api/v1/auth/**、/api/v1/me/**：所有登录用户；</li>
 *   <li>/api/v1/platform/**：平台管理员（代入令牌的有效角色是 TENANT_ADMIN，因此被拒）；</li>
 *   <li>/api/v1/users/**：租户管理员（含代入的平台管理员）；</li>
 *   <li>其余 /api/v1/** 的 GET：租户管理员、只读用户；其他方法：租户管理员。</li>
 * </ul>
 * 未认证 401、无权限 403，均为 problem+json。过滤器顺序：JwtAuthFilter → PasswordChangeGateFilter → ImpersonationLogFilter → 权限判断。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** 构建过滤链（规则见类注释）。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectMapper objectMapper,
                                            TenantMapper tenants, AuthorizationService authorization) throws Exception {
        http
                // 无会话 + refresh Cookie 为 SameSite=Strict 且只发往 /api/v1/auth，关闭 CSRF
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Roles.PLATFORM_ADMIN)
                        .requestMatchers("/api/v1/auth/**", "/api/v1/me", "/api/v1/me/**").authenticated()
                        .requestMatchers("/api/v1/platform/**").hasRole(Roles.PLATFORM_ADMIN)
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(authorization.enforcing()
                                        ? authentication.get().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+Roles.TENANT_ADMIN) || a.getAuthority().equals("ROLE_"+Roles.TENANT_VIEWER)) : authentication.get().getAuthorities().stream()
                                        .anyMatch(a -> a.getAuthority().equals("ROLE_"+Roles.TENANT_ADMIN))))
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(Roles.TENANT_ADMIN, Roles.TENANT_VIEWER)
                        .requestMatchers("/api/v1/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(authorization.enforcing()
                                        ? authentication.get().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+Roles.TENANT_ADMIN) || a.getAuthority().equals("ROLE_"+Roles.TENANT_VIEWER))
                                        : authentication.get().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+Roles.TENANT_ADMIN))))
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, objectMapper, HttpStatus.UNAUTHORIZED, "未认证或登录已过期", null))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, objectMapper, HttpStatus.FORBIDDEN, "无权限", null)))
                .addFilterBefore(new JwtAuthFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new PasswordChangeGateFilter(objectMapper), JwtAuthFilter.class)
                .addFilterAfter(new ImpersonationLogFilter(tenants), PasswordChangeGateFilter.class)
                .addFilterAfter(new PermissionGateFilter(authorization,objectMapper), ImpersonationLogFilter.class);
        return http.build();
    }

    /**
     * 直接向响应写入 RFC 7807 problem+json（过滤器层异常不经过 MVC 异常处理器）。
     *
     * @param code 非空时写入 ProblemDetail 的 code 属性（如 PASSWORD_CHANGE_REQUIRED）
     */
    static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper,
                             HttpStatus status, String detail, String code) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (code != null) {
            problem.setProperty("code", code);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
