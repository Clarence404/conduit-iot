package dev.conduit.transport.allocation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link IdentityShares} 的单元测试：份额向上取整、偏好序为稳定排列、告警阈值。 */
class IdentitySharesTest {

    @Test
    void shareRoundsUp() {
        assertThat(IdentityShares.share(64, 1)).isEqualTo(64);
        assertThat(IdentityShares.share(64, 2)).isEqualTo(32);
        assertThat(IdentityShares.share(64, 3)).isEqualTo(22);
        assertThat(IdentityShares.share(64, 64)).isEqualTo(1);
        assertThat(IdentityShares.share(64, 65)).isEqualTo(1);
        assertThat(IdentityShares.share(64, 0)).isEqualTo(64);
    }

    @Test
    void preferenceIsAPermutationAndStable() {
        List<Integer> a = IdentityShares.preference("a", 64);
        assertThat(a).hasSize(64);
        assertThat(new HashSet<>(a)).hasSize(64).allMatch(i -> i >= 0 && i < 64);
        assertThat(IdentityShares.preference("a", 64)).isEqualTo(a);
        List<Integer> b = IdentityShares.preference("b", 64);
        assertThat(a.subList(0, 8)).isNotEqualTo(b.subList(0, 8));
    }

    @Test
    void preferenceIsStableWhenPoolGrows() {
        List<Integer> small = IdentityShares.preference("a", 64);
        List<Integer> grown = IdentityShares.preference("a", 128).stream().filter(i -> i < 64).toList();
        assertThat(grown).isEqualTo(small);
    }

    @Test
    void alertAboveRatio() {
        assertThat(IdentityShares.shouldAlert(33, 64, 0.5)).isTrue();
        assertThat(IdentityShares.shouldAlert(32, 64, 0.5)).isFalse();
    }
}
