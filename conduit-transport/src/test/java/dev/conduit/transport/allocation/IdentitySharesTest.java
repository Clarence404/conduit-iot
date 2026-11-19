package dev.conduit.transport.allocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link IdentityShares} 的单元测试：按位置的精确配额（合计恰为 K）、偏好序为稳定排列、告警阈值。 */
class IdentitySharesTest {

    @Test
    void quotaByPositionSumsToPool() {
        assertThat(quotas(16, 3)).containsExactly(6, 5, 5);
        assertThat(quotas(64, 10)).containsExactly(7, 7, 7, 7, 6, 6, 6, 6, 6, 6);
        assertThat(quotas(1, 3)).containsExactly(1, 0, 0);
        assertThat(quotas(4, 1)).containsExactly(4);
        assertThat(quotas(2, 3)).containsExactly(1, 1, 0);
        for (int[] kn : new int[][]{{16, 3}, {64, 10}, {1, 3}, {4, 1}, {2, 3}, {64, 65}, {128, 7}}) {
            assertThat(quotas(kn[0], kn[1]).stream().mapToInt(Integer::intValue).sum()).isEqualTo(kn[0]);
        }
    }

    @Test
    void quotaForAbsentSelfTreatsItAsLast() {
        // 自己不在列表中：视为排在末尾，N 加 1；末位永远拿不到余数
        assertThat(IdentityShares.quota(16, List.of("a", "b"), "z")).isEqualTo(5);
        assertThat(IdentityShares.quota(16, List.of("a", "b"), "0")).isEqualTo(5);
        assertThat(IdentityShares.quota(4, List.of(), "a")).isEqualTo(4);
        assertThat(IdentityShares.quota(2, List.of("a", "b"), "c")).isZero();
    }

    /** 成员 m0..m{n-1}（已按字典序）各自的配额，按位置排列。 */
    private static List<Integer> quotas(int poolSize, int members) {
        List<String> sorted = new ArrayList<>();
        for (int p = 0; p < members; p++) {
            sorted.add(String.format("m%03d", p));
        }
        List<Integer> result = new ArrayList<>();
        for (String self : sorted) {
            result.add(IdentityShares.quota(poolSize, sorted, self));
        }
        return result;
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
