package dev.conduit.transport.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * transport 轮询 EMQX 会话指标的配置（前缀 conduit.transport.monitor）。
 * EMQX 5 的 REST API 不接受控制台账号密码，必须用 API key/secret（实测返回 401 BAD_API_KEY_OR_SECRET）。
 *
 * @param enabled      关闭时不轮询，健康检查退回只看选主与连接状态
 * @param baseUrl      EMQX dashboard 的地址，如 http://emqx:18083
 * @param apiKey       API key，经 api_key.bootstrap_file 下发，角色取只读 viewer
 * @param apiSecret    API secret
 * @param interval     轮询间隔
 * @param stallSamples 连续多少次「投递受阻且投递总数不变」的采样才判定停滞，至少 2（默认 6）；
 *                     N 次采样之间只有 N-1 个间隔，默认值从首次异常采样到判定 DOWN 为 150 秒
 * @param heapLimit    EMQX 的单连接堆上限，须与编排中 EMQX_CONNECTION_MAX_HEAP_SIZE 保持一致
 * @param mqueueWarnLen 单个会话队列积压达到多少条即告警（0 关闭，默认 50000）。与 EMQX 队列上限无关，
 *                      队列不限长时比例告警失效，靠它发现积压
 * @param nodeMemoryWarnRatio EMQX 主机内存已用占比达到多少即告警，(0, 1]，默认 0.8
 */
@ConfigurationProperties(prefix = "conduit.transport.monitor")
public record SessionMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:18083") String baseUrl,
        String apiKey,
        String apiSecret,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("6") int stallSamples,
        @DefaultValue("256MB") DataSize heapLimit,
        @DefaultValue("50000") long mqueueWarnLen,
        @DefaultValue("0.8") double nodeMemoryWarnRatio) {

    /** stallSamples 为 1 时只要投递受阻就立刻判停滞，即使投递总数其实在增长也会误报，故要求至少 2。 */
    // 另有一个便捷构造器，必须标明 Spring 绑定用这个规范构造器，否则启动时报 No default constructor found
    @ConstructorBinding
    public SessionMonitorProperties {
        if (enabled && (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())) {
            throw new IllegalArgumentException(
                    "会话监控已启用，必须配置 conduit.transport.monitor.api-key 与 api-secret（或将 enabled 设为 false）");
        }
        if (stallSamples < 2) {
            throw new IllegalArgumentException(
                    "conduit.transport.monitor.stall-samples 至少为 2（为 1 时投递受阻即误判停滞），当前为 " + stallSamples);
        }
        if (mqueueWarnLen < 0) {
            throw new IllegalArgumentException(
                    "conduit.transport.monitor.mqueue-warn-len 不能为负（0 表示关闭），当前为 " + mqueueWarnLen);
        }
        if (!(nodeMemoryWarnRatio > 0 && nodeMemoryWarnRatio <= 1)) {
            throw new IllegalArgumentException(
                    "conduit.transport.monitor.node-memory-warn-ratio 须在 (0, 1] 内，当前为 " + nodeMemoryWarnRatio);
        }
    }

    /** 告警两项取默认值（积压 50000 条、内存 80%）的构造，供只关心其余配置的调用方与测试使用。 */
    public SessionMonitorProperties(boolean enabled, String baseUrl, String apiKey, String apiSecret,
                                    Duration interval, int stallSamples, DataSize heapLimit) {
        this(enabled, baseUrl, apiKey, apiSecret, interval, stallSamples, heapLimit, 50_000, 0.8);
    }

    /** 打码 apiKey 与 apiSecret，避免 record 自动生成的 toString 把密钥带进日志。 */
    @Override
    public String toString() {
        return "SessionMonitorProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=******"
                + ", apiSecret=******, interval=" + interval + ", stallSamples=" + stallSamples
                + ", heapLimit=" + heapLimit + ", mqueueWarnLen=" + mqueueWarnLen
                + ", nodeMemoryWarnRatio=" + nodeMemoryWarnRatio + "]";
    }
}
