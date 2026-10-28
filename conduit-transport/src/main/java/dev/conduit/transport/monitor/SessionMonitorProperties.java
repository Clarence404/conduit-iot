package dev.conduit.transport.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 */
@ConfigurationProperties(prefix = "conduit.transport.monitor")
public record SessionMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:18083") String baseUrl,
        String apiKey,
        String apiSecret,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("6") int stallSamples,
        @DefaultValue("256MB") DataSize heapLimit) {

    /** stallSamples 为 1 时只要投递受阻就立刻判停滞，即使投递总数其实在增长也会误报，故要求至少 2。 */
    public SessionMonitorProperties {
        if (stallSamples < 2) {
            throw new IllegalArgumentException(
                    "conduit.transport.monitor.stall-samples 至少为 2（为 1 时投递受阻即误判停滞），当前为 " + stallSamples);
        }
    }
}
