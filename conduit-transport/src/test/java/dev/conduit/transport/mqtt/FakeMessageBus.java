package dev.conduit.transport.mqtt;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.PublishResult;
import dev.conduit.common.message.RawMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的总线替身：只实现批量 publish，记录每次调用收到的整批消息，返回值由测试给的应答器决定。
 * 被批提交线程并发调用，所以记录用线程安全容器，应答器用 volatile 引用，测试可在运行中切换。
 */
final class FakeMessageBus implements MessageBus {

    /** 第 call 次（从 0 开始）调用的应答；可以抛异常模拟整批失败。 */
    @FunctionalInterface
    interface Responder {
        List<PublishResult> respond(int call, List<RawMessage> batch);
    }

    private final List<List<RawMessage>> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile Responder responder = (call, batch) -> okResults(batch);

    /** 切换应答器，之后的调用按它返回。 */
    void respondWith(Responder responder) {
        this.responder = responder;
    }

    /** 迄今每次调用收到的整批消息（按调用顺序）。 */
    List<List<RawMessage>> calls() {
        return List.copyOf(calls);
    }

    /** 迄今的调用次数。 */
    int callCount() {
        return calls.size();
    }

    /** 为整批生成全部成功的结果，id 按下标编号。 */
    static List<PublishResult> okResults(List<RawMessage> batch) {
        List<PublishResult> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            results.add(PublishResult.ok((i + 1) + "-0"));
        }
        return results;
    }

    /** 先记录再应答：应答器抛异常时这次调用也计数，便于断言重试次数。 */
    @Override
    public List<PublishResult> publish(List<RawMessage> messages) {
        int call = counter.getAndIncrement();
        calls.add(List.copyOf(messages));
        return responder.respond(call, messages);
    }

    @Override
    public String publish(RawMessage message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void ensureGroup(String group) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Delivery> read(String group, String consumer, int count, Duration block) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void ack(String group, List<String> ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deadLetter(String group, Delivery delivery, String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void recordDead(Delivery delivery, String reason, Map<String, String> details) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Delivery> range(String startId, String endId, int count) {
        throw new UnsupportedOperationException();
    }
}
