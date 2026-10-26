package dev.conduit.server.auth;

import jakarta.validation.constraints.NotBlank;

/** 认证与个人接口（{@link AuthController}、{@link MeController}）的请求/响应 DTO 集合。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** 登录请求，用户名与密码均不可为空。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 返回给 UI 的当前用户信息（不含任何敏感字段）。
     *
     * @param displayName        显示名称；未设置时为 null（界面显示用户名）
     * @param role               有效角色；代入时为 TENANT_ADMIN
     * @param tenantCode         当前租户编码；未代入的平台管理员为 null
     * @param tenantName         当前租户名称；未代入的平台管理员为 null
     * @param mustChangePassword 是否需要先修改密码（代入时恒为 false）
     * @param impersonatedBy     代入的平台管理员用户名；未代入为 null
     */
    public record UserView(String username, String displayName, String role, String tenantCode, String tenantName,
                           boolean mustChangePassword, String impersonatedBy, java.util.Set<String> permissions,
                           java.util.Set<String> tenantPermissions, boolean authorizationEnforced) {
    }

    /**
     * 登录、刷新、进入/退出租户、修改密码的响应。
     *
     * @param accessToken access token
     * @param expiresIn   access token 有效期，单位秒
     * @param user        当前用户
     */
    public record TokenResponse(String accessToken, long expiresIn, UserView user) {
    }

    /** 修改自己的显示名称；空白表示清除。 */
    public record UpdateMeRequest(String displayName) {
    }

    /** 修改自己的密码。 */
    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }
}
