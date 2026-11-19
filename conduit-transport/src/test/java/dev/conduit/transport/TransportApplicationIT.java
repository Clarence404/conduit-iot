package dev.conduit.transport;

import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * transport 装配冒烟测试：完整 Spring 上下文连接 Redis 与 EMQX，单实例经身份池分配器持有全部身份并连上，
 * 健康检查 components.allocation 显示 held / poolSize 且 connected 为 true。
 *
 * <p>只设旧键 conduit.transport.mqtt.connections=2，验证它经 application.yml 同时决定初始 K（poolSize 为 2）。
 *
 * <p>{@link DirtiesContext}：本类结束即关闭上下文（停适配器、释放租约）。否则 Spring 会缓存上下文到 JVM 结束，
 * 一个仍在订阅共享 EMQX 的 transport 会同时消费其他 IT（如 MqttProtocolAdapterIT）发布的消息、重复发回执。
 * 共享订阅组名与 clientId 也取随机值，进一步避免与其他 IT 的连接混在一个组里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "conduit.transport.monitor.enabled=false",
        "conduit.transport.mqtt.connections=2",
        "logging.file.name=target/test-logs/conduit-transport.log",
        "logging.logback.rollingpolicy.file-name-pattern=target/test-logs/conduit-transport.%d{yyyy-MM-dd}.%i.log.gz"})
@DirtiesContext
class TransportApplicationIT {

    static final String CLIENT_ID = "conduit-transport-app-it-" + UUID.randomUUID();
    static final String SHARED_GROUP = "app-it-" + UUID.randomUUID();
    static final String STREAM_KEY = "it:app:up:" + UUID.randomUUID();

    /**
     * 分配状态的 Redis 键是固定名（不随配置变化），上下文加载前清掉其他测试可能留下的值：
     * 残留的更大 pool-size 会让本实例按更大的 K 连接，残留的成员会让份额小于 2。
     */
    @BeforeAll
    static void clearAllocationKeys() {
        TestBroker.redis().delete(List.of("conduit:transport:pool-size", "conduit:transport:members",
                "conduit:transport:id:0", "conduit:transport:id:1"));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", TestBroker::redisHost);
        registry.add("spring.data.redis.port", TestBroker::redisPort);
        registry.add("conduit.transport.mqtt.host", TestBroker::emqxHost);
        registry.add("conduit.transport.mqtt.port", TestBroker::emqxPort);
        registry.add("conduit.transport.mqtt.client-id", () -> CLIENT_ID);
        registry.add("conduit.transport.mqtt.shared-group", () -> SHARED_GROUP);
        registry.add("conduit.bus.stream-key", () -> STREAM_KEY);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthShowsHeldIdentitiesOnceConnected() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = rest.getForObject("/actuator/health", String.class);
            assertThat(body).contains("\"allocation\"").contains("\"held\":2").contains("\"poolSize\":2")
                    .contains("\"members\":1").contains("\"connected\":true").contains("\"instanceId\":")
                    .contains("\"status\":\"UP\"");
        });
    }
}
