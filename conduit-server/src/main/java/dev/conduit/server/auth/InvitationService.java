package dev.conduit.server.auth;

import dev.conduit.server.authorization.AuthorizationService;
import dev.conduit.server.notification.InvitationNotificationChannel;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One-time invitations. Only a token hash is persisted; delivery is a replaceable plugin. */
@Service
public class InvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthorizationService authorization;
    private final List<InvitationNotificationChannel> channels;
    private final Clock clock;
    private final String channelKey;
    private final String publicUrl;

    public InvitationService(JdbcTemplate jdbc, PasswordEncoder encoder, AuthorizationService authorization,
                             List<InvitationNotificationChannel> channels, Clock clock,
                             @Value("${conduit.notification.invitation.channel:email}") String channelKey,
                             @Value("${conduit.notification.public-url:}") String publicUrl) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.authorization = authorization;
        this.channels = channels;
        this.clock = clock;
        this.channelKey = channelKey;
        this.publicUrl = publicUrl;
    }

    public record Create(String email, String username, String role) { }

    @Transactional
    public UUID create(AuthPrincipal actor, Create request) {
        requireManage(actor);
        if (request == null || request.email() == null || !request.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                || request.email().length() > 254) throw ApiException.badRequest("邮箱地址无效");
        String username = UsernamePolicy.requireValid(request.username());
        if (!Roles.TENANT_ADMIN.equals(request.role()) && !Roles.TENANT_VIEWER.equals(request.role()))
            throw ApiException.badRequest("邀请角色无效");
        Integer existing = jdbc.queryForObject("select count(*) from app_user where username=?", Integer.class, username);
        if (existing != null && existing > 0) throw ApiException.conflict("用户名已存在");
        Integer pending = jdbc.queryForObject("""
                select count(*) from user_invitation where username=? and accepted_at is null
                  and revoked_at is null and expires_at>?
                """, Integer.class, username, java.sql.Timestamp.from(clock.instant()));
        if (pending != null && pending > 0) throw ApiException.conflict("该用户名已有未完成的邀请");
        InvitationNotificationChannel channel = channels.stream().filter(c -> c.key().equals(channelKey))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "邀请通知通道未配置"));
        authorization.roles(); // provision built-in role metadata for newly created tenants
        if (publicUrl.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "邀请公开地址未配置");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into user_invitation(id,tenant_id,email,username,role,invited_by,token_hash,expires_at)
                values(?,?,?,?,?,?,?,?)
                """, id, TenantContext.require(), request.email().strip(), username, request.role(), actor.userId(),
                hash(token), java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(48))));
        try {
            channel.send(request.email().strip(), publicUrl.replaceAll("/+$", "") + "/invitations/accept?token=" + token);
        } catch (MailException | IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "邀请邮件发送失败");
        }
        return id;
    }

    public List<Map<String, Object>> list(AuthPrincipal actor) {
        requireManage(actor);
        return jdbc.queryForList("""
                select id,email,username,role,created_at,expires_at,accepted_at,revoked_at
                from user_invitation where tenant_id=? order by created_at desc limit 100
                """, TenantContext.require());
    }

    public void revoke(AuthPrincipal actor, UUID id) {
        requireManage(actor);
        jdbc.update("""
                update user_invitation set revoked_at=? where id=? and tenant_id=?
                  and accepted_at is null and revoked_at is null
                """, java.sql.Timestamp.from(clock.instant()), id, TenantContext.require());
    }

    /** Public preview contains no token or internal user id. */
    public Map<String, Object> preview(String token) {
        var rows = jdbc.queryForList("""
                select i.username,i.email,t.name as tenant_name from user_invitation i
                join tenant t on t.id=i.tenant_id
                where i.token_hash=? and i.accepted_at is null and i.revoked_at is null
                  and i.expires_at>? and t.status='ACTIVE'
                """, hash(token), java.sql.Timestamp.from(clock.instant()));
        if (rows.isEmpty()) throw ApiException.notFound("邀请已失效");
        return rows.getFirst();
    }

    @Transactional
    public void accept(String token, String password) {
        PasswordPolicy.requireValid(password);
        var rows = jdbc.queryForList("""
                update user_invitation set accepted_at=? where token_hash=? and accepted_at is null
                  and revoked_at is null and expires_at>?
                  and exists(select 1 from tenant where id=tenant_id and status='ACTIVE')
                returning tenant_id,username,role
                """, java.sql.Timestamp.from(clock.instant()), hash(token), java.sql.Timestamp.from(clock.instant()));
        if (rows.isEmpty()) throw ApiException.notFound("邀请已失效");
        var row = rows.getFirst();
        long tenant = ((Number) row.get("tenant_id")).longValue();
        String username = (String) row.get("username");
        String role = (String) row.get("role");
        try {
            Long userId = jdbc.queryForObject("""
                    insert into app_user(tenant_id,username,password_hash,role,status,must_change_password,authz_managed)
                    values(?,?,?,?,'ACTIVE',false,true) returning id
                    """, Long.class, tenant, username, encoder.encode(password), role);
            String roleKey = Roles.TENANT_ADMIN.equals(role) ? "TENANT_ADMIN" : "VIEWER";
            Long roleId = jdbc.queryForObject("select id from role_definition where tenant_id=? and role_key=?",
                    Long.class, tenant, roleKey);
            if (roleId == null) throw new IllegalStateException("Built-in role metadata missing");
            jdbc.update("""
                    insert into role_binding(tenant_id,user_id,role_id,scope_type)
                    values(?,?,?,'TENANT')
                    """, tenant, userId, roleId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("用户名已存在");
        }
    }

    private void requireManage(AuthPrincipal actor) {
        var scope = authorization.resolve(actor);
        if (!scope.tenantPermission("member.manage") || !scope.writable())
            throw AuthorizationService.forbidden();
    }

    private static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiException.notFound("邀请已失效");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
