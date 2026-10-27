package dev.conduit.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的签发与校验（HS 系列对称签名）。声明：sub 用户 id、tid 租户 id（平台管理员没有）、usr 用户名、role 有效角色、
 * typ 令牌类型、imp 代入的平台管理员用户 id（仅代入时）、mcp 需要先修改密码（仅为 true 时）、
 * sid 服务端会话 id 与 av 授权版本。
 * 由 {@link SessionService} 签发、{@link JwtAuthFilter} 与刷新接口校验。
 */
@Component
public class JwtService {

    /** 令牌类型：ACCESS 用于访问接口，REFRESH 仅用于换发；校验时必须匹配，防止互相冒用。 */
    public enum TokenType { ACCESS, REFRESH }

    private final JwtProperties props;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(JwtProperties props, Clock clock) {
        if (props.jwtSecret() == null || props.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("conduit.auth.jwt-secret must be at least 32 bytes");
        }
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 签发指定类型的令牌，有效期取配置中对应类型的 TTL；为空的可选声明（tid、imp）与为 false 的 mcp 不写入。 */
    public String issue(AuthPrincipal principal, TokenType type) {
        Instant now = clock.instant();
        Duration ttl = type == TokenType.ACCESS ? props.accessTtl() : props.refreshTtl();
        JwtBuilder builder = Jwts.builder()
                .id(java.util.UUID.randomUUID().toString())
                .subject(Long.toString(principal.userId()))
                .claim("usr", principal.username())
                .claim("role", principal.role())
                .claim("typ", type.name());
        if (principal.tenantId() != null) {
            builder.claim("tid", principal.tenantId());
        }
        if (principal.impersonatorId() != null) {
            builder.claim("imp", principal.impersonatorId());
        }
        if (principal.mustChangePassword()) {
            builder.claim("mcp", true);
        }
        if (principal.sessionId() != null) {
            builder.claim("sid", principal.sessionId().toString()).claim("av", principal.authzVersion());
        }
        return builder.issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl))).signWith(key).compact();
    }

    /**
     * 校验签名与有效期并还原身份。
     *
     * @param expected 期望的令牌类型
     * @throws JwtException 签名错误、已过期、格式非法或类型不符（调用方应一并处理 IllegalArgumentException）
     */
    public AuthPrincipal parse(String token, TokenType expected) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expected.name().equals(claims.get("typ", String.class))) {
            throw new JwtException("Unexpected token type");
        }
        return new AuthPrincipal(
                Long.parseLong(claims.getSubject()),
                optionalLong(claims.get("tid")),
                claims.get("usr", String.class),
                claims.get("role", String.class),
                optionalLong(claims.get("imp")),
                Boolean.TRUE.equals(claims.get("mcp", Boolean.class)),
                claims.get("sid", String.class) == null ? null : java.util.UUID.fromString(claims.get("sid", String.class)),
                optionalLong(claims.get("av")) == null ? 0 : optionalLong(claims.get("av")));
    }

    /** 数值声明转 Long；声明不存在时为 null。 */
    private static Long optionalLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
