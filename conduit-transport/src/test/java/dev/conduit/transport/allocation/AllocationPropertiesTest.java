package dev.conduit.transport.allocation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AllocationProperties} 的单元测试：默认值与启动期校验。 */
class AllocationPropertiesTest {

    private static AllocationProperties of(int pool, Duration ttl, Duration renew, double ratio) {
        return new AllocationProperties(pool, ttl, renew, Duration.ofSeconds(2), Duration.ofSeconds(15),
                Duration.ofSeconds(30), ratio);
    }

    @Test
    void defaultsMatchSpec() {
        AllocationProperties p = new AllocationProperties(64, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), 0.5);
        assertThat(p.initialPoolSize()).isEqualTo(64);
        assertThat(p.leaseTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.renewInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.tickInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.memberTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.rebalanceStable()).isEqualTo(Duration.ofSeconds(30));
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
