package dev.conduit.transport.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 身份池的份额与偏好序计算（纯函数，无状态）。
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
     * 每个成员的目标份额：池大小除以成员数，向上取整。
     *
     * @param poolSize 身份池大小 K
     * @param members  当前成员数；小于等于 0 时按 1 计算
     * @return 目标份额
     */
    public static int share(int poolSize, int members) {
        int m = Math.max(members, 1);
        return (poolSize + m - 1) / m;
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
