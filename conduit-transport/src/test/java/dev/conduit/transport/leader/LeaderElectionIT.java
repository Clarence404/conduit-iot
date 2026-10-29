package dev.conduit.transport.leader;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.common.bus.RedisStreamMessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.transport.leader.LeaderElection.Role;
import dev.conduit.transport.mqtt.MqttProtocolAdapter;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 选主集成测试（真实 Redis + 内存持久会话 EMQX，ttl 3s / 续期 1s / 抢锁 0.5s）：两个实例只有一个成为主且只有它连接；
 * 主实例停止后备实例在 ttl 内接管并补齐切换期间发布的消息；续期失败（锁被改写）立即断开并保持备用，锁恢复空闲后重新成为主。
 */
class LeaderElectionIT {

    static final Duration TTL = Duration.ofSeconds(3);
    static final Duration RENEW = Duration.ofSeconds(1);
    static final Duration ACQUIRE = Duration.ofMillis(500);

    final StringRedisTemplate redis = TestBroker.redis();
    final String key = "it:leader:" + UUID.randomUUID();
    final String streamKey = "it:up:" + UUID.randomUUID();
    final String clientId = "conduit-transport-it-" + UUID.randomUUID();
    final String productKey = "pk" + UUID.randomUUID().toString().substring(0, 8);
    final List<LeaderElection> elections = new ArrayList<>();
    final Map<LeaderElection, MqttProtocolAdapter> adapters = new HashMap<>();
    final List<Mqtt5BlockingClient> devices = new ArrayList<>();
    final Set<String> received = ConcurrentHashMap.newKeySet();
    MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new RedisStreamMessageBus(redis, new MessageBusProperties(streamKey, "it:dead", 100_000, 1000));
        bus.ensureGroup("it");
    }

    @AfterEach
    void tearDown() {
        elections.forEach(LeaderElection::stop);
        devices.forEach(d -> {
            if (d.getState().isConnected()) {
                d.disconnect();
            }
        });
        redis.delete(List.of(key, streamKey));
    }

    private LeaderElection instance(String name) {
        var props = new MqttTransportProperties(TestBroker.emqxHost(), TestBroker.emqxPort(), clientId,
                null, null, 86400, "conduit-", 1, Duration.ofMillis(5), 1, "conduit-transport");
        MqttProtocolAdapter adapter = new MqttProtocolAdapter(props, bus, Clock.systemUTC());
        LeaderElection election = new LeaderElection(new RedisLeaderLock(redis, key, TTL, name), List.of(adapter),
                new LeaderProperties(key, TTL, RENEW, ACQUIRE));
        elections.add(election);
        adapters.put(election, adapter);
        return election;
    }

    private LeaderElection awaitConnectedLeader() {
        AtomicReference<LeaderElection> leader = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<LeaderElection> leaders = elections.stream().filter(e -> e.role() == Role.LEADER).toList();
            assertThat(leaders).hasSize(1);
            assertThat(leaders.getFirst().adaptersConnected()).isTrue();
            leader.set(leaders.getFirst());
        });
        return leader.get();
    }

    private Mqtt5BlockingClient device(String deviceKey) {
        Mqtt5BlockingClient client = MqttClient.builder().useMqttVersion5().identifier(deviceKey)
                .serverHost(TestBroker.emqxHost()).serverPort(TestBroker.emqxPort()).buildBlocking();
        client.connect();
        devices.add(client);
        return client;
    }

    private void drain() {
        List<Delivery> batch;
        while (!(batch = bus.read("it", "test", 500, Duration.ofMillis(50))).isEmpty()) {
            for (Delivery d : batch) {
                RawMessage m = d.message();
                if (m.type() == MessageType.PROPERTY && productKey.equals(m.productKey())) {
                    received.add(new String(m.payload(), StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test
    void onlyOneOfTwoInstancesBecomesLeaderAndOnlyItConnects() throws Exception {
        LeaderElection a = instance("instance-a");
        LeaderElection b = instance("instance-b");

        a.start();
        b.start();

        LeaderElection leader = awaitConnectedLeader();
        LeaderElection standby = leader == a ? b : a;
        for (int i = 0; i < 30; i++) {
            assertThat(elections.stream().filter(e -> e.role() == Role.LEADER).count()).isEqualTo(1);
            Thread.sleep(100);
        }
        assertThat(redis.opsForValue().get(key)).isEqualTo(leader.instanceId());
        assertThat(adapters.get(standby).isConnected()).isFalse();
    }

    @Test
    void theStandbyTakesOverWithinTheTtlAndReceivesTheBacklog() throws Exception {
        LeaderElection a = instance("instance-a");
        LeaderElection b = instance("instance-b");
        a.start();
        b.start();
        LeaderElection leader = awaitConnectedLeader();
        LeaderElection standby = leader == a ? b : a;
        Mqtt5BlockingClient device = device("dk-failover");
        AtomicInteger published = new AtomicInteger();
        CompletableFuture<Void> publishing = CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= 300; i++) {
                device.publishWith().topic("conduit/" + productKey + "/dk-failover/up/property")
                        .qos(MqttQos.AT_LEAST_ONCE).payload(("{\"seq\":" + i + "}").getBytes(StandardCharsets.UTF_8))
                        .send();
                published.incrementAndGet();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        await().atMost(Duration.ofSeconds(20)).until(() -> published.get() >= 100);

        leader.stop();

        await().atMost(TTL).until(() -> standby.role() == Role.LEADER);
        await().atMost(Duration.ofSeconds(15)).until(standby::adaptersConnected);
        publishing.get(60, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            drain();
            assertThat(received).hasSize(300);
        });
    }

    @Test
    void aFailedRenewalDisconnectsAtOnceAndTheInstanceWaitsAsStandby() throws Exception {
        LeaderElection a = instance("instance-a");
        a.start();
        awaitConnectedLeader();

        redis.opsForValue().set(key, "another-instance", Duration.ofMinutes(1));

        await().atMost(RENEW.plusSeconds(3)).untilAsserted(() -> {
            assertThat(a.role()).isEqualTo(Role.STANDBY);
            assertThat(adapters.get(a).isConnected()).isFalse();
        });
        Thread.sleep(ACQUIRE.toMillis() * 3);
        assertThat(a.role()).as("the lock belongs to another instance").isEqualTo(Role.STANDBY);

        redis.delete(key);
        awaitConnectedLeader();
    }
}
