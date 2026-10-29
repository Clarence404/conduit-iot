package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 写最新值之前，剔除同批内「被后续消息完全覆盖」的数据点，使每批每台设备通常只需一次脚本调用。
 *
 * <p>为什么需要：最新值脚本是每条消息调用一次，2026-10-29 实测它占 Redis 时间的 58.9%（12,000 条/秒时
 * Redis 单线程峰值 91%）。Redis 一满，transport 的 XADD 跟着排队、回 PUBACK 变慢，EMQX 信箱堆积直至连接被杀。
 * 而一批几千条消息里同一台设备常有十几条，最新值只关心每个测点「最新」的那次写入，前面的调用结果全被覆盖。
 *
 * <p><b>为什么等价</b>：脚本对每个测点的语义是「ts 不早于当前值才写」，按顺序执行下来，
 * 每个测点的最终值恰好是对它所有写入中按（ts, 批内顺序）取字典序最大的那一次——ts 最大者胜，
 * ts 相同则批内靠后者胜（因为用的是 >=）。所以若同一设备有一条位于 A 之后的 B，满足
 * {@code ts_B >= ts_A} 且 A 的测点集合是 B 的子集，那么 A 在它的每个测点上都被 B 字典序压过，
 * 永远不会是胜者，去掉 A 不改变任何测点的最终值；{@code __last} 取最大 ts，B 也不小于 A。
 * 脚本的快路径（ts >= __last 时整批覆盖）与逐测点比较等价（见 latest-update.lua），不影响这一推理。
 *
 * <p>判定是保守的：每台设备只拿<b>一个</b>支配候选——已保留的后续点里 ts 最大的那个——来比较；
 * 它覆盖不了 A 的全部测点就保留 A，即便 A 的测点其实能被多条后续消息拼凑覆盖。宁可多调一次，
 * 也不冒漏写的险。这样每个点 O(1) 判定：若与全部后续点逐一比较，ts 递减或测点集合多变的设备
 * 会退化成 O(n²)（单设备 2 万条即 2 亿次比较）。常见设备每次上报的测点集合相同，
 * ts 最大的那条即能覆盖同批所有更早的点。保留下来的点维持原有的批内相对顺序。
 */
final class LatestCoalescer {

    private LatestCoalescer() {
    }

    /**
     * 返回需要真正执行最新值更新的数据点，保持批内原有相对顺序。
     *
     * @param points 按批内顺序排列的数据点
     * @return 剔除被同批后续消息完全覆盖者之后的子序列；输入为空时返回空列表
     */
    static List<TelemetryPoint> coalesce(List<TelemetryPoint> points) {
        if (points.size() < 2) {
            return points;
        }
        // 从后往前扫：遇到一个点时，dominator 里登记的是同设备「位于它之后、已保留、ts 最大」的那个点
        Map<String, TelemetryPoint> dominator = new HashMap<>();
        List<TelemetryPoint> keptReversed = new ArrayList<>(points.size());
        for (int i = points.size() - 1; i >= 0; i--) {
            TelemetryPoint a = points.get(i);
            TelemetryPoint b = dominator.get(a.deviceKey());
            if (b != null && b.ts() >= a.ts() && b.fields().keySet().containsAll(a.fields().keySet())) {
                continue;  // a 在它的每个测点上都被位于其后的 b 压过，去掉不改变最终状态
            }
            keptReversed.add(a);
            // ts 相同时保留原候选：它位于批内更靠后，在 ts 相同的写入中本来就是胜者
            if (b == null || a.ts() > b.ts()) {
                dominator.put(a.deviceKey(), a);
            }
        }
        if (keptReversed.size() == points.size()) {
            return points;
        }
        Collections.reverse(keptReversed);
        return keptReversed;
    }

}
