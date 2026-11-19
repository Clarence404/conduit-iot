package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5Disconnect;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqttProtocolAdapter 单元测试：经真实的 {@link SharedBatcher} 与总线替身，验证写入总线成功后才 ACK、
 * 会话停止后已提交的条目不写入也不 ACK、业务回执先于 ACK、同一实例的多条连接合成一批、自有客户端上下线直接 ACK、
 * 意外断线与会话被接管时请求重启、SUBACK 含错误码视为订阅失败、连不上 broker 时 start 失败且不留状态。
 * 合批本身（攒满、延迟、只重提失败条目等）的断言在 {@link SharedBatcherTest}。
 */
class MqttProtocolAdapterTest {

    static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC);

    FakeMessageBus bus;
    SharedBatcher batcher;
    Mqtt5Publish publish;
    MqttProtocolAdapter adapter;
    final List<SharedBatcher> extraBatchers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new FakeMessageBus();
        batcher = new SharedBatcher(bus, 1, Duration.ofMillis(5), Duration.ofMillis(1));
        publish = mock(Mqtt5Publish.class);
        when(publish.getTopic()).thenReturn(MqttTopic.of("conduit/pk1/dk1/up/property"));
        when(publish.getPayloadAsBytes()).thenReturn(new byte[]{1});
        adapter = new MqttProtocolAdapter(props(1), batcher, FIXED);
    }

    @AfterEach
    void tearDown() {
        adapter.stop();
        batcher.close();
        extraBatchers.forEach(SharedBatcher::close);
    }

    @Test
    void acksOnlyAfterSuccessfulPublishAndRetries() {
        bus.respondWith((call, batch) -> {
            if (call < 2) {
                throw new IllegalStateException("redis down");
            }
            return FakeMessageBus.okResults(batch);
        });
        List<Integer> callsSeenAtAck = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> callsSeenAtAck.add(bus.callCount())).when(publish).acknowledge();

        adapter.onPublish(adapter.openSession(), publish);

        verify(publish, timeout(2000)).acknowledge();
        assertThat(callsSeenAtAck).containsExactly(3);
    }

    @Test
    void doesNotAckWhilePublishKeepsFailingThenDropsTheEntryOnceStopped() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(true);
        bus.respondWith((call, batch) -> {
            if (failing.get()) {
                throw new IllegalStateException("redis down");
            }
            return FakeMessageBus.okResults(batch);
        });
        adapter.onPublish(adapter.openSession(), publish);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> bus.callCount() >= 1);

        adapter.stop();
        failing.set(false);
        // 合批器单线程按序处理：标记条目回调时，前一批已因会话停止被丢弃
        awaitMarker(batcher);

        verify(publish, never()).acknowledge();
        List<List<RawMessage>> calls = bus.calls();
        assertThat(calls.get(calls.size() - 1)).extracting(RawMessage::deviceKey).containsExactly("marker");
    }

    @Test
    void ackFailureDoesNotRepublish() {
        doThrow(new IllegalStateException("connection lost")).when(publish).acknowledge();

        adapter.onPublish(adapter.openSession(), publish);

        verify(publish, timeout(2000)).acknowledge();
        assertThat(bus.callCount()).isEqualTo(1);
    }

    @Test
    void sendsApplicationAckForReliablePropertyAfterBusPublish() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(batcher);
        when(publish.getPayloadAsBytes())
                .thenReturn("{\"id\":\"dk1-42\",\"ts\":1760400000000,\"params\":{\"Ua\":220.1}}"
                        .getBytes(StandardCharsets.UTF_8));

        reliable.onPublish(reliable.openSession(), publish);

        verify(publish, timeout(2000)).acknowledge();
        assertThat(reliable.ackTopic).isEqualTo("conduit/pk1/dk1/down/ack");
        assertThat(new String(reliable.ackPayload, StandardCharsets.UTF_8))
                .contains("\"id\":\"dk1-42\"")
                .contains("\"ts\":1");
        assertThat(bus.callCount()).isEqualTo(1);
        reliable.stop();
    }

    @Test
    void skipsApplicationAckWithoutAnIdOrForMalformedJson() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(batcher);
        when(publish.getPayloadAsBytes()).thenReturn("{\"ts\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8));
        reliable.onPublish(reliable.openSession(), publish);
        verify(publish, timeout(2000)).acknowledge();
        assertThat(reliable.ackTopic).isNull();

        // 第一条已 ACK 后再改桩，避免与批提交线程上的 acknowledge 并发操作同一个 mock
        when(publish.getPayloadAsBytes()).thenReturn(new byte[]{1, 2, 3});
        reliable.onPublish(reliable.openSession(), publish);

        verify(publish, timeout(2000).times(2)).acknowledge();
        assertThat(reliable.ackTopic).isNull();
        assertThat(bus.callCount()).isEqualTo(2);
        reliable.stop();
    }

    @Test
    void applicationAckSendFailureStillAcknowledgesTheUplink() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(batcher);
        reliable.ackResult = CompletableFuture.failedFuture(new IllegalStateException("connection lost"));
        when(publish.getPayloadAsBytes())
                .thenReturn("{\"id\":\"dk1-1\",\"ts\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8));

        reliable.onPublish(reliable.openSession(), publish);

        verify(publish, timeout(2000)).acknowledge();
        assertThat(reliable.ackTopic).isEqualTo("conduit/pk1/dk1/down/ack");
        assertThat(bus.callCount()).isEqualTo(1);
        reliable.stop();
    }

    @Test
    void presenceOfThePlatformsOwnClientIsAcknowledgedWithoutPublishing() {
        when(publish.getTopic())
                .thenReturn(MqttTopic.of("$SYS/brokers/emqx@127.0.0.1/clients/conduit-transport/connected"));

        adapter.onPublish(adapter.openSession(), publish);

        verify(publish).acknowledge();
        assertThat(bus.callCount()).isZero();
    }

    @Test
    void connectionsOfOneInstanceShareOneBatch() {
        SharedBatcher shared = newBatcher(2, Duration.ofSeconds(30));
        MqttProtocolAdapter first = new MqttProtocolAdapter(props(2), shared, FIXED, 0);
        MqttProtocolAdapter second = new MqttProtocolAdapter(props(2), shared, FIXED, 1);
        Mqtt5Publish p1 = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        Mqtt5Publish p2 = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");

        first.onPublish(first.openSession(), p1);
        second.onPublish(second.openSession(), p2);

        verify(p1, timeout(2000)).acknowledge();
        verify(p2, timeout(2000)).acknowledge();
        assertThat(bus.calls()).hasSize(1);
        assertThat(bus.calls().get(0)).extracting(RawMessage::deviceKey).containsExactly("dev-1", "dev-2");
        first.stop();
        second.stop();
    }

    @Test
    void anUnexpectedDisconnectRequestsARestart() {
        MqttProtocolAdapter.Session session = adapter.openSession();

        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.SERVER,
                new IllegalStateException("broker restarted")));

        assertThat(adapter.needsRestart()).isTrue();
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void aDisconnectByTheAdapterItselfDoesNotRequestARestart() {
        MqttProtocolAdapter.Session session = adapter.openSession();

        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.USER, null));

        assertThat(adapter.needsRestart()).isFalse();
    }

    @Test
    void stopClearsTheRestartRequest() {
        MqttProtocolAdapter.Session session = adapter.openSession();
        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.CLIENT, null));

        adapter.stop();

        assertThat(adapter.needsRestart()).isFalse();
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void recognisesASessionTakenOverByAnotherConnection() {
        Mqtt5Disconnect takenOver = mock(Mqtt5Disconnect.class);
        when(takenOver.getReasonCode()).thenReturn(Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER);
        Mqtt5Disconnect shutdown = mock(Mqtt5Disconnect.class);
        when(shutdown.getReasonCode()).thenReturn(Mqtt5DisconnectReasonCode.SERVER_SHUTTING_DOWN);

        assertThat(MqttProtocolAdapter.isSessionTakenOver(new Mqtt5DisconnectException(takenOver, "taken over")))
                .isTrue();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(new Mqtt5DisconnectException(shutdown, "shutdown")))
                .isFalse();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(new IllegalStateException("other"))).isFalse();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(null)).isFalse();
    }

    @Test
    void aSubAckWithAnyErrorCodeCountsAsAFailedSubscription() {
        Mqtt5SubAck partial = mock(Mqtt5SubAck.class);
        when(partial.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1,
                Mqtt5SubAckReasonCode.NOT_AUTHORIZED, Mqtt5SubAckReasonCode.GRANTED_QOS_1));
        Mqtt5SubAck granted = mock(Mqtt5SubAck.class);
        when(granted.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1,
                Mqtt5SubAckReasonCode.GRANTED_QOS_1, Mqtt5SubAckReasonCode.GRANTED_QOS_1));

        assertThatThrownBy(() -> MqttProtocolAdapter.requireGranted(partial))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_AUTHORIZED");
        MqttProtocolAdapter.requireGranted(granted);
    }

    /** 用模拟客户端替换真实连接的适配器：connect 返回由测试控制的 future，subscribe 立即成功。 */
    private static class FakeConnectAdapter extends MqttProtocolAdapter {
        final List<Mqtt5AsyncClient> clients = new ArrayList<>();
        final List<CompletableFuture<Mqtt5ConnAck>> connects = new ArrayList<>();
        /** 非 null 时 subscribe 返回它，由测试控制 SUBACK 何时到达。 */
        volatile CompletableFuture<Mqtt5SubAck> subAck;

        FakeConnectAdapter(SharedBatcher batcher) {
            super(props(1), batcher, Clock.systemUTC());
        }

        @Override
        synchronized Mqtt5AsyncClient buildClient(Mqtt5ClientBuilder builder) {
            Mqtt5AsyncClient client = mock(Mqtt5AsyncClient.class);
            when(client.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
            when(client.getState()).thenReturn(com.hivemq.client.mqtt.MqttClientState.CONNECTED);
            clients.add(client);
            return client;
        }

        @Override
        synchronized CompletableFuture<Mqtt5ConnAck> connect(Mqtt5AsyncClient client) {
            CompletableFuture<Mqtt5ConnAck> f = new CompletableFuture<>();
            connects.add(f);
            return f;
        }

        @Override
        CompletableFuture<Mqtt5SubAck> subscribe(Mqtt5AsyncClient client) {
            if (subAck != null) {
                return subAck;
            }
            Mqtt5SubAck ack = mock(Mqtt5SubAck.class);
            when(ack.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1));
            return CompletableFuture.completedFuture(ack);
        }

        synchronized int connectCount() {
            return connects.size();
        }
    }

    /** 捕获业务回执而不连接 broker。 */
    private static final class AckCapturingAdapter extends MqttProtocolAdapter {
        // 在批提交线程上写、测试线程上读
        volatile String ackTopic;
        volatile byte[] ackPayload;
        volatile CompletableFuture<?> ackResult = CompletableFuture.completedFuture(null);

        AckCapturingAdapter(SharedBatcher batcher) {
            super(props(1), batcher, FIXED);
        }

        @Override
        CompletableFuture<?> publishReliableAck(Session session, String topic, byte[] payload) {
            ackTopic = topic;
            ackPayload = payload;
            return ackResult;
        }
    }

    @Test
    void stopDuringAnInProgressStartMakesTheStartFailAndLeavesNothingConnected() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                fake.start();
                result.complete(null);
            } catch (Throwable e) {
                result.complete(e);
            }
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 1);

        fake.stop();

        assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class);
        assertThat(fake.isConnected()).isFalse();
        assertThat(fake.needsRestart()).isFalse();
    }

    @Test
    void aConnackArrivingAfterStopIsDisconnectedImmediately() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                fake.start();
                result.complete(null);
            } catch (Throwable e) {
                result.complete(e);
            }
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 1);
        fake.stop();
        result.get(2, TimeUnit.SECONDS);
        Mqtt5AsyncClient client = fake.clients.get(0);
        org.mockito.Mockito.clearInvocations(client);
        when(client.disconnect()).thenReturn(CompletableFuture.completedFuture(null));

        fake.connects.get(0).complete(mock(Mqtt5ConnAck.class));

        verify(client, timeout(1000).atLeastOnce()).disconnect();
    }

    @Test
    void isNotConnectedBetweenConnackAndAGrantedSuback() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);
        fake.subAck = new CompletableFuture<>();
        Thread starter = Thread.startVirtualThread(fake::start);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 1);

        fake.connects.get(0).complete(mock(Mqtt5ConnAck.class));
        Thread.sleep(100);
        assertThat(fake.isConnected()).as("connected but not yet subscribed").isFalse();

        Mqtt5SubAck ack = mock(Mqtt5SubAck.class);
        when(ack.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1));
        fake.subAck.complete(ack);
        starter.join(2000);
        assertThat(fake.isConnected()).isTrue();

        fake.onDisconnected(fake.currentSession(), disconnected(MqttDisconnectSource.SERVER,
                new IllegalStateException("lost")));
        assertThat(fake.isConnected()).as("ready is cleared on disconnect").isFalse();
        fake.stop();
    }

    @Test
    void startGivesUpBeforeConnectingWhenItIsNoLongerValid() {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);

        assertThatThrownBy(() -> fake.start(() -> false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer valid");

        assertThat(fake.connectCount()).isZero();
        assertThat(fake.isConnected()).isFalse();
    }

    @Test
    void stopOnAConnectedSessionDisconnectsExactlyOnce() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);
        Thread starter = Thread.startVirtualThread(fake::start);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 1);
        fake.connects.get(0).complete(mock(Mqtt5ConnAck.class));
        starter.join(2000);
        Mqtt5AsyncClient client = fake.clients.get(0);

        fake.stop();

        verify(client, times(1)).disconnect();
    }

    @Test
    void startingTwiceClosesTheFirstSession() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(batcher);
        Thread first = Thread.startVirtualThread(() -> {
            try {
                fake.start();
            } catch (IllegalStateException expected) {
                // 第一次 start 被第二次 start 作废
            }
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 1);

        Thread second = Thread.startVirtualThread(() -> {
            try {
                fake.start();
            } catch (IllegalStateException expected) {
                // 测试结束时 stop 会使其失败
            }
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> fake.connectCount() == 2);

        first.join(2000);
        assertThat(first.isAlive()).as("the first start was abandoned").isFalse();
        verify(fake.clients.get(0), timeout(1000).atLeastOnce()).disconnect();
        fake.stop();
        second.join(2000);
    }

    @Test
    void sendsReliableAckOnlyAfterBusPublishAndBeforeAcknowledgeInABatch() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        BatchAckAdapter a = new BatchAckAdapter(newBatcher(3, Duration.ofSeconds(30)), events);
        MqttProtocolAdapter.Session s = a.openSession();
        bus.respondWith((call, batch) -> {
            events.add("bus");
            return FakeMessageBus.okResults(batch);
        });
        Mqtt5Publish reliable1 = publishOf("conduit/pk/dev-1/up/property", "{\"id\":\"a-1\",\"ts\":1}");
        Mqtt5Publish plain = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
        Mqtt5Publish reliable2 = publishOf("conduit/pk/dev-3/up/property", "{\"id\":\"c-3\",\"ts\":3}");
        recordAck(events, reliable1, "ack1");
        recordAck(events, plain, "ack2");
        recordAck(events, reliable2, "ack3");

        a.onPublish(s, reliable1);
        a.onPublish(s, plain);
        assertThat(events).isEmpty(); // 批未提交前既无回执也无 ACK
        a.onPublish(s, reliable2);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> events.size() == 6);
        // 总线写入在前；带 id 的先回执再 ACK；不带 id 的只 ACK；顺序与到达顺序一致
        assertThat(events).containsExactly("bus", "receipt:a-1", "ack1", "ack2", "receipt:c-3", "ack3");
        a.stop();
    }

    /** 把 acknowledge 调用按名字记入共享事件列表。 */
    private static void recordAck(List<String> events, Mqtt5Publish p, String name) {
        doAnswer(inv -> {
            events.add(name);
            return null;
        }).when(p).acknowledge();
    }

    /** 把业务回执按发送顺序记入共享事件列表，用于断言回执与总线写入、ACK 的先后。 */
    private static final class BatchAckAdapter extends MqttProtocolAdapter {
        private final List<String> events;

        BatchAckAdapter(SharedBatcher batcher, List<String> events) {
            super(props(1), batcher, FIXED);
            this.events = events;
        }

        @Override
        CompletableFuture<?> publishReliableAck(Session session, String topic, byte[] payload) {
            String text = new String(payload, StandardCharsets.UTF_8);
            events.add("receipt:" + text.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void doesNotAckPendingEntriesWhenTheSessionStopsBeforeTheDelayedFlush() throws Exception {
        // 延迟提交触发前会话已停止：待提交条目既不写总线也不 ACK，留给 broker 重投
        SharedBatcher slow = newBatcher(10, Duration.ofMillis(100));
        MqttProtocolAdapter a = new MqttProtocolAdapter(props(1), slow, FIXED);
        Mqtt5Publish only = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        a.onPublish(a.openSession(), only);

        a.stop();
        awaitMarker(slow);

        verify(only, never()).acknowledge();
        assertThat(bus.calls()).allSatisfy(call ->
                assertThat(call).extracting(RawMessage::deviceKey).doesNotContain("dev-1"));
    }

    /** 单连接或多连接的测试配置；批参数由合批器决定，这里的 batchSize / batchDelay 不起作用。 */
    private static MqttTransportProperties props(int connections) {
        return new MqttTransportProperties("localhost", 1883, "conduit-transport-ut",
                null, null, 86400L, "conduit-", 1, Duration.ofMillis(5), connections, "conduit-transport");
    }

    /** 按指定批参数新建一个共用测试总线替身的合批器，测试结束时统一关闭。 */
    private SharedBatcher newBatcher(int batchSize, Duration batchDelay) {
        SharedBatcher b = new SharedBatcher(bus, batchSize, batchDelay, Duration.ofMillis(1));
        extraBatchers.add(b);
        return b;
    }

    /**
     * 向合批器提交一条 live 的标记消息并等它回调。合批器单线程按序处理，标记回调时之前提交的条目都已处理完，
     * 可据此确定性地断言「从未 ACK」，而不必固定睡眠。
     */
    private static void awaitMarker(SharedBatcher target) throws InterruptedException {
        CountDownLatch marker = new CountDownLatch(1);
        target.submit(new RawMessage("pk", "marker", MessageType.PROPERTY, new byte[]{1}, 1L, Map.of()),
                () -> true, marker::countDown);
        assertThat(marker.await(2, TimeUnit.SECONDS)).as("marker committed").isTrue();
    }

    /** 构造一条指定 topic 与载荷的模拟 MQTT 消息。 */
    private static Mqtt5Publish publishOf(String topic, String payload) {
        Mqtt5Publish p = mock(Mqtt5Publish.class);
        when(p.getTopic()).thenReturn(MqttTopic.of(topic));
        when(p.getPayloadAsBytes()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test
    void startFailsCleanlyWhenTheBrokerIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var props = new MqttTransportProperties("127.0.0.1", closedPort, "conduit-transport-ut",
                null, null, 86400, "conduit-", 1, Duration.ofMillis(5), 1, "conduit-transport");
        MqttProtocolAdapter unreachable = new MqttProtocolAdapter(props, batcher, Clock.systemUTC());

        assertThatThrownBy(unreachable::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("127.0.0.1:" + closedPort);
        assertThat(unreachable.isConnected()).isFalse();
        assertThat(unreachable.needsRestart()).isFalse();
    }

    private static MqttClientDisconnectedContext disconnected(MqttDisconnectSource source, Throwable cause) {
        MqttClientDisconnectedContext context = mock(MqttClientDisconnectedContext.class);
        when(context.getSource()).thenReturn(source);
        when(context.getCause()).thenReturn(cause);
        return context;
    }
}
