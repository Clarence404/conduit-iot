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
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.PublishResult;
import dev.conduit.common.message.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqttProtocolAdapter 单元测试：写入总线成功后才 ACK、停止时放弃重试、自有客户端上下线直接 ACK、
 * 意外断线与会话被接管时请求重启、SUBACK 含错误码视为订阅失败、连不上 broker 时 start 失败且不留状态。
 */
class MqttProtocolAdapterTest {

    MessageBus bus;
    Mqtt5Publish publish;
    MqttProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        bus = mock(MessageBus.class);
        publish = mock(Mqtt5Publish.class);
        when(publish.getTopic()).thenReturn(MqttTopic.of("conduit/pk1/dk1/up/property"));
        when(publish.getPayloadAsBytes()).thenReturn(new byte[]{1});
        var props = new MqttTransportProperties("localhost", 1883, "conduit-transport-ut",
                null, null, 86400, "conduit-", 1, Duration.ofMillis(5));
        adapter = new MqttProtocolAdapter(props, bus,
                Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC), Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        adapter.stop();
    }

    @Test
    void acksOnlyAfterSuccessfulPublishAndRetriesInline() {
        when(bus.publish(anyList()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(List.of(PublishResult.ok("1-0")));

        adapter.onPublish(adapter.openSession(), publish);

        InOrder order = inOrder(bus, publish);
        order.verify(bus, times(3)).publish(anyList());
        order.verify(publish, times(1)).acknowledge();
    }

    @Test
    void doesNotAckWhilePublishKeepsFailingThenGivesUpWhenStopped() throws Exception {
        when(bus.publish(anyList())).thenThrow(new IllegalStateException("redis down"));
        MqttProtocolAdapter.Session session = adapter.openSession();
        Thread t = Thread.startVirtualThread(() -> adapter.onPublish(session, publish));
        Thread.sleep(50);

        adapter.stop();
        t.join(2000);

        assertThat(t.isAlive()).isFalse();
        verify(publish, never()).acknowledge();
    }

    @Test
    void ackFailureDoesNotRepublish() {
        when(bus.publish(anyList())).thenReturn(List.of(PublishResult.ok("1-0")));
        doThrow(new IllegalStateException("connection lost")).when(publish).acknowledge();

        adapter.onPublish(adapter.openSession(), publish);

        verify(bus, times(1)).publish(anyList());
        verify(publish, times(1)).acknowledge();
    }

    @Test
    void sendsApplicationAckForReliablePropertyAfterBusPublish() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(bus);
        when(bus.publish(anyList())).thenReturn(List.of(PublishResult.ok("1-0")));
        when(publish.getPayloadAsBytes())
                .thenReturn("{\"id\":\"dk1-42\",\"ts\":1760400000000,\"params\":{\"Ua\":220.1}}"
                        .getBytes(StandardCharsets.UTF_8));

        reliable.onPublish(reliable.openSession(), publish);

        assertThat(reliable.ackTopic).isEqualTo("conduit/pk1/dk1/down/ack");
        assertThat(new String(reliable.ackPayload, StandardCharsets.UTF_8))
                .contains("\"id\":\"dk1-42\"")
                .contains("\"ts\":1");
        verify(bus).publish(anyList());
        verify(publish).acknowledge();
        reliable.stop();
    }

    @Test
    void skipsApplicationAckWithoutAnIdOrForMalformedJson() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(bus);
        when(bus.publish(anyList())).thenReturn(List.of(PublishResult.ok("1-0")));
        when(publish.getPayloadAsBytes()).thenReturn("{\"ts\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8));
        reliable.onPublish(reliable.openSession(), publish);
        assertThat(reliable.ackTopic).isNull();

        when(publish.getPayloadAsBytes()).thenReturn(new byte[]{1, 2, 3});
        reliable.onPublish(reliable.openSession(), publish);

        assertThat(reliable.ackTopic).isNull();
        verify(bus, times(2)).publish(anyList());
        verify(publish, times(2)).acknowledge();
        reliable.stop();
    }

    @Test
    void applicationAckSendFailureStillAcknowledgesTheUplink() {
        AckCapturingAdapter reliable = new AckCapturingAdapter(bus);
        reliable.ackResult = CompletableFuture.failedFuture(new IllegalStateException("connection lost"));
        when(bus.publish(anyList())).thenReturn(List.of(PublishResult.ok("1-0")));
        when(publish.getPayloadAsBytes())
                .thenReturn("{\"id\":\"dk1-1\",\"ts\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8));

        reliable.onPublish(reliable.openSession(), publish);

        assertThat(reliable.ackTopic).isEqualTo("conduit/pk1/dk1/down/ack");
        verify(bus).publish(anyList());
        verify(publish).acknowledge();
        reliable.stop();
    }

    @Test
    void presenceOfThePlatformsOwnClientIsAcknowledgedWithoutPublishing() {
        when(publish.getTopic())
                .thenReturn(MqttTopic.of("$SYS/brokers/emqx@127.0.0.1/clients/conduit-transport/connected"));

        adapter.onPublish(adapter.openSession(), publish);

        verify(bus, never()).publish(anyList());
        verify(publish).acknowledge();
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

        FakeConnectAdapter(MessageBus bus) {
            super(new MqttTransportProperties("localhost", 1883, "conduit-transport-ut", null, null, 86400,
                    "conduit-", 1, Duration.ofMillis(5)), bus, Clock.systemUTC(), Duration.ofMillis(1));
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
        String ackTopic;
        byte[] ackPayload;
        CompletableFuture<?> ackResult = CompletableFuture.completedFuture(null);

        AckCapturingAdapter(MessageBus bus) {
            super(new MqttTransportProperties("localhost", 1883, "conduit-transport-ut", null, null, 86400,
                    "conduit-", 1, Duration.ofMillis(5)), bus, Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC), Duration.ofMillis(1));
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
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);
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
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);
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
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);
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
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);

        assertThatThrownBy(() -> fake.start(() -> false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer valid");

        assertThat(fake.connectCount()).isZero();
        assertThat(fake.isConnected()).isFalse();
    }

    @Test
    void stopOnAConnectedSessionDisconnectsExactlyOnce() throws Exception {
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);
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
        FakeConnectAdapter fake = new FakeConnectAdapter(bus);
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
    void batchesPublishesUntilBatchSizeIsReached() {
        // 批大小 2：收到两条即提交一次，两条都 ACK
        MqttProtocolAdapter a = adapterWithBatch(2, Duration.ofSeconds(30));
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

        Mqtt5Publish first = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        Mqtt5Publish second = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
        a.onPublish(s, first);
        a.onPublish(s, second);

        verify(bus, times(1)).publish(argThat((List<RawMessage> b) -> b.size() == 2));
        verify(first).acknowledge();
        verify(second).acknowledge();
        a.stop();
    }

    @Test
    void flushesPartialBatchAfterBatchDelay() {
        // 批大小 10、延迟 50ms：只来一条，也必须在延迟到期后提交，不能无限等
        MqttProtocolAdapter a = adapterWithBatch(10, Duration.ofMillis(50));
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

        Mqtt5Publish only = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        a.onPublish(s, only);

        verify(bus, timeout(2000).times(1)).publish(argThat((List<RawMessage> b) -> b.size() == 1));
        verify(only, timeout(2000)).acknowledge();
        a.stop();
    }

    @Test
    void retriesOnlyTheFailedEntriesOfAPartiallySuccessfulBatchBeforeAcking() {
        // 逐条失败不能留下未 ACK 的空洞（EMQX retry_interval=infinity 会耗尽 inflight 窗口）：只重试失败条目
        MqttProtocolAdapter a = adapterWithBatch(2, Duration.ofSeconds(30));
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList()))
                .thenReturn(List.of(PublishResult.ok("1-0"),
                        PublishResult.failed(new IllegalStateException("boom"))))
                .thenAnswer(inv -> okResults(inv.getArgument(0)));

        Mqtt5Publish good = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        Mqtt5Publish bad = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
        a.onPublish(s, good);
        a.onPublish(s, bad);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawMessage>> batches = ArgumentCaptor.forClass(List.class);
        verify(bus, times(2)).publish(batches.capture());
        assertThat(batches.getAllValues().get(0)).extracting(RawMessage::deviceKey)
                .containsExactly("dev-1", "dev-2");
        assertThat(batches.getAllValues().get(1)).extracting(RawMessage::deviceKey)
                .containsExactly("dev-2");
        InOrder order = inOrder(good, bad);
        order.verify(good).acknowledge();
        order.verify(bad).acknowledge();
        a.stop();
    }

    @Test
    void retriesAfterWholeBatchExceptionWithoutDuplicatingOrDroppingAcks() {
        MqttProtocolAdapter a = adapterWithBatch(3, Duration.ofSeconds(30));
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(List.of(PublishResult.ok("1-0"), PublishResult.failed(new IllegalStateException("x")),
                        PublishResult.ok("3-0")))
                .thenAnswer(inv -> okResults(inv.getArgument(0)));

        Mqtt5Publish p1 = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        Mqtt5Publish p2 = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
        Mqtt5Publish p3 = publishOf("conduit/pk/dev-3/up/property", "{\"ts\":3}");
        a.onPublish(s, p1);
        a.onPublish(s, p2);
        a.onPublish(s, p3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawMessage>> batches = ArgumentCaptor.forClass(List.class);
        verify(bus, times(3)).publish(batches.capture());
        assertThat(batches.getAllValues().get(0)).hasSize(3);
        assertThat(batches.getAllValues().get(1)).hasSize(3);
        assertThat(batches.getAllValues().get(2)).extracting(RawMessage::deviceKey).containsExactly("dev-2");
        InOrder order = inOrder(p1, p2, p3);
        order.verify(p1).acknowledge();
        order.verify(p2).acknowledge();
        order.verify(p3).acknowledge();
        verify(p1, times(1)).acknowledge();
        verify(p2, times(1)).acknowledge();
        verify(p3, times(1)).acknowledge();
        a.stop();
    }

    @Test
    void sendsReliableAckOnlyAfterBusPublishAndBeforeAcknowledgeInABatch() {
        List<String> events = new ArrayList<>();
        BatchAckAdapter a = new BatchAckAdapter(bus, 3, events);
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList())).thenAnswer(inv -> {
            events.add("bus");
            return okResults(inv.getArgument(0));
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

        BatchAckAdapter(MessageBus bus, int batchSize, List<String> events) {
            super(new MqttTransportProperties("localhost", 1883, "conduit-transport-ut", null, null, 86400,
                    "conduit-", batchSize, Duration.ofSeconds(30)), bus,
                    Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC), Duration.ofMillis(1));
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
    void preservesArrivalOrderWithinABatch() {
        MqttProtocolAdapter a = adapterWithBatch(3, Duration.ofSeconds(30));
        MqttProtocolAdapter.Session s = a.openSession();
        when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

        a.onPublish(s, publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}"));
        a.onPublish(s, publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}"));
        a.onPublish(s, publishOf("conduit/pk/dev-3/up/property", "{\"ts\":3}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawMessage>> batch = ArgumentCaptor.forClass(List.class);
        verify(bus).publish(batch.capture());
        assertThat(batch.getValue()).extracting(RawMessage::deviceKey)
                .containsExactly("dev-1", "dev-2", "dev-3");
        a.stop();
    }

    @Test
    void doesNotAckPendingEntriesWhenTheSessionStopsBeforeTheDelayedFlush() throws Exception {
        // 延迟提交触发前会话已停止：待提交条目既不写总线也不 ACK，留给 broker 重投
        MqttProtocolAdapter a = adapterWithBatch(10, Duration.ofMillis(100));
        MqttProtocolAdapter.Session s = a.openSession();
        Mqtt5Publish only = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
        a.onPublish(s, only);

        a.stop();
        Thread.sleep(300);

        verify(bus, never()).publish(anyList());
        verify(only, never()).acknowledge();
    }

    /** 按指定批参数构造被测适配器；其余依赖沿用测试类已有的 mock。 */
    private MqttProtocolAdapter adapterWithBatch(int batchSize, Duration batchDelay) {
        var p = new MqttTransportProperties("localhost", 1883, "conduit-transport-ut",
                null, null, 86400L, "conduit-", batchSize, batchDelay);
        return new MqttProtocolAdapter(p, bus, Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC),
                Duration.ofMillis(1));
    }

    /** 构造一条指定 topic 与载荷的模拟 MQTT 消息。 */
    private static Mqtt5Publish publishOf(String topic, String payload) {
        Mqtt5Publish p = mock(Mqtt5Publish.class);
        when(p.getTopic()).thenReturn(MqttTopic.of(topic));
        when(p.getPayloadAsBytes()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** 为整批生成全部成功的结果，id 按下标编号。 */
    private static List<PublishResult> okResults(List<RawMessage> batch) {
        List<PublishResult> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            results.add(PublishResult.ok((i + 1) + "-0"));
        }
        return results;
    }

    @Test
    void startFailsCleanlyWhenTheBrokerIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var props = new MqttTransportProperties("127.0.0.1", closedPort, "conduit-transport-ut",
                null, null, 86400, "conduit-", 1, Duration.ofMillis(5));
        MqttProtocolAdapter unreachable = new MqttProtocolAdapter(props, bus, Clock.systemUTC());

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
