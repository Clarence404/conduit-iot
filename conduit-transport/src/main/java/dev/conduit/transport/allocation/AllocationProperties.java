package dev.conduit.transport.allocation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * transport 身份池多活的分配配置（前缀 conduit.transport.allocation）。
 *
 * @param initialPoolSize 身份池初始大小 K，至少为 1
 * @param leaseTtl        身份租约过期时间：实例崩溃后最长经过这么久身份才能被他人接管
 * @param renewInterval   租约续期间隔，必须为正且短于 leaseTtl
 * @param tickInterval    分配循环的执行间隔，必须为正
 * @param memberTtl       成员心跳的过期时间，必须为正
 * @param rebalanceStable 成员集合稳定多久后才触发再平衡，必须为正
 * @param poolAlertRatio  成员数超过 K 乘以该比例时告警扩容，取值范围 (0, 1]
 */
@ConfigurationProperties(prefix = "conduit.transport.allocation")
public record AllocationProperties(
        @DefaultValue("64") int initialPoolSize,
        @DefaultValue("15s") Duration leaseTtl,
        @DefaultValue("5s") Duration renewInterval,
        @DefaultValue("2s") Duration tickInterval,
        @DefaultValue("15s") Duration memberTtl,
        @DefaultValue("10s") Duration rebalanceStable,
        @DefaultValue("0.5") double poolAlertRatio) {

    /** 校验各项取值，不合法时抛 IllegalStateException 使启动失败。 */
    public AllocationProperties {
        if (initialPoolSize < 1) {
            throw new IllegalStateException("conduit.transport.allocation.initial-pool-size ("
                    + initialPoolSize + ") must be >= 1");
        }
        requirePositive("lease-ttl", leaseTtl);
        requirePositive("renew-interval", renewInterval);
        requirePositive("tick-interval", tickInterval);
        requirePositive("member-ttl", memberTtl);
        requirePositive("rebalance-stable", rebalanceStable);
        if (renewInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalStateException("conduit.transport.allocation.renew-interval (" + renewInterval
                    + ") must be shorter than lease-ttl (" + leaseTtl + ")");
        }
        if (!(poolAlertRatio > 0 && poolAlertRatio <= 1)) {
            throw new IllegalStateException("conduit.transport.allocation.pool-alert-ratio ("
                    + poolAlertRatio + ") must be in (0, 1]");
        }
    }

    private static void requirePositive(String name, Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalStateException("conduit.transport.allocation." + name + " (" + d
                    + ") must be positive");
        }
    }
}
