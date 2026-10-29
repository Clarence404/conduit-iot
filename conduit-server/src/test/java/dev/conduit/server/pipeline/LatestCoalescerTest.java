package dev.conduit.server.pipeline;

import dev.conduit.server.telemetry.TelemetryPoint;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最新值合并的等价性：对同一批数据，「逐条全部执行」与「只执行合并后的子序列」得到的最终状态必须完全相同。
 *
 * <p>判据用一个最新值脚本的参考模型（{@link Model}）：每个测点「ts 不早于当前值才写」，{@code __last} 取最大 ts。
 * 该语义与 latest-update.lua 一致（快路径与逐测点比较等价，已在压测机 Redis 上做过新旧脚本差分测试）。
 * 这里的随机化测试覆盖乱序、同毫秒重复、测点集合多变、批前已有状态等组合——合并逻辑一旦写错，
 * 就是静默的数据错误（旧数据盖掉新值，或新值丢失），只能靠穷举式的等价对照兜住。
 */
class LatestCoalescerTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private static TelemetryPoint p(String dk, long ts, Map<String, Object> fields) {
        return new TelemetryPoint(1L, "pk", dk, ts, fields);
    }

    /** 最新值脚本的参考模型：设备 → 测点 → {ts, 值}，外加 __last。 */
    static final class Model {
        final Map<String, Map<String, long[]>> fields = new HashMap<>();
        final Map<String, Long> last = new HashMap<>();

        void apply(TelemetryPoint p) {
            Map<String, long[]> dev = fields.computeIfAbsent(p.deviceKey(), k -> new HashMap<>());
            p.fields().forEach((name, v) -> {
                long[] cur = dev.get(name);
                if (cur == null || p.ts() >= cur[0]) {
                    dev.put(name, new long[]{p.ts(), ((Number) v).longValue()});
                }
            });
            last.merge(p.deviceKey(), p.ts(), Math::max);
        }

        Model copy() {
            Model m = new Model();
            fields.forEach((d, fs) -> {
                Map<String, long[]> c = new HashMap<>();
                fs.forEach((n, v) -> c.put(n, v.clone()));
                m.fields.put(d, c);
            });
            m.last.putAll(last);
            return m;
        }

        /** 便于比较的规范形式。 */
        Map<String, Object> snapshot() {
            Map<String, Object> s = new HashMap<>();
            fields.forEach((d, fs) -> fs.forEach((n, v) -> s.put(d + "." + n, v[0] + ":" + v[1])));
            last.forEach((d, t) -> s.put(d + ".__last", t));
            return s;
        }
    }

    @Test
    void 常见情况每台设备只剩最新一条() {
        List<TelemetryPoint> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(p("d1", 1000 + i, Map.of("Ua", i, "Ia", i)));
            batch.add(p("d2", 1000 + i, Map.of("Ua", i, "Ia", i)));
        }
        List<TelemetryPoint> out = LatestCoalescer.coalesce(batch);
        assertThat(out).hasSize(2);
        assertThat(out).extracting(TelemetryPoint::ts).containsOnly(1009L);
    }

    @Test
    void 后续消息更旧时不得剔除前面更新的那条() {
        // 乱序：批内靠后的是旧数据，靠前的才是最新值，不能被剔除
        List<TelemetryPoint> batch = List.of(p("d", 2000, Map.of("a", 2)), p("d", 1000, Map.of("a", 1)));
        assertThat(LatestCoalescer.coalesce(batch)).hasSize(2);
    }

    @Test
    void 后续消息测点不全时不得剔除() {
        List<TelemetryPoint> batch = List.of(p("d", 1000, Map.of("a", 1, "b", 1)), p("d", 2000, Map.of("a", 2)));
        // 前一条的 b 没有被覆盖，必须保留，否则 b 的最新值丢失
        assertThat(LatestCoalescer.coalesce(batch)).hasSize(2);
    }

    @Test
    void 同毫秒时保留批内靠后的那条() {
        List<TelemetryPoint> batch = List.of(p("d", 1000, Map.of("a", 1)), p("d", 1000, Map.of("a", 2)));
        List<TelemetryPoint> out = LatestCoalescer.coalesce(batch);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).fields()).containsEntry("a", 2);
    }

    @Test
    void 保留的点维持原有批内相对顺序() {
        List<TelemetryPoint> batch = List.of(
                p("x", 3000, Map.of("a", 1)), p("y", 1000, Map.of("a", 1)),
                p("x", 1000, Map.of("a", 2)), p("y", 2000, Map.of("a", 2)));
        // x: 3000 后跟 1000（乱序，两条都要）；y: 1000 被其后的 2000 覆盖
        assertThat(LatestCoalescer.coalesce(batch)).extracting(q -> q.deviceKey() + "@" + q.ts())
                .containsExactly("x@3000", "x@1000", "y@2000");
    }

    /**
     * 随机等价对照：每次随机生成批前状态与一批数据（多设备、乱序、同毫秒、测点子集多变），
     * 分别全部执行与只执行合并结果，最终状态必须逐字段一致。
     */
    @RepeatedTest(2000)
    void 随机批次下合并前后最终状态完全一致() {
        Random r = new Random(SEQ.incrementAndGet() * 7919L);
        String[] devices = {"d0", "d1", "d2"};
        String[] names = {"a", "b", "c", "d"};

        Model prior = new Model();
        for (int i = 0; i < r.nextInt(6); i++) {
            prior.apply(p(devices[r.nextInt(devices.length)], r.nextInt(20), randomFields(r, names)));
        }

        List<TelemetryPoint> batch = new ArrayList<>();
        int n = 1 + r.nextInt(30);
        for (int i = 0; i < n; i++) {
            // ts 取值范围小，故意制造大量同毫秒与乱序
            batch.add(p(devices[r.nextInt(devices.length)], r.nextInt(20), randomFields(r, names)));
        }

        Model all = prior.copy();
        batch.forEach(all::apply);
        Model coalesced = prior.copy();
        LatestCoalescer.coalesce(batch).forEach(coalesced::apply);

        assertThat(coalesced.snapshot()).isEqualTo(all.snapshot());
    }

    private static Map<String, Object> randomFields(Random r, String[] names) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (String name : names) {
            if (r.nextInt(3) > 0) {
                f.put(name, r.nextInt(1000));
            }
        }
        if (f.isEmpty()) {
            f.put(names[r.nextInt(names.length)], r.nextInt(1000));
        }
        return f;
    }
}
