package dev.conduit.transport;

import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** transport 装配冒烟测试：完整 Spring 上下文连接 Redis 与 EMQX，单实例成为主实例，健康检查显示 role 与 connected。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "conduit.transport.monitor.enabled=false",
        "logging.file.name=target/test-logs/conduit-transport.log",
        "logging.logback.rollingpolicy.file-name-pattern=target/test-logs/conduit-transport.%d{yyyy-MM-dd}.%i.log.gz"})
class TransportApplicationIT {

    static final String CLIENT_ID = "conduit-transport-app-it-" + UUID.randomUUID();
    static final String LEADER_KEY = "it:leader:" + UUID.randomUUID();
    static final String STREAM_KEY = "it:app:up:" + UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", TestBroker::redisHost);
        registry.add("spring.data.redis.port", TestBroker::redisPort);
        registry.add("conduit.transport.mqtt.host", TestBroker::emqxHost);
        registry.add("conduit.transport.mqtt.port", TestBroker::emqxPort);
        registry.add("conduit.transport.mqtt.client-id", () -> CLIENT_ID);
        registry.add("conduit.transport.leader.key", () -> LEADER_KEY);
        registry.add("conduit.bus.stream-key", () -> STREAM_KEY);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthShowsTheLeaderRoleOnceConnected() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = rest.getForObject("/actuator/health", String.class);
            assertThat(body).contains("\"role\":\"LEADER\"").contains("\"connected\":true")
                    .contains("\"instanceId\":");
        });
    }
}
