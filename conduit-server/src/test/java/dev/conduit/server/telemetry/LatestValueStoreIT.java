package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatestValueStoreIT extends IntegrationTestBase {

    @Autowired
    LatestValueStore store;

    @Test
    void newerValuesOverwriteAndOlderValuesAreIgnoredPerField() {
        String dk = unique("dk");

        store.update(dk, 2000, Map.of("Ua", 220.0, "Ia", 5.0));
        store.update(dk, 1000, Map.of("Ua", 100.0, "Pf", 0.9));
        store.update(dk, 3000, Map.of("Ia", 6.0));

        assertThat(store.get(dk)).containsExactly(
                Map.entry("Ia", new LatestValue(6.0, 3000)),
                Map.entry("Pf", new LatestValue(0.9, 1000)),
                Map.entry("Ua", new LatestValue(220.0, 2000)));
    }

    @Test
    void sameTimestampOverwritesIdempotently() {
        String dk = unique("dk");

        store.update(dk, 1000, Map.of("Ua", 220.0));
        store.update(dk, 1000, Map.of("Ua", 220.0));

        assertThat(store.get(dk)).containsExactly(Map.entry("Ua", new LatestValue(220.0, 1000)));
    }

    @Test
    void keepsStringAndBooleanValues() {
        String dk = unique("dk");

        store.update(dk, 1000, Map.of("mode", "auto", "alarm", true));

        assertThat(store.get(dk)).containsEntry("mode", new LatestValue("auto", 1000))
                .containsEntry("alarm", new LatestValue(true, 1000));
    }

    @Test
    void lastReportTimeIsMaxTimestampSeen() {
        String a = unique("dk");
        String b = unique("dk");
        String none = unique("dk");

        store.update(a, 2000, Map.of("Ua", 1.0));
        store.update(a, 1000, Map.of("Ua", 1.0));
        store.update(b, 5000, Map.of("Ua", 1.0));

        assertThat(store.lastReportTimes(List.of(a, b, none)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(a, 2000L, b, 5000L));
    }

    @Test
    void deleteRemovesEverything() {
        String dk = unique("dk");
        store.update(dk, 1000, Map.of("Ua", 1.0));

        store.delete(dk);

        assertThat(store.get(dk)).isEmpty();
        assertThat(store.lastReportTimes(List.of(dk))).isEmpty();
    }

    @Test
    void reservedFieldNameIsRejectedAndHashUnchanged() {
        String dk = unique("dk");
        store.update(dk, 1000, Map.of("Ua", 1.0));

        assertThatThrownBy(() -> store.update(dk, 2000, Map.of("__last", 5.0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.get(dk)).containsExactly(Map.entry("Ua", new LatestValue(1.0, 1000)));
        assertThat(store.lastReportTimes(List.of(dk))).containsEntry(dk, 1000L);
    }

    @Test
    void nullValueIsRejected() {
        String dk = unique("dk");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("Ua", null);

        assertThatThrownBy(() -> store.update(dk, 1000, params))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ua");
    }

    @Test
    void ordersCorrectlyWithRealisticMillisecondTimestamps() {
        String dk = unique("dk");
        long t = 1_760_400_000_000L;

        store.update(dk, t + 1, Map.of("Ua", 2.0));
        store.update(dk, t, Map.of("Ua", 1.0));

        assertThat(store.get(dk)).containsExactly(Map.entry("Ua", new LatestValue(2.0, t + 1)));
        assertThat(store.lastReportTimes(List.of(dk))).containsEntry(dk, t + 1);
    }

    /**
     * 快路径不得改变语义：脚本在 ts >= __last 时跳过逐测点比较直接整批覆盖，
     * 依据是「每个测点存的 ts 必然 ≤ __last」。本用例把这条依据正面压一遍——
     * 先写入一个较新的测点抬高 __last，再用更早的 ts 补传，旧数据必须被拒绝。
     */
    @Test
    void 乱序补传不得覆盖更新的测点值() {
        String dk = unique("dk");
        store.update(dk, 1000, Map.of("a", 1.0, "b", 2.0));
        store.update(dk, 2000, Map.of("a", 9.0));

        // ts=1500 落在 b 的 1000 之后、a 的 2000 之前：b 该被覆盖，a 不该
        store.update(dk, 1500, Map.of("a", 5.0, "b", 7.0));

        assertThat(store.get(dk)).containsEntry("a", new LatestValue(9.0, 2000))
                .containsEntry("b", new LatestValue(7.0, 1500));
        assertThat(store.lastReportTimes(List.of(dk))).containsEntry(dk, 2000L);
    }

    /**
     * 快路径用 unpack 一次性 HSET，但 unpack 受 Lua 栈限制，测点极多时会报
     * "too many results to unpack"，脚本为此在超过阈值时改为逐个写。
     * 这里用 120 个测点越过该阈值，确认写入与时间戳语义都正常。
     */
    @Test
    void 测点数量超过unpack阈值时仍正确写入() {
        String dk = unique("dk");
        Map<String, Object> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            many.put("p" + i, (double) i);
        }

        store.update(dk, 3000, many);

        assertThat(store.get(dk)).hasSize(120)
                .containsEntry("p0", new LatestValue(0.0, 3000))
                .containsEntry("p119", new LatestValue(119.0, 3000));
        assertThat(store.lastReportTimes(List.of(dk))).containsEntry(dk, 3000L);
    }

    /** 同一组输入：一份逐条 update、一份 updateBatch，读回的全部最新值与 __last 必须完全一致。 */
    @Test
    void batchUpdateProducesExactlyTheSameStateAsOneByOneUpdates() {
        String a1 = unique("dk"), b1 = unique("dk");
        String a2 = unique("dk"), b2 = unique("dk");
        // 含乱序（旧 ts 在新 ts 之后）、同设备多条、同 ts、字符串/布尔值
        List<Object[]> inputs = List.of(
                new Object[]{0, 2000L, Map.<String, Object>of("Ua", 220.0, "Ia", 5.0, "mode", "auto")},
                new Object[]{0, 1000L, Map.<String, Object>of("Ua", 100.0, "Pf", 0.9)},
                new Object[]{1, 5000L, Map.<String, Object>of("alarm", true)},
                new Object[]{0, 3000L, Map.<String, Object>of("Ia", 6.0)},
                new Object[]{1, 5000L, Map.<String, Object>of("alarm", false, "Ua", 1.0)},
                new Object[]{1, 4000L, Map.<String, Object>of("Ua", 9.0)});
        String[] oneByOne = {a1, b1};
        String[] batched = {a2, b2};
        List<LatestValueStore.LatestUpdate> batch = new java.util.ArrayList<>();
        for (Object[] in : inputs) {
            int d = (Integer) in[0];
            @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) in[2];
            store.update(oneByOne[d], (Long) in[1], params);
            batch.add(new LatestValueStore.LatestUpdate(batched[d], (Long) in[1], params));
        }

        store.updateBatch(batch);

        for (int d = 0; d < 2; d++) {
            assertThat(store.get(batched[d])).isEqualTo(store.get(oneByOne[d]));
            assertThat(redis.opsForHash().entries(LatestValueStore.key(batched[d])))
                    .isEqualTo(redis.opsForHash().entries(LatestValueStore.key(oneByOne[d])));
        }
        assertThat(store.get(a2)).containsEntry("Ua", new LatestValue(220.0, 2000))
                .containsEntry("Ia", new LatestValue(6.0, 3000)).containsEntry("Pf", new LatestValue(0.9, 1000));
        assertThat(store.lastReportTimes(List.of(a2, b2))).containsEntry(a2, 3000L).containsEntry(b2, 5000L);
    }

    @Test
    void batchDoesNotLetOlderTimestampsOverwriteNewerValues() {
        String dk = unique("dk");
        store.update(dk, 5000, Map.of("Ua", 220.0));

        store.updateBatch(List.of(
                new LatestValueStore.LatestUpdate(dk, 1000, Map.of("Ua", 1.0, "Ia", 2.0)),
                new LatestValueStore.LatestUpdate(dk, 3000, Map.of("Ia", 3.0))));

        assertThat(store.get(dk)).containsEntry("Ua", new LatestValue(220.0, 5000))
                .containsEntry("Ia", new LatestValue(3.0, 3000));
        assertThat(store.lastReportTimes(List.of(dk))).containsEntry(dk, 5000L);
    }

    @Test
    void oneByOneAlsoKeepsNewerValuesAgainstOlderTimestamps() {
        String dk = unique("dk");
        store.update(dk, 5000, Map.of("Ua", 220.0));
        store.update(dk, 1000, Map.of("Ua", 1.0));

        assertThat(store.get(dk)).containsExactly(Map.entry("Ua", new LatestValue(220.0, 5000)));
    }

    @Test
    void emptyBatchSendsNothing() {
        store.updateBatch(List.of());
    }

    @Test
    void batchWithReservedNameIsRejectedBeforeAnyWrite() {
        String good = unique("dk");
        assertThatThrownBy(() -> store.updateBatch(List.of(
                new LatestValueStore.LatestUpdate(good, 1000, Map.of("Ua", 1.0)),
                new LatestValueStore.LatestUpdate(unique("dk"), 1000, Map.of("__last", 1.0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(good)).isEmpty();
    }
}
