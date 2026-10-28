package dev.conduit.server.metrics;

import dev.conduit.server.mqttauth.MqttAuthProperties;
import dev.conduit.server.mqttauth.MqttAuthService;
import dev.conduit.server.web.ApiException;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 Prometheus 抓取的内部指标端点。
 * <p>
 * 不用 {@code /actuator/prometheus}：该路径按既有约定仅平台管理员可访问，凭据是 15 分钟过期且依赖数据库会话的 JWT，
 * Prometheus 拿不到稳定凭据；又不能为抓取放开 {@code /actuator/**}。因此沿用 {@code /internal/**} 的既有模式——
 * 安全配置对该前缀放行，由控制器自行校验内部令牌（与 EMQX 鉴权回调相同的令牌与比较方式）。
 * 令牌值与收到的令牌都不写日志。
 */
@RestController
public class InternalMetricsController {

    private final PrometheusMeterRegistry registry;
    private final MqttAuthProperties props;

    public InternalMetricsController(PrometheusMeterRegistry registry, MqttAuthProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /**
     * 返回 Prometheus 文本格式的全部指标。校验与 {@code MqttAuthController} 一致：令牌缺失或常量时间比较不匹配抛 401，
     * 服务端未配置令牌时一律拒绝。
     */
    @GetMapping(value = "/internal/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String scrape(@RequestHeader(value = "X-Conduit-Internal-Token", required = false) String token) {
        if (token == null || props.token() == null || props.token().isBlank()
                || !MqttAuthService.constantEquals(token, props.token())) {
            throw ApiException.unauthorized("invalid internal token");
        }
        return registry.scrape();
    }
}
