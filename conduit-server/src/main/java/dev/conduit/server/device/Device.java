package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 设备实体（表 device，带 tenant_id）：归属于某个产品，凭 deviceKey + 设备密钥接入 MQTT。
 * 数据库只存密钥的哈希与盐，明文密钥仅在创建或重置时返回一次。status 为 ENABLED 或 DISABLED（禁用后无法接入）。
 */
@Data
@TableName("device")
public class Device {
    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private Long siteId;
    private Long assetId;
    private String deviceKey;
    private String name;
    private String secretHash;
    private String secretSalt;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
