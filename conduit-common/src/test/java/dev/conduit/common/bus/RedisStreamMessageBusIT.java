package dev.conduit.common.bus;

import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisStreamMessageBusIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    static final String GROUP = "test-group";
    MessageBus bus;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        bus = new RedisStreamMessageBus(redis,
                new MessageBusProperties("test:up", "test:dead", 1000, 1000));
        bus.ensureGroup(GROUP);
    }

    private static RawMessage message(String deviceKey) {
        return new RawMessage("pk1", deviceKey, MessageType.PROPERTY,
                "{\"params\":{\"Ua\":1}}".getBytes(StandardCharsets.UTF_8), 42L, Map.of("topic", "t"));
    }

    @Test
    void publishesBatchInOrderAndReturnsOneResultPerMessage() {
        List<RawMessage> messages = List.of(
                new RawMessage("pk", "dev-1", MessageType.PROPERTY, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                        1_000L, Map.of("topic", "t1")),
                new RawMessage("pk", "dev-2", MessageType.PROPERTY, "{\"a\":2}".getBytes(StandardCharsets.UTF_8),
                        2_000L, Map.of("topic", "t2")));

        List<PublishResult> results = bus.publish(messages);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(PublishResult::succeeded);
        // 条目 id 单调递增，确认写入顺序与入参一致
        assertThat(results.get(0).id()).isLessThan(results.get(1).id());

        bus.ensureGroup("g-batch");
        List<Delivery> read = bus.read("g-batch", "c1", 10, Duration.ofSeconds(1));
        assertThat(read).extracting(d -> d.message().deviceKey()).containsExactly("dev-1", "dev-2");
    }

    @Test
    void emptyBatchPublishesNothing() {
        assertThat(bus.publish(List.of())).isEmpty();
    }

    @Test
    void ensureGroupIsIdempotent() {
        bus.ensureGroup(GROUP);
        bus.ensureGroup(GROUP);
    }

    @Test
    void publishedMessageIsReadOnceWithDeliveryCountOne() {
        String id = bus.publish(message("dk1"));

        List<Delivery> first = bus.read(GROUP, "c1", 10, Duration.ofMillis(100));
        List<Delivery> second = bus.read(GROUP, "c1", 10, Duration.ofMillis(100));

        assertThat(first).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(id);
            assertThat(d.deliveryCount()).isEqualTo(1);
            assertThat(d.message()).usingRecursiveComparison().isEqualTo(message("dk1"));
        });
        assertThat(second).isEmpty();
    }

    @Test
    void unackedMessageCanBeClaimedWithIncreasedDeliveryCount() {
        bus.publish(message("dk1"));
        bus.read(GROUP, "crashed", 10, Duration.ofMillis(100));

        List<Delivery> claimed = bus.claimStale(GROUP, "c2", Duration.ZERO, 10);

        assertThat(claimed).singleElement()
                .satisfies(d -> assertThat(d.deliveryCount()).isEqualTo(2));
    }

    @Test
    void claimSkipsMessagesNotIdleLongEnough() {
        bus.publish(message("dk1"));
        bus.read(GROUP, "c1", 10, Duration.ofMillis(100));

        assertThat(bus.claimStale(GROUP, "c2", Duration.ofMinutes(5), 10)).isEmpty();
    }

    @Test
    void ackedMessageIsNoLongerPending() {
        bus.publish(message("dk1"));
        Delivery d = bus.read(GROUP, "c1", 10, Duration.ofMillis(100)).getFirst();

        bus.ack(GROUP, List.of(d.id()));

        assertThat(bus.claimStale(GROUP, "c2", Duration.ZERO, 10)).isEmpty();
    }

    @Test
    void deadLetterStoresReasonAndAcksOriginal() {
        bus.publish(message("dk1"));
        Delivery d = bus.read(GROUP, "c1", 10, Duration.ofMillis(100)).getFirst();

        bus.deadLetter(GROUP, d, "UNKNOWN_DEVICE");

        var dead = redis.opsForStream().range("test:dead", org.springframework.data.domain.Range.unbounded());
        assertThat(dead).singleElement().satisfies(r -> {
            assertThat(r.getValue()).containsEntry("reason", "UNKNOWN_DEVICE");
            assertThat(r.getValue()).containsEntry("originalId", d.id());
            assertThat(r.getValue()).containsEntry("dk", "dk1");
            assertThat(r.getValue()).containsEntry("deliveryCount", "1");
            assertThat(r.getValue()).containsKey("failedAt");
        });
        assertThat(bus.claimStale(GROUP, "c2", Duration.ZERO, 10)).isEmpty();
    }

    @Test
    void recordDeadWritesDetailsButDoesNotAck() {
        bus.publish(message("dk1"));
        Delivery d = bus.read(GROUP, "c1", 10, Duration.ofMillis(100)).getFirst();

        bus.recordDead(d, "FIELD_REJECTED", Map.of("rejected", "[{\"field\":\"Ua\"}]", "reason", "spoofed"));

        var dead = redis.opsForStream().range("test:dead", org.springframework.data.domain.Range.unbounded());
        assertThat(dead).singleElement().satisfies(r -> {
            assertThat(r.getValue()).containsEntry("reason", "FIELD_REJECTED");
            assertThat(r.getValue()).containsEntry("rejected", "[{\"field\":\"Ua\"}]");
            assertThat(r.getValue()).containsEntry("originalId", d.id());
            assertThat(r.getValue()).containsEntry("dk", "dk1");
        });
        assertThat(bus.claimStale(GROUP, "c2", Duration.ZERO, 10)).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo(d.id()));
    }

    @Test
    void readReturnsEmptyListWhenNothingArrives() {
        assertThat(bus.read(GROUP, "c1", 10, Duration.ofMillis(50))).isEmpty();
    }

    @Test
    void claimStaleIsNotStarvedByRecentlyClaimedEntriesAtHeadOfPending() throws InterruptedException {
        bus.publish(message("dk1"));
        bus.publish(message("dk2"));
        String staleId = bus.publish(message("dk3"));
        bus.read(GROUP, "a", 10, Duration.ofMillis(100));
        Thread.sleep(400);
        // re-claim the first two: they keep their IDs at the head of the PEL but their idle time resets
        assertThat(bus.claimStale(GROUP, "b", Duration.ZERO, 2)).hasSize(2);

        List<Delivery> claimed = bus.claimStale(GROUP, "c", Duration.ofMillis(300), 2);

        assertThat(claimed).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(staleId);
            assertThat(d.deliveryCount()).isEqualTo(2);
        });
    }

    @Test
    void publishTrimsStreamApproximately() {
        MessageBus small = new RedisStreamMessageBus(redis,
                new MessageBusProperties("test:up", "test:dead", 10, 1000));

        for (int i = 0; i < 500; i++) {
            small.publish(message("dk" + i));
        }

        assertThat(redis.opsForStream().size("test:up")).isLessThan(200);
    }

    @Test
    void deadLetterStreamIsTrimmedApproximately() {
        MessageBus small = new RedisStreamMessageBus(redis,
                new MessageBusProperties("test:up", "test:dead", 100000, 10));
        small.ensureGroup(GROUP);
        for (int i = 0; i < 500; i++) {
            small.publish(message("dk" + i));
        }
        for (Delivery d : small.read(GROUP, "c1", 500, Duration.ofMillis(100))) {
            small.deadLetter(GROUP, d, "UNKNOWN_DEVICE");
        }

        assertThat(redis.opsForStream().size("test:dead")).isLessThan(200);
    }

    @Test
    void rangeReturnsTheEntriesBetweenTwoIdsInclusive() {
        String first = bus.publish(message("d1"));
        String second = bus.publish(message("d2"));
        bus.publish(message("d3"));

        List<Delivery> entries = bus.range(first, second, 100);

        assertThat(entries).extracting(Delivery::id).containsExactly(first, second);
        assertThat(entries.getFirst().message().deviceKey()).isEqualTo("d1");
        assertThat(entries.getFirst().fields()).containsEntry("dk", "d1");
    }

    @Test
    void rangeMarksEntriesAsNotDeliveredThroughAGroup() {
        String id = bus.publish(message("d1"));

        assertThat(bus.range(id, id, 100)).singleElement()
                .extracting(Delivery::deliveryCount).isEqualTo(0L);
    }

    @Test
    void rangeHonoursTheCountLimit() {
        String first = bus.publish(message("d1"));
        String second = bus.publish(message("d2"));
        bus.publish(message("d3"));

        assertThat(bus.range("0-0", "99999999999999-0", 2))
                .extracting(Delivery::id).containsExactly(first, second);
    }

    @Test
    void rangeDoesNotConsumeTheEntries() {
        bus.publish(message("d1"));
        bus.publish(message("d2"));

        assertThat(bus.range("0-0", "99999999999999-0", 100)).hasSize(2);
        assertThat(bus.read(GROUP, "c1", 10, Duration.ofMillis(100))).hasSize(2);
    }

    @Test
    void rangeWithMaxSequenceEndIncludesEntriesOfThatMillisecond() {
        String id = bus.publish(message("d1"));
        String ms = id.substring(0, id.indexOf('-'));

        assertThat(bus.range(ms + "-0", ms + "-18446744073709551615", 10))
                .extracting(Delivery::id).containsExactly(id);
    }

    @Test
    void rangeCoveringNoEntriesReturnsEmptyList() {
        assertThat(bus.range("0-0", "99999999999999-0", 10)).isEmpty();

        String id = bus.publish(message("d1"));
        long ms = Long.parseLong(id.substring(0, id.indexOf('-')));
        assertThat(bus.range((ms + 1000) + "-0", (ms + 2000) + "-0", 10)).isEmpty();
    }
}
