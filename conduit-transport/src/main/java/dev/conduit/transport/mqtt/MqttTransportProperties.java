package dev.conduit.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * transport 连接 EMQX 的 MQTT 配置（前缀 conduit.transport.mqtt）。
 *
 * @param clientId             所有 transport 实例共用的 clientId：EMQX 磁盘持久会话按 clientId 保存离线积压，新主实例接管同一会话
 * @param username             为空表示匿名连接（仅测试环境）
 * @param sessionExpirySeconds 会话保留秒数，与 EMQX 持久会话消息保留期（1 天）一致
 * @param ownClientPrefix      平台内部客户端的 clientId 前缀，这些客户端的上下线事件不转发
 * @param batchSize            一次提交到总线的最大条数；1 等价于逐条提交（回退开关）
 * @param batchDelay           攒批的最长等待：不足 batchSize 时最多等这么久也要提交，
 *                             直接体现为 PUBACK 的最坏额外延迟
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
        @DefaultValue("5ms") Duration batchDelay) {

    public MqttTransportProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("conduit.transport.mqtt.batch-size must be >= 1");
        }
    }
}
