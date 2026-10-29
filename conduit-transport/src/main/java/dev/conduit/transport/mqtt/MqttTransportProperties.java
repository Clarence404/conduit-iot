package dev.conduit.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * transport 连接 EMQX 的 MQTT 配置（前缀 conduit.transport.mqtt）。
 *
 * @param clientId             所有 transport 实例共用的 clientId：EMQX 内存持久会话按 clientId 为离线订阅端排队（EMQX 重启不保留），新主实例接管同一会话
 * @param username             为空表示匿名连接（仅测试环境）
 * @param sessionExpirySeconds 会话保留秒数，与 EMQX 持久会话消息保留期（1 天）一致
 * @param ownClientPrefix      平台内部客户端的 clientId 前缀，这些客户端的上下线事件不转发
 * @param batchSize            一次提交到总线的最大条数；1 等价于逐条提交（回退开关）。
 *                             不得大于 EMQX 的 mqtt.max_inflight，且窗口应为批的若干倍。待提交条目数就是
 *                             EMQX 侧未确认的在途数，被该窗口硬限：批大于窗口则永远攒不满、每批白等满 batchDelay；
 *                             批等于窗口则是停等协议（发 N 条、等全部确认、再发 N 条），流水线深度为 1；
 *                             窗口远大于批时提交当前批的同时下一批已在途，深度为 窗口/批（实测 1024/64 优于 32/32）
 * @param batchDelay           攒批的最长等待：不足 batchSize 时最多等这么久也要提交，
 *                             直接体现为 PUBACK 的最坏额外延迟
 * @param connections          主实例连接 EMQX 的条数，默认 4，必须 ≥ 1。
 *                             为什么要多条：EMQX 把全平台上行都投递给同一个订阅连接进程，而一个 Erlang 进程
 *                             单核封顶（2026-10-29 recon 实测 9,000 条/秒时该进程已顶在单调度器上限，
 *                             信箱持续增长直至 mailbox_overflow 被强杀）。多于 1 条时改用共享订阅
 *                             $share/{sharedGroup}/...，由 EMQX 把投递分散到这些连接进程上。
 *                             第 0 条沿用 clientId（接管旧会话、补发其中排队的消息后退订旧的非共享过滤器），
 *                             第 i 条为 clientId-i；上下线事件只由第 0 条订阅，避免重复 K 次。
 *                             为 1 时与改动前完全一致（旧 clientId、普通订阅、不退订），即回退开关。
 *                             见 docs/superpowers/specs/2026-10-29-conduit-transport-shared-subscription-design.md
 * @param sharedGroup          共享订阅组名（connections > 1 时生效）
 */
@ConfigurationProperties(prefix = "conduit.transport.mqtt")
public record MqttTransportProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("1883") int port,
        @DefaultValue("conduit-transport") String clientId,
        String username,
        String password,
        @DefaultValue("86400") long sessionExpirySeconds,
        @DefaultValue("conduit-") String ownClientPrefix,
        @DefaultValue("64") int batchSize,
        @DefaultValue("5ms") Duration batchDelay,
        @DefaultValue("4") int connections,
        @DefaultValue("conduit-transport") String sharedGroup) {

    public MqttTransportProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("conduit.transport.mqtt.batch-size must be >= 1");
        }
        if (connections < 1) {
            // 0 条连接意味着主实例什么都不订阅、消息全部堆在 EMQX，必须在启动时就失败
            throw new IllegalArgumentException("conduit.transport.mqtt.connections must be >= 1");
        }
    }

    /** 是否使用共享订阅：多于一条连接时才需要由 EMQX 在连接之间分摊。 */
    public boolean shared() {
        return connections > 1;
    }

    /**
     * 第 index 条连接的 clientId：第 0 条沿用 clientId 以接管旧会话，其余加序号后缀。
     * 必须保留 clientId 的前缀——transport 超级用户鉴权要求以 conduit- 开头。
     */
    public String clientIdOf(int index) {
        return index == 0 ? clientId : clientId + "-" + index;
    }
}
