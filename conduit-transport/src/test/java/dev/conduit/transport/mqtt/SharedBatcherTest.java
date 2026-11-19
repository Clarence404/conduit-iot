package dev.conduit.transport.mqtt;

import dev.conduit.common.bus.PublishResult;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * SharedBatcher 单元测试：多连接合成一批、未满按延迟提交、按提交顺序回调、只在写入成功后回调、
 * 只重提失败条目、会话已停止的条目既不写入也不回调、close 不被失败的总线卡住。
 * 原先在 MqttProtocolAdapterTest 里的会话内攒批断言也迁到这里。
 */
class SharedBatcherTest {

    static final Duration LONG_DELAY = Duration.ofSeconds(30);
    static final Duration FAST_BACKOFF = Duration.ofMillis(1);
    static final BooleanSupplier LIVE = () -> true;

    final FakeMessageBus bus = new FakeMessageBus();
    final List<String> committed = Collections.synchronizedList(new ArrayList<>());
    SharedBatcher batcher;

    @AfterEach
    void tearDown() {
        if (batcher != null) {
            batcher.close();
        }
    }

    @Test
    void mergesSubmissionsFromManyConnectionsIntoOneBatch() {
        batcher = new SharedBatcher(bus, 6, LONG_DELAY, FAST_BACKOFF);
        for (String source : List.of("a", "b", "c")) {
            for (int i = 1; i <= 2; i++) {
                submit(source + i, LIVE);
            }
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 6);
        assertThat(bus.calls()).hasSize(1);
        assertThat(bus.calls().get(0)).extracting(RawMessage::deviceKey)
                .containsExactly("a1", "a2", "b1", "b2", "c1", "c2");
    }

    @Test
    void flushesAfterDelayWhenNotFull() throws Exception {
        batcher = new SharedBatcher(bus, 64, Duration.ofMillis(20), FAST_BACKOFF);
        CountDownLatch done = new CountDownLatch(1);

        batcher.submit(message("only"), LIVE, done::countDown);

        assertThat(done.await(100, TimeUnit.MILLISECONDS)).as("flushed within 100 ms").isTrue();
        assertThat(bus.calls()).hasSize(1);
        assertThat(bus.calls().get(0)).extracting(RawMessage::deviceKey).containsExactly("only");
    }

    @Test
    void callbacksPreserveArrivalOrderPerConnection() {
        // 批大小 2：a1..a3 与 b1、b2 交错提交，跨越多个批
        batcher = new SharedBatcher(bus, 2, Duration.ofMillis(5), FAST_BACKOFF);
        for (String id : List.of("a1", "b1", "a2", "b2", "a3")) {
            submit(id, LIVE);
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 5);
        assertThat(committed).containsExactly("a1", "b1", "a2", "b2", "a3");
        assertThat(committed.stream().filter(id -> id.startsWith("a")).toList()).containsExactly("a1", "a2", "a3");
    }

    @Test
    void callbackRunsOnlyAfterSuccessfulPublish() {
        bus.respondWith((call, batch) -> {
            if (call == 0) {
                throw new IllegalStateException("redis down");
            }
            return FakeMessageBus.okResults(batch);
        });
        batcher = new SharedBatcher(bus, 1, LONG_DELAY, FAST_BACKOFF);
        List<Integer> callsSeenByCallback = Collections.synchronizedList(new ArrayList<>());

        batcher.submit(message("m1"), LIVE, () -> callsSeenByCallback.add(bus.callCount()));

        await().atMost(Duration.ofSeconds(2)).until(() -> callsSeenByCallback.size() == 1);
        assertThat(callsSeenByCallback).containsExactly(2);
        assertThat(bus.callCount()).isEqualTo(2);
    }

    @Test
    void retriesOnlyFailedItems() {
        bus.respondWith((call, batch) -> call == 0
                ? List.of(PublishResult.ok("1-0"), PublishResult.failed(new IllegalStateException("boom")),
                PublishResult.ok("3-0"))
                : FakeMessageBus.okResults(batch));
        batcher = new SharedBatcher(bus, 3, LONG_DELAY, FAST_BACKOFF);
        List<Integer> callsSeenByCallback = Collections.synchronizedList(new ArrayList<>());
        for (String id : List.of("m1", "m2", "m3")) {
            batcher.submit(message(id), LIVE, () -> {
                committed.add(id);
                callsSeenByCallback.add(bus.callCount());
            });
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 3);
        assertThat(bus.calls()).hasSize(2);
        assertThat(bus.calls().get(0)).extracting(RawMessage::deviceKey).containsExactly("m1", "m2", "m3");
        assertThat(bus.calls().get(1)).extracting(RawMessage::deviceKey).containsExactly("m2");
        // 整批全部写入后才按提交顺序回调，PUBACK 没有空洞
        assertThat(committed).containsExactly("m1", "m2", "m3");
        assertThat(callsSeenByCallback).containsOnly(2);
    }

    @Test
    void retriesAfterWholeBatchExceptionWithoutDuplicatingOrDroppingCallbacks() {
        bus.respondWith((call, batch) -> switch (call) {
            case 0 -> throw new IllegalStateException("redis down");
            case 1 -> List.of(PublishResult.ok("1-0"), PublishResult.failed(new IllegalStateException("x")),
                    PublishResult.ok("3-0"));
            default -> FakeMessageBus.okResults(batch);
        });
        batcher = new SharedBatcher(bus, 3, LONG_DELAY, FAST_BACKOFF);
        for (String id : List.of("m1", "m2", "m3")) {
            submit(id, LIVE);
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 3);
        assertThat(bus.calls()).hasSize(3);
        assertThat(bus.calls().get(0)).hasSize(3);
        assertThat(bus.calls().get(1)).hasSize(3);
        assertThat(bus.calls().get(2)).extracting(RawMessage::deviceKey).containsExactly("m2");
        assertThat(committed).containsExactly("m1", "m2", "m3");
    }

    @Test
    void itemsOfStoppedSessionsAreDroppedNotAcked() {
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicBoolean liveB = new AtomicBoolean(true);
        bus.respondWith((call, batch) -> {
            if (failing.get()) {
                throw new IllegalStateException("redis down");
            }
            return FakeMessageBus.okResults(batch);
        });
        batcher = new SharedBatcher(bus, 2, LONG_DELAY, FAST_BACKOFF);
        submit("a1", LIVE);
        submit("b1", liveB::get);
        await().atMost(Duration.ofSeconds(2)).until(() -> bus.callCount() >= 1);

        liveB.set(false);
        // 等到某次重提已不含 B，再让总线恢复：避免恢复前已读过 live 的那一轮把 B 带进成功的写入
        await().atMost(Duration.ofSeconds(2)).until(() -> lastCallDeviceKeys().equals(List.of("a1")));
        failing.set(false);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);
        assertThat(committed).containsExactly("a1");
        List<List<RawMessage>> calls = bus.calls();
        assertThat(calls.get(calls.size() - 1)).extracting(RawMessage::deviceKey).containsExactly("a1");
    }

    @Test
    void anEntryAlreadyWrittenIsNotAckedWhenItsSessionStopsBeforeTheBatchCompletes() {
        AtomicBoolean liveA = new AtomicBoolean(true);
        bus.respondWith((call, batch) -> {
            if (call == 0) {
                return List.of(PublishResult.ok("1-0"), PublishResult.failed(new IllegalStateException("boom")));
            }
            liveA.set(false); // A 的会话在 B 重试期间停止：A 已写入，但不能再在已停止的会话上回 PUBACK
            return FakeMessageBus.okResults(batch);
        });
        batcher = new SharedBatcher(bus, 2, LONG_DELAY, FAST_BACKOFF);
        submit("a1", liveA::get);
        submit("b1", LIVE);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);
        assertThat(committed).containsExactly("b1");
        assertThat(bus.calls()).hasSize(2);
    }

    @Test
    void entriesOfAStoppedSessionAreNotPublishedBeforeTheDelayedFlush() {
        // 延迟提交前会话已停止：既不写总线也不回调，留给 broker 重投；随后的 live 条目照常提交
        batcher = new SharedBatcher(bus, 64, Duration.ofMillis(20), FAST_BACKOFF);
        AtomicBoolean live = new AtomicBoolean(true);
        submit("stopped", live::get);
        live.set(false);
        submit("marker", LIVE);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);
        assertThat(committed).containsExactly("marker");
        assertThat(bus.calls()).allSatisfy(call ->
                assertThat(call).extracting(RawMessage::deviceKey).doesNotContain("stopped"));
    }

    @Test
    void aThrowingCallbackDoesNotStopTheLoopOrSkipOtherCallbacks() {
        batcher = new SharedBatcher(bus, 2, LONG_DELAY, FAST_BACKOFF);
        batcher.submit(message("m1"), LIVE, () -> {
            throw new IllegalStateException("ack failed");
        });
        submit("m2", LIVE);
        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);

        submit("m3", LIVE);
        submit("m4", LIVE);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 3);
        assertThat(committed).containsExactly("m2", "m3", "m4");
    }

    @Test
    void anErrorFromTheBusDropsOnlyThatBatchAndLaterBatchesStillCommit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bus.respondWith((call, batch) -> {
            if (call == 0) {
                throw new StackOverflowError("simulated");
            }
            return FakeMessageBus.okResults(batch);
        });
        batcher = new SharedBatcher(bus, 1, Duration.ofMillis(1), FAST_BACKOFF, registry);

        submit("lost", LIVE);
        await().atMost(Duration.ofSeconds(2)).until(() -> bus.callCount() >= 1);
        submit("after", LIVE);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);
        // 出错的那一批不回调（不 ACK，由 EMQX 在重连时重投），之后的批照常提交并回调
        assertThat(committed).containsExactly("after");
        assertThat(batcher.failures()).isEqualTo(1);
        assertThat(registry.get("conduit.transport.batcher.failures").functionCounter().count()).isEqualTo(1);
    }

    @Test
    void anErrorFromACallbackDoesNotStopOtherCallbacksOrLaterBatches() {
        batcher = new SharedBatcher(bus, 2, LONG_DELAY, FAST_BACKOFF);
        batcher.submit(message("m1"), LIVE, () -> {
            throw new AssertionError("simulated");
        });
        submit("m2", LIVE);
        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 1);

        submit("m3", LIVE);
        submit("m4", LIVE);

        await().atMost(Duration.ofSeconds(2)).until(() -> committed.size() == 3);
        assertThat(committed).containsExactly("m2", "m3", "m4");
        assertThat(batcher.failures()).isEqualTo(1);
    }

    @Test
    void stopDoesNotWaitForABlockedBatch() {
        bus.respondWith((call, batch) -> {
            throw new IllegalStateException("redis down");
        });
        batcher = new SharedBatcher(bus, 1, LONG_DELAY, Duration.ofSeconds(1));
        submit("m1", LIVE);
        await().atMost(Duration.ofSeconds(2)).until(() -> bus.callCount() >= 1);

        long start = System.nanoTime();
        batcher.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(200);
        assertThat(committed).isEmpty();
    }

    @Test
    void submissionsAfterCloseAreDroppedWithoutCallback() {
        batcher = new SharedBatcher(bus, 1, Duration.ofMillis(1), FAST_BACKOFF);
        batcher.close();

        submit("late", LIVE);

        assertThat(committed).isEmpty();
        assertThat(bus.callCount()).isZero();
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new SharedBatcher(bus, 0, LONG_DELAY, FAST_BACKOFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new SharedBatcher(bus, 1, Duration.ofMillis(-1), FAST_BACKOFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedBatcher(bus, 1, LONG_DELAY, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 提交一条以 id 为 deviceKey 的消息，回调时把 id 记入 committed。 */
    private void submit(String id, BooleanSupplier live) {
        batcher.submit(message(id), live, () -> committed.add(id));
    }

    /** 最近一次总线调用里的 deviceKey 列表；尚无调用时为空。 */
    private List<String> lastCallDeviceKeys() {
        List<List<RawMessage>> calls = bus.calls();
        return calls.isEmpty() ? List.of()
                : calls.get(calls.size() - 1).stream().map(RawMessage::deviceKey).toList();
    }

    private static RawMessage message(String deviceKey) {
        return new RawMessage("pk", deviceKey, MessageType.PROPERTY, new byte[]{1}, 1L, Map.of());
    }
}
