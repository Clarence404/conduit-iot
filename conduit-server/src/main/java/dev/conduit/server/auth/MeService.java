package dev.conduit.server.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.conduit.server.auth.AuthDtos.ChangePasswordRequest;
import dev.conduit.server.auth.AuthDtos.UpdateMeRequest;
import dev.conduit.server.auth.AuthDtos.UserView;
import dev.conduit.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

/** 个人设置：修改自己的显示名称与密码。按令牌中的用户 id 操作（代入时即平台管理员本人）。 */
@Service
public class MeService {

    private static final Logger log = LoggerFactory.getLogger(MeService.class);

    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final SessionService sessions;
    private final Clock clock;
    private final UserSessionRegistry registry;

    public MeService(AppUserMapper users, PasswordEncoder encoder, SessionService sessions, Clock clock,
                     UserSessionRegistry registry) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * 修改自己的显示名称（去首尾空白，空白即清除）。
     *
     * @return 更新后的当前用户视图
     * @throws ApiException 400：超过 64 个字符
     */
    public UserView updateProfile(AuthPrincipal principal, UpdateMeRequest request) {
        String displayName = DisplayNamePolicy.normalize(request.displayName());
        users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, principal.userId())
                .set(AppUser::getDisplayName, displayName)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        return sessions.userView(principal);
    }

    /**
     * 修改自己的密码并清除「需改密码」标记。
     *
     * @return 新的会话身份（调用方据此签发不带 mcp 的令牌）
     * @throws ApiException 409：代入期间；401：用户已不可用；400：原密码错误、新密码不合规或与原密码相同
     */
    @org.springframework.transaction.annotation.Transactional
    public AuthPrincipal changePassword(AuthPrincipal principal, ChangePasswordRequest request) {
        if (principal.impersonating()) {
            throw ApiException.conflict("代入租户期间不能修改密码，请先退出租户");
        }
        AppUser user = sessions.requireActive(users.selectById(principal.userId()));
        if (!encoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("原密码错误");
        }
        PasswordPolicy.requireValid(request.newPassword());
        if (encoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("新密码不能与原密码相同");
        }
        users.update(null, new LambdaUpdateWrapper<AppUser>()
                .eq(AppUser::getId, user.getId())
                .set(AppUser::getPasswordHash, encoder.encode(request.newPassword()))
                .set(AppUser::getMustChangePassword, false)
                .set(AppUser::getUpdatedAt, OffsetDateTime.now(clock)));
        log.info("User {} changed the password", user.getUsername());
        registry.bumpVersion(user.getId());
        registry.revokeAll(user.getId());
        user.setMustChangePassword(false);
        return sessions.principalOf(user);
    }
}
