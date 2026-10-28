package dev.conduit.server.metrics;

import dev.conduit.server.mqttauth.MqttAuthProperties;
import dev.conduit.server.mqttauth.MqttAuthService;
import dev.conduit.server.web.ApiException;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>指标注册表用 {@link ObjectProvider} 取而不是构造器强依赖：Spring Boot 在测试中默认关闭指标导出，
 * 于是注册表不会被创建；若在此硬依赖，缺失时整个应用上下文都起不来（实测会让 268 个集成测试全部 Error）。
 * 一个诊断端点不应当有能力阻止应用启动。注册表缺失时启动即 WARN，本端点返回 503，
 * Prometheus 侧会体现为该目标的 {@code up} 为 0，不会静默。
 */
@RestController
public class InternalMetricsController {

    private static final Logger log = LoggerFactory.getLogger(InternalMetricsController.class);

    private final ObjectProvider<PrometheusMeterRegistry> registry;
    private final MqttAuthProperties props;

    public InternalMetricsController(ObjectProvider<PrometheusMeterRegistry> registry, MqttAuthProperties props) {
        this.registry = registry;
        this.props = props;
        if (registry.getIfAvailable() == null) {
            log.warn("No PrometheusMeterRegistry available; /internal/metrics will return 503. "
                    + "This is expected in tests, but in a deployment it means metrics export is disabled.");
        }
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
        PrometheusMeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            // 指标导出未启用：明确报错而不是返回空指标，否则 Prometheus 会把空响应当成"一切正常"
            throw ApiException.unavailable("metrics registry is not available");
        }
        return meters.scrape();
    }
}
