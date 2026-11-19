package dev.conduit.transport.allocation;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AllocationProperties} 的单元测试：默认值与启动期校验。 */
class AllocationPropertiesTest {

    private static AllocationProperties of(int pool, Duration ttl, Duration renew, double ratio) {
        return new AllocationProperties(pool, ttl, renew, Duration.ofSeconds(2), Duration.ofSeconds(15),
                Duration.ofSeconds(30), ratio);
    }

    /** 从空配置源绑定，断言真实的 @DefaultValue 默认值（同时证明字符串能解析为 Duration）。 */
    @Test
    void defaultsMatchSpec() {
        AllocationProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("conduit.transport.allocation", AllocationProperties.class);
        assertThat(p.initialPoolSize()).isEqualTo(64);
        assertThat(p.leaseTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.renewInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.tickInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.memberTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.rebalanceStable()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.poolAlertRatio()).isEqualTo(0.5);
    }

    @Test
    void rejectsRenewIntervalNotShorterThanTtl() {
        assertThatThrownBy(() -> of(64, Duration.ofSeconds(15), Duration.ofSeconds(15), 0.5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPoolSizeBelowOne() {
        assertThatThrownBy(() -> of(0, Duration.ofSeconds(15), Duration.ofSeconds(5), 0.5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsRatioOutsideZeroOneInclusive() {
        assertThatThrownBy(() -> of(64, Duration.ofSeconds(15), Duration.ofSeconds(5), 0.0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> of(64, Duration.ofSeconds(15), Duration.ofSeconds(5), 1.1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(of(64, Duration.ofSeconds(15), Duration.ofSeconds(5), 1.0).poolAlertRatio()).isEqualTo(1.0);
    }
}
