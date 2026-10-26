package dev.conduit.server.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 设备接口的请求与响应对象集合。
 */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    /** 创建设备请求：站点为空时归入系统默认站点；assetCode 非空时必须属于所选站点。 */
    public record CreateDeviceRequest(@NotBlank String productKey, @NotBlank @Size(max = 128) String name,
                                      String siteCode, String assetCode) {
    }

    /** 更新设备请求：新名称与状态（ENABLED / DISABLED）。 */
    public record UpdateDeviceRequest(@NotBlank @Size(max = 128) String name,
                                      @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    /**
     * 设备响应视图。
     * @param online 是否在线（来自 Redis 在线状态）
     * @param lastReportAt 最近一次上报时间（毫秒），从未上报为 null
     */
    public record DeviceView(String deviceKey, String name, String productKey, String status,
                             boolean online, Long lastReportAt, OffsetDateTime createdAt, java.util.Set<String> permissions) {
        public DeviceView(String deviceKey,String name,String productKey,String status,boolean online,Long lastReportAt,OffsetDateTime createdAt) {
            this(deviceKey,name,productKey,status,online,lastReportAt,createdAt,null);
        }
    }

    /**
     * 创建设备的响应；deviceSecret 为明文密钥，仅此一次返回，之后无法再查看。
     */
    public record DeviceCreated(DeviceView device, String deviceSecret) {
    }

    /**
     * 重置密钥的响应；deviceSecret 为新的明文密钥，仅此一次返回。
     */
    public record SecretView(String deviceKey, String deviceSecret) {
    }

    /** 调整设备组织归属。 */
    public record AssignDeviceRequest(@NotBlank String siteCode, String assetCode) {
    }

    /** 设备组织归属视图。 */
    public record DeviceAssignmentView(String deviceKey, String siteCode, String assetCode) {
    }
}
