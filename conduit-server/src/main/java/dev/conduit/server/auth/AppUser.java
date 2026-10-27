package dev.conduit.server.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 平台用户（表 app_user）。租户用户属于一个租户；平台管理员不属于任何租户（tenantId 为 null）。密码只存 BCrypt 哈希。
 * 该表不参与租户行过滤：登录时尚无租户上下文，需要全局按用户名查找；按租户的查询必须显式带 tenant_id 条件。
 */
@Data
@TableName("app_user")
public class AppUser {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属租户；平台管理员为 null。 */
    private Long tenantId;
    private String username;
    /** 显示名称；为空时界面显示用户名。 */
    private String displayName;
    private String passwordHash;
    /** 角色，取值见 {@link Roles}。 */
    private String role;
    /** ACTIVE 或 DISABLED。 */
    private String status;
    /** 为 true 时登录后只能修改密码（新建的用户、被重置密码的用户）。 */
    private Boolean mustChangePassword;
    private Long authzVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
