package dev.conduit.server.auth;

import dev.conduit.server.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 持久化的会话登记表：刷新令牌只以哈希形式存储。 */
@Service
public class UserSessionRegistry {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean allowLegacy;

    public UserSessionRegistry(JdbcTemplate jdbc, Clock clock,
                               @Value("${conduit.auth.allow-legacy-sessions:false}") boolean allowLegacy) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.allowLegacy = allowLegacy;
    }

    public void createWithId(AuthPrincipal principal, String refresh, Instant expiresAt) {
        UUID id = principal.sessionId();
        jdbc.update("insert into user_session(id,user_id,tenant_id,refresh_hash,expires_at) values(?,?,?,?,?)",
                id, principal.userId(), principal.impersonating() ? null : principal.tenantId(),
                hash(refresh), java.sql.Timestamp.from(expiresAt));
    }

    public boolean valid(AuthPrincipal principal) {
        if (principal.sessionId() == null) return allowLegacy;
        Integer count = jdbc.queryForObject("""
                select count(*) from user_session s join app_user u on u.id=s.user_id
                left join tenant t on t.id=u.tenant_id
                where s.id=? and s.user_id=? and s.revoked_at is null and s.expires_at>?
                  and u.status='ACTIVE' and u.authz_version=?
                  and (u.tenant_id is null or t.status='ACTIVE')
                """, Integer.class, principal.sessionId(), principal.userId(),
                java.sql.Timestamp.from(clock.instant()), principal.authzVersion());
        return count != null && count == 1;
    }

    /** 先比较再更新，防止已轮换过的刷新令牌被重复使用。 */
    public void rotate(AuthPrincipal principal, String oldRefresh, String newRefresh, Instant expiresAt) {
        if (!valid(principal) || principal.sessionId() == null) throw ApiException.unauthorized("刷新凭证无效");
        int changed = jdbc.update("""
                update user_session set refresh_hash=?,last_used_at=?,expires_at=?
                where id=? and user_id=? and refresh_hash=? and revoked_at is null and expires_at>?
                """, hash(newRefresh), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(expiresAt), principal.sessionId(), principal.userId(),
                hash(oldRefresh), java.sql.Timestamp.from(clock.instant()));
        if (changed != 1) throw ApiException.unauthorized("刷新凭证无效");
    }

    public void replaceRefresh(AuthPrincipal principal, String refresh, Instant expiresAt) {
        if (!valid(principal)) throw ApiException.unauthorized("会话已失效");
        jdbc.update("update user_session set refresh_hash=?,last_used_at=?,expires_at=? where id=? and user_id=?",
                hash(refresh), java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(expiresAt),
                principal.sessionId(), principal.userId());
    }

    public void revoke(UUID id, long userId) {
        jdbc.update("update user_session set revoked_at=? where id=? and user_id=? and revoked_at is null",
                java.sql.Timestamp.from(clock.instant()), id, userId);
    }

    public void revokeAll(long userId) {
        jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null",
                java.sql.Timestamp.from(clock.instant()), userId);
    }

    public java.util.List<java.util.Map<String, Object>> list(long userId) {
        return jdbc.query("""
                select id,created_at,last_used_at,expires_at,revoked_at,client_summary
                from user_session where user_id=? order by created_at desc limit 100
                """, (rs, row) -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", rs.getObject("id", UUID.class));
            item.put("createdAt", rs.getTimestamp("created_at").toInstant());
            item.put("lastUsedAt", rs.getTimestamp("last_used_at").toInstant());
            item.put("expiresAt", rs.getTimestamp("expires_at").toInstant());
            item.put("revokedAt", rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());
            item.put("clientSummary", rs.getString("client_summary"));
            return item;
        }, userId);
    }

    public void bumpVersion(long userId) {
        jdbc.update("update app_user set authz_version=authz_version+1 where id=?", userId);
    }

    public void bumpRoleUsers(long tenantId, long roleId) {
        jdbc.update("""
                update app_user set authz_version=authz_version+1
                where tenant_id=? and id in
                  (select user_id from role_binding where tenant_id=? and role_id=?)
                """, tenantId, tenantId, roleId);
    }

    public void bumpTenantUsers(long tenantId) {
        jdbc.update("update app_user set authz_version=authz_version+1 where tenant_id=?", tenantId);
    }

    public long version(long userId) {
        Long version = jdbc.queryForObject("select authz_version from app_user where id=?", Long.class, userId);
        return version == null ? 0 : version;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
