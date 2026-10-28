package dev.conduit.server.metrics;

import dev.conduit.server.mqttauth.MqttAuthProperties;
import dev.conduit.server.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        mvc = MockMvcBuilders.standaloneSetup(new InternalMetricsController(registry, props))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
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
}
