package dev.conduit.transport.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * transport 集成测试共用的容器：Redis 7.4.2 与使用内存持久会话的 EMQX 5.10.5（磁盘持久会话已关闭；单节点 singleton、$SYS 上下线事件、
 * ACL 全放行、匿名连接）。每个 JVM 只启动一次（持有类惰性初始化），只用 Redis 的测试不会等待 EMQX。
 * 各测试自行使用不同的 stream 键、锁键、clientId 与产品 Key，互不干扰。
 */
public final class TestBroker {

    private TestBroker() {
    }

    /** Redis 容器与连接（首次访问时启动）。 */
    private static final class RedisHolder {
        static final GenericContainer<?> CONTAINER = start(
                new GenericContainer<>(DockerImageName.parse("redis:7.4.2")).withExposedPorts(6379));
        static final StringRedisTemplate TEMPLATE = template();

        private static StringRedisTemplate template() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(CONTAINER.getHost(), CONTAINER.getMappedPort(6379)));
            factory.afterPropertiesSet();
            return new StringRedisTemplate(factory);
        }
    }

    /** EMQX 容器（首次访问时启动），会话相关配置（内存持久会话、max_inflight=1024、mqueue 容量 100000）与 deploy/dev/docker-compose.yml 及 e2e 一致。 */
    private static final class EmqxHolder {
        static final GenericContainer<?> CONTAINER = start(
                new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.10.5"))
                        .withExposedPorts(1883)
                        .withCopyToContainer(MountableFile.forClasspathResource("emqx/acl.conf"),
                                "/opt/emqx/etc/acl.conf")
                        .withEnv("EMQX_SYS_TOPICS__SYS_EVENT_MESSAGES__CLIENT_CONNECTED", "true")
                        .withEnv("EMQX_SYS_TOPICS__SYS_EVENT_MESSAGES__CLIENT_DISCONNECTED", "true")
                        .withEnv("EMQX_DURABLE_SESSIONS__ENABLE", "false")
                        .withEnv("EMQX_MQTT__MAX_INFLIGHT", "1024")
                        .withEnv("EMQX_MQTT__MAX_MQUEUE_LEN", "100000")
                        .withEnv("EMQX_CLUSTER__DISCOVERY_STRATEGY", "singleton")
                        .withEnv("EMQX_NODE__NAME", "emqx@127.0.0.1")
                        .waitingFor(Wait.forLogMessage(".*is running now.*", 1))
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static GenericContainer<?> start(GenericContainer<?> container) {
        container.start();
        return container;
    }

    /** 连接共享 Redis 的模板。 */
    public static StringRedisTemplate redis() {
        return RedisHolder.TEMPLATE;
    }

    public static String redisHost() {
        return RedisHolder.CONTAINER.getHost();
    }

    public static int redisPort() {
        return RedisHolder.CONTAINER.getMappedPort(6379);
    }

    public static String emqxHost() {
        return EmqxHolder.CONTAINER.getHost();
    }

    public static int emqxPort() {
        return EmqxHolder.CONTAINER.getMappedPort(1883);
    }
}
