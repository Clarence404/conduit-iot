package dev.conduit.server.metrics;

import dev.conduit.server.mqttauth.MqttAuthProperties;
import dev.conduit.server.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/** /internal/metrics 的内部令牌校验与输出格式（不依赖 Spring 上下文与 Docker，直接装配控制器）。 */
class InternalMetricsControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        var props = new MqttAuthProperties("test-internal-token", "conduit-transport", "pw");
        mvc = MockMvcBuilders.standaloneSetup(new InternalMetricsController(provider(registry), props))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 最小 ObjectProvider：只实现控制器实际会用到的 getIfAvailable。value 为 null 表示注册表不存在。 */
    private static ObjectProvider<PrometheusMeterRegistry> provider(PrometheusMeterRegistry value) {
        return new ObjectProvider<>() {
            @Override
            public PrometheusMeterRegistry getIfAvailable() {
                return value;
            }

            @Override
            public PrometheusMeterRegistry getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PrometheusMeterRegistry getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PrometheusMeterRegistry getIfUnique() {
                return value;
            }
        };
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mvc.perform(get("/internal/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongToken() throws Exception {
        mvc.perform(get("/internal/metrics").header("X-Conduit-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPrometheusTextWithCorrectToken() throws Exception {
        mvc.perform(get("/internal/metrics").header("X-Conduit-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void returnsServiceUnavailableWhenTheRegistryIsMissing() throws Exception {
        // Spring Boot 在测试中默认关闭指标导出，注册表可能不存在；此时必须明确报 503，
        // 不能返回空指标——Prometheus 会把 200 + 空响应当成"一切正常"
        var props = new MqttAuthProperties("test-internal-token", "conduit-transport", "pw");
        MockMvc noRegistry = MockMvcBuilders
                .standaloneSetup(new InternalMetricsController(provider(null), props))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        noRegistry.perform(get("/internal/metrics").header("X-Conduit-Internal-Token", "test-internal-token"))
                .andExpect(status().isServiceUnavailable());
    }
}
