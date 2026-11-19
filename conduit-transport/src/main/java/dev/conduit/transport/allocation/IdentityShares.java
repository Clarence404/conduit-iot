package dev.conduit.transport.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 身份池的配额、偏好序与告警判断（纯函数，无状态）。
 *
 * <p>偏好序采用 rendezvous（最高随机权重）思想：每个（实例, 身份序号）对有一个确定性分数，
 * 分数只取决于实例 id 与序号本身，与池大小无关，因此 K 调大时已有序号的相对顺序不变。
 * 分数函数为 splitmix64 终结混合器作用于 {@code (instanceId.hashCode() << 32) ^ i}；
 * String.hashCode 由规范定义，跨 JVM 稳定，无需额外依赖。
 */
public final class IdentityShares {

    private IdentityShares() {
    }

    /**
     * 本实例应持有的身份数（精确配额）：按存活成员的字典序位置 p 分配，
     * {@code ⌊K/N⌋ + (p < K mod N ? 1 : 0)}。各成员用同一份有序列表计算，配额合计恰为 K、两两相差不超过 1，
     * 因此全部身份都有人持有；只给上限（如 ⌈K/N⌉）会让各实例停在 6/6/4 这类不均的状态。
     *
     * @param poolSize      身份池大小 K
     * @param sortedMembers 存活实例 id，按字典序升序
     * @param self          本实例 id；不在列表中时视为排在末尾（p = N，N 加 1），末位永远分不到余数
     * @return 本实例的配额，0 ≤ 配额 ≤ K
     */
    public static int quota(int poolSize, List<String> sortedMembers, String self) {
        int n = sortedMembers.size();
        int p = sortedMembers.indexOf(self);
        if (p < 0) {
            p = n;
            n++;
        }
        return poolSize / n + (p < poolSize % n ? 1 : 0);
    }

    /**
     * 该实例对 0..poolSize-1 全部身份序号的偏好顺序：分数降序，分数相同按序号升序。
     *
     * @param instanceId 实例 id
     * @param poolSize   身份池大小 K
     * @return 序号的一个排列，同参数多次调用结果相同
     */
    public static List<Integer> preference(String instanceId, int poolSize) {
        long seed = (long) instanceId.hashCode() << 32;
        long[] scores = new long[poolSize];
        List<Integer> order = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            scores[i] = mix(seed ^ i);
            order.add(i);
        }
        // 无符号比较分数，降序；并列时序号小者在前
        order.sort(((Comparator<Integer>) (x, y) -> Long.compareUnsigned(scores[y], scores[x]))
                .thenComparingInt(i -> i));
        return order;
    }

    /**
     * 是否应告警扩容：成员数超过 poolSize * ratio。
     *
     * @param members  当前成员数
     * @param poolSize 身份池大小 K
     * @param ratio    告警比例
     * @return 超过阈值（严格大于）时为 true
     */
    public static boolean shouldAlert(int members, int poolSize, double ratio) {
        return members > poolSize * ratio;
    }

    /** splitmix64 终结混合：让相邻输入的输出充分扩散。 */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
