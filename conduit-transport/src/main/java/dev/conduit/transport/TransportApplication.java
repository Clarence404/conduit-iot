package dev.conduit.transport;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusConfiguration;
import dev.conduit.transport.allocation.AllocationHealthIndicator;
import dev.conduit.transport.allocation.AllocationProperties;
import dev.conduit.transport.allocation.IdentityAllocator;
import dev.conduit.transport.allocation.IdentityLeaseStore;
import dev.conduit.transport.allocation.RedisIdentityLeaseStore;
import dev.conduit.transport.monitor.EmqxNodeClient;
import dev.conduit.transport.monitor.EmqxSessionClient;
import dev.conduit.transport.monitor.NodeMemoryMonitor;
import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import dev.conduit.transport.monitor.SessionMonitorProperties;
import dev.conduit.transport.mqtt.MqttProtocolAdapter;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import dev.conduit.transport.mqtt.SharedBatcher;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * conduit-transport 进程入口：接入层。订阅 EMQX 上的设备消息，组装为 RawMessage 写入 Redis Streams 总线，
 * 不做任何业务解析（解码与校验由 conduit-server 流水线完成）。
 *
 * <p>多个实例同时工作（身份池多活）：K 个 MQTT 身份经 Redis 租约分摊到各存活实例，每个实例只为自己持有的身份
 * 连接 EMQX，由 {@link IdentityAllocator} 按身份启停适配器（见 docs/superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md）。
 *
 * <p>停止顺序：分配器是 SmartLifecycle（默认相位 {@code Integer.MAX_VALUE}），在上下文关闭的 Lifecycle 阶段最先停止——
 * 停适配器、释放租约、离开存活集合；Redis 连接工厂（Lettuce，相位 0）在它之后才停止，而合批提交器的 close
 * 是销毁方法，要等全部 Lifecycle 停完后的单例销毁阶段才执行。因此适配器不会在提交器关闭后还投递消息，
 * 释放租约时 Redis 连接仍可用。
 */
@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties({MqttTransportProperties.class, AllocationProperties.class,
        SessionMonitorProperties.class})
public class TransportApplication {

    private static final Logger log = LoggerFactory.getLogger(TransportApplication.class);

    /** 启动 transport 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }

    /**
     * 启动校验：mqtt.connections（决定是否共享订阅）与 allocation.initial-pool-size（初始 K）必须相等。
     * application.yml 让两者取自同一配置（环境变量 TRANSPORT_POOL_SIZE，或旧的 TRANSPORT_MQTT_CONNECTIONS /
     * conduit.transport.mqtt.connections），只有单独改了其中一个才会不等：K = 1 却用了共享订阅、或 K > 1 却用普通订阅
     * （每个身份各收一份全量、消息重复 K 次），都必须在启动时失败。
     *
     * @throws IllegalStateException 两者不相等
     */
    static void requireConsistentPoolSize(MqttTransportProperties mqtt, AllocationProperties allocation) {
        if (mqtt.connections() != allocation.initialPoolSize()) {
            throw new IllegalStateException("conduit.transport.mqtt.connections (" + mqtt.connections()
                    + ") must equal conduit.transport.allocation.initial-pool-size ("
                    + allocation.initialPoolSize() + "); set both through TRANSPORT_POOL_SIZE");
        }
    }

    /**
     * 本实例唯一的合批提交器：本实例持有的所有连接收到的消息按 batch-size / batch-delay 合批写总线，
     * 写入失败从 1 秒起退避重试。关闭时放弃正在重试的批、不回 PUBACK；适配器先于它停止（见类注释的停止顺序）。
     * 意外错误次数计入 conduit.transport.batcher.failures。
     */
    @Bean(destroyMethod = "close")
    SharedBatcher sharedBatcher(MqttTransportProperties props, MessageBus bus, MeterRegistry registry) {
        return new SharedBatcher(bus, props.batchSize(), props.batchDelay(), Duration.ofSeconds(1), registry);
    }

    /** 身份租约、实例心跳与身份池大小的 Redis 存储；实例 id 为 主机名-进程号-随机串。 */
    @Bean
    IdentityLeaseStore identityLeaseStore(StringRedisTemplate redis, AllocationProperties props) {
        return new RedisIdentityLeaseStore(redis, props.leaseTtl(), RedisIdentityLeaseStore.newInstanceId());
    }

    /**
     * 身份池分配器（SmartLifecycle，容器启动后开始心跳与抢占，关闭时停适配器、释放租约）。
     * 第 i 个身份的适配器在第一次抢到时由工厂创建，所有适配器共用同一个合批提交器；适配器不是 Spring bean，
     * 启停只由分配器控制。是否共享订阅由 mqtt.connections 在启动时确定，故先校验它与初始 K 一致。
     */
    @Bean
    IdentityAllocator identityAllocator(IdentityLeaseStore store, MqttTransportProperties mqtt,
                                        AllocationProperties allocation, SharedBatcher batcher,
                                        MeterRegistry registry) {
        requireConsistentPoolSize(mqtt, allocation);
        return new IdentityAllocator(store, i -> new MqttProtocolAdapter(mqtt, batcher, Clock.systemUTC(), i),
                allocation, Clock.systemUTC(), registry);
    }

    /**
     * 会话监控：每个身份的 {@link SessionMonitor} 在第一次持有该身份时创建（K 可在运行中调大）。
     * 关闭时不注册，健康检查只看分配与连接状态。
     */
    @Bean
    @ConditionalOnProperty(prefix = "conduit.transport.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    SessionMonitorGroup sessionMonitor(SessionMonitorProperties props, MqttTransportProperties mqtt,
                                       MeterRegistry registry) {
        // EMQX 主机内存水位：会话积压耗尽内存时整个 EMQX 被系统杀掉、积压全丢（2026-11-12 压测实测），须提前告警
        NodeMemoryMonitor node = new NodeMemoryMonitor(new EmqxNodeClient(props), props.nodeMemoryWarnRatio(), registry);
        // 单身份（K = 1 回退）时不加 client 标签，指标名与引入多连接之前一致，既有面板不受影响
        return new SessionMonitorGroup(clientId -> new SessionMonitor(new EmqxSessionClient(props, clientId),
                props.stallSamples(), props.heapLimit().toBytes(), mqtt.batchSize(), registry,
                mqtt.shared() ? clientId : null, props.mqueueWarnLen()), node);
    }

    /** 按配置的间隔采样本实例持有的身份的 EMQX 会话。 */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "conduit.transport.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
    ScheduledExecutorService sessionMonitorScheduler(SessionMonitorProperties props, MqttTransportProperties mqtt,
                                                     SessionMonitorGroup monitor, IdentityAllocator allocator) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("session-monitor").daemon().factory());
        long millis = props.interval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> pollOnce(allocator, mqtt, monitor), millis, millis,
                TimeUnit.MILLISECONDS);
        return scheduler;
    }

    /**
     * 单次轮询：把本实例持有的身份序号经 {@link MqttTransportProperties#clientIdOf} 换成 clientId，只采样这些会话；
     * 不再持有的身份由监控组重置（其会话已不归本实例）。整体捕获 Throwable——scheduleWithFixedDelay
     * 的任务一旦抛出就会被永久取消且无日志，监控不能因一次意外而静默死亡。
     */
    static void pollOnce(IdentityAllocator allocator, MqttTransportProperties mqtt, SessionMonitorGroup monitor) {
        try {
            Set<String> clientIds = new LinkedHashSet<>();
            for (int i : allocator.held()) {
                clientIds.add(mqtt.clientIdOf(i));
            }
            monitor.sample(clientIds);
        } catch (Throwable t) {
            log.error("session monitor poll crashed; will retry at the next interval", t);
        }
    }

    /** 健康检查组件 allocation：实例 id、持有身份数、存活实例数、K、连接状态与会话监控结论（监控关闭时为 null）。 */
    @Bean
    AllocationHealthIndicator allocationHealthIndicator(IdentityAllocator allocator,
                                                        ObjectProvider<SessionMonitorGroup> monitor) {
        return new AllocationHealthIndicator(allocator, monitor.getIfAvailable());
    }
}
