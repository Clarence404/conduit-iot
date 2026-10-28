package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.common.bus.RedisStreamMessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * MqttProtocolAdapter 对真实 EMQX（磁盘持久会话）的集成测试：属性与上下线事件进入总线、无法路由的 topic 不阻塞、
 * 停止期间发布的 2000 条在重新 start 后全部收到、同 clientId 的新连接踢掉旧连接且旧连接不自行重连。
 */
class MqttProtocolAdapterIT {

    static final String GROUP = "it";
    static final long NOW = 1_760_400_000_000L;

    final StringRedisTemplate redis = TestBroker.redis();
    final String streamKey = "it:up:" + UUID.randomUUID();
    final String clientId = "conduit-transport-it-" + UUID.randomUUID();
    final String productKey = "pk" + UUID.randomUUID().toString().substring(0, 8);
    final List<MqttProtocolAdapter> adapters = new ArrayList<>();
    final List<Mqtt5BlockingClient> devices = new ArrayList<>();
    MessageBus bus;
    MqttProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        bus = new RedisStreamMessageBus(redis, new MessageBusProperties(streamKey, "it:dead", 100_000, 1000));
        bus.ensureGroup(GROUP);
        adapter = newAdapter();
        adapter.start();
    }

    @AfterEach
    void tearDown() {
        devices.forEach(d -> {
            if (d.getState().isConnected()) {
                d.disconnect();
            }
        });
        adapters.forEach(MqttProtocolAdapter::stop);
        redis.delete(streamKey);
    }

    private MqttProtocolAdapter newAdapter() {
        var props = new MqttTransportProperties(TestBroker.emqxHost(), TestBroker.emqxPort(), clientId,
                null, null, 86400, "conduit-", 1, Duration.ofMillis(5));
        MqttProtocolAdapter a = new MqttProtocolAdapter(props, bus,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        adapters.add(a);
        return a;
    }

    private Mqtt5BlockingClient device(String deviceKey) {
        Mqtt5BlockingClient client = MqttClient.builder().useMqttVersion5()
                .identifier(deviceKey)
                .serverHost(TestBroker.emqxHost()).serverPort(TestBroker.emqxPort())
                .buildBlocking();
        client.connect();
        devices.add(client);
        return client;
    }

    private void publish(Mqtt5BlockingClient device, String deviceKey, String suffix, byte[] payload) {
        device.publishWith().topic("conduit/" + productKey + "/" + deviceKey + "/up/" + suffix)
                .qos(MqttQos.AT_LEAST_ONCE).payload(payload).send();
    }

    private List<Delivery> drain() {
        List<Delivery> all = new ArrayList<>();
        List<Delivery> batch;
        while (!(batch = bus.read(GROUP, "test", 500, Duration.ofMillis(50))).isEmpty()) {
            all.addAll(batch);
        }
        return all;
    }

    @Test
    void forwardsPropertyMessageToBus() {
        byte[] payload = "{\"params\":{\"Ua\":220.1}}".getBytes(StandardCharsets.UTF_8);
        publish(device("dk-prop"), "dk-prop", "property", payload);

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> assertThat(d.message().type()).isEqualTo(MessageType.PROPERTY));
        });

        RawMessage message = received.stream().map(Delivery::message)
                .filter(m -> m.type() == MessageType.PROPERTY).findFirst().orElseThrow();
        assertThat(message.productKey()).isEqualTo(productKey);
        assertThat(message.deviceKey()).isEqualTo("dk-prop");
        assertThat(message.payload()).containsExactly(payload);
        assertThat(message.receivedAt()).isEqualTo(NOW);
        assertThat(message.attributes()).containsEntry("topic", "conduit/" + productKey + "/dk-prop/up/property");
    }

    @Test
    void acknowledgesAReliablePropertyAfterWritingItToTheBus() throws Exception {
        Mqtt5BlockingClient client = device("dk-reliable");
        List<byte[]> acknowledgements = new CopyOnWriteArrayList<>();
        client.toAsync().subscribeWith().topicFilter("conduit/" + productKey + "/dk-reliable/down/ack")
                .qos(MqttQos.AT_MOST_ONCE).callback(p -> acknowledgements.add(p.getPayloadAsBytes())).send().join();

        publish(client, "dk-reliable", "property",
                "{\"id\":\"dk-reliable-7\",\"ts\":1760400000000,\"params\":{\"Ua\":220.1}}"
                        .getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(acknowledgements).hasSize(1));
        JsonNode ack = new ObjectMapper().readTree(acknowledgements.getFirst());
        assertThat(ack.get("id").asText()).isEqualTo("dk-reliable-7");
        assertThat(ack.get("ts").asLong()).isEqualTo(NOW);
        assertThat(drain()).anySatisfy(d -> {
            assertThat(d.message().deviceKey()).isEqualTo("dk-reliable");
            assertThat(d.message().type()).isEqualTo(MessageType.PROPERTY);
        });
    }

    @Test
    void forwardsPresenceEventsButIgnoresOwnClient() {
        Mqtt5BlockingClient own = device("conduit-internal-" + UUID.randomUUID());
        own.disconnect();
        String deviceKey = "dk-presence-" + UUID.randomUUID();
        Mqtt5BlockingClient client = device(deviceKey);
        client.disconnect();

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> {
                assertThat(d.message().deviceKey()).isEqualTo(deviceKey);
                assertThat(d.message().type()).isEqualTo(MessageType.PRESENCE_DISCONNECTED);
            });
        });

        assertThat(received).anySatisfy(d -> {
            assertThat(d.message().deviceKey()).isEqualTo(deviceKey);
            assertThat(d.message().type()).isEqualTo(MessageType.PRESENCE_CONNECTED);
        });
        // 自有客户端的事件先于本设备产生，此时必已处理完毕
        assertThat(received).noneSatisfy(d -> assertThat(d.message().deviceKey()).startsWith("conduit-"));
    }

    @Test
    void dropsUnroutableTopicWithoutBlocking() {
        Mqtt5BlockingClient client = device("dk-bad");
        publish(client, "dk-bad", "unknown", new byte[]{1});
        publish(client, "dk-bad", "property", new byte[]{2});

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> assertThat(d.message().type()).isEqualTo(MessageType.PROPERTY));
        });
        assertThat(received).noneSatisfy(d ->
                assertThat(d.message().attributes().get("topic")).endsWith("/up/unknown"));
    }

    @Test
    void receivesEveryMessagePublishedWhileStopped() {
        adapter.stop();
        Mqtt5BlockingClient client = device("dk-offline");
        for (int i = 1; i <= 2000; i++) {
            publish(client, "dk-offline", "property", ("{\"seq\":" + i + "}").getBytes(StandardCharsets.UTF_8));
        }

        adapter.start();

        Set<String> payloads = ConcurrentHashMap.newKeySet();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            drain().stream().map(Delivery::message)
                    .filter(m -> m.type() == MessageType.PROPERTY && productKey.equals(m.productKey()))
                    .forEach(m -> payloads.add(new String(m.payload(), StandardCharsets.UTF_8)));
            assertThat(payloads).hasSize(2000);
        });
    }

    @Test
    void aSecondConnectionWithTheSameClientIdKicksTheFirstWhichStaysDown() throws Exception {
        MqttProtocolAdapter second = newAdapter();

        second.start();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(adapter.isConnected()).isFalse();
            assertThat(adapter.needsRestart()).isTrue();
        });
        assertThat(second.isConnected()).isTrue();
        Thread.sleep(2000);
        assertThat(adapter.isConnected()).as("the kicked connection does not reconnect by itself").isFalse();
        assertThat(second.isConnected()).isTrue();
    }
}
