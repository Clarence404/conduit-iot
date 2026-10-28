package dev.conduit.transport;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusConfiguration;
import dev.conduit.transport.adapter.ProtocolAdapter;
import dev.conduit.transport.leader.LeaderElection;
import dev.conduit.transport.leader.LeaderElection.Role;
import dev.conduit.transport.leader.LeaderHealthIndicator;
import dev.conduit.transport.leader.LeaderLock;
import dev.conduit.transport.leader.LeaderProperties;
import dev.conduit.transport.leader.RedisLeaderLock;
import dev.conduit.transport.mqtt.MqttProtocolAdapter;
import dev.conduit.transport.monitor.EmqxSessionClient;
import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorProperties;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * conduit-transport 进程入口：接入层。订阅 EMQX 上的设备消息，组装为 RawMessage 写入 Redis Streams 总线，
 * 不做任何业务解析（解码与校验由 conduit-server 流水线完成）。多个实例单活多备：经 Redis 锁选主，只有主实例连接 EMQX。
 */
@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties({MqttTransportProperties.class, LeaderProperties.class,
        SessionMonitorProperties.class})
public class TransportApplication {

    private static final Logger log = LoggerFactory.getLogger(TransportApplication.class);

    /** 启动 transport 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }

    /** MQTT 协议适配器；何时连接由 {@link LeaderElection} 决定。 */
    @Bean
    MqttProtocolAdapter mqttProtocolAdapter(MqttTransportProperties props, MessageBus bus) {
        return new MqttProtocolAdapter(props, bus, Clock.systemUTC());
    }

    /** Redis 选主锁，实例 id 为 主机名-进程号-随机串。 */
    @Bean
    LeaderLock leaderLock(StringRedisTemplate redis, LeaderProperties props) {
        return new RedisLeaderLock(redis, props.key(), props.ttl(), RedisLeaderLock.newInstanceId());
    }

    /** 选主协调器（SmartLifecycle，容器启动后开始抢锁，关闭时释放锁）。 */
    @Bean
    LeaderElection leaderElection(LeaderLock lock, List<ProtocolAdapter> adapters, LeaderProperties props) {
        return new LeaderElection(lock, adapters, props);
    }

    /** 会话监控；关闭时不注册，健康检查退回只看选主与连接状态。 */
    @Bean
    @ConditionalOnProperty(prefix = "conduit.transport.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    SessionMonitor sessionMonitor(SessionMonitorProperties props, MqttTransportProperties mqtt) {
        return new SessionMonitor(new EmqxSessionClient(props, mqtt.clientId()),
                props.stallSamples(), props.heapLimit().toBytes(), mqtt.batchSize());
    }

    /**
     * 按配置的间隔采样 EMQX 会话。只有主实例持有那条 MQTT 连接，
     * 备实例读到的不是自己的会话，不参与判定。
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "conduit.transport.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    ScheduledExecutorService sessionMonitorScheduler(SessionMonitorProperties props,
                                                     SessionMonitor monitor,
                                                     LeaderElection election) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("session-monitor").daemon().factory());
        long millis = props.interval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> pollOnce(election, monitor), millis, millis, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    /**
     * 单次轮询：主实例采样，非主实例重置监控状态。整体捕获 Throwable——scheduleWithFixedDelay
     * 的任务一旦抛出就会被永久取消且无日志，监控不能因一次意外而静默死亡。
     */
    static void pollOnce(LeaderElection election, SessionMonitor monitor) {
        try {
            if (election.role() == Role.LEADER) {
                monitor.sample();
            } else {
                monitor.reset();
            }
        } catch (Throwable t) {
            log.error("session monitor poll crashed; will retry at the next interval", t);
        }
    }

    /** 健康检查组件 leader：显示角色、实例 id、连接状态与会话监控结论（监控关闭时为 null）。 */
    @Bean
    LeaderHealthIndicator leaderHealthIndicator(LeaderElection election, ObjectProvider<SessionMonitor> monitor) {
        return new LeaderHealthIndicator(election, monitor.getIfAvailable());
    }
}
