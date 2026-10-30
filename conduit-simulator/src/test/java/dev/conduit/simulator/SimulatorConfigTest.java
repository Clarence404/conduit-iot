package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SimulatorConfig 单元测试：环境变量覆盖本地文件、默认值、必填项、上报格式与多租户模拟的新配置。 */
class SimulatorConfigTest {

    @Test
    void environmentOverridesLocalProperties() {
        Map<String, String> local = Map.of("CONDUIT_ADMIN_PASSWORD", "from-file", "MQTT_HOST", "file-host");
        Map<String, String> env = Map.of("MQTT_HOST", "env-host");

        SimulatorConfig config = SimulatorConfig.fromEnv(SimulatorConfig.merge(local, env));

        assertThat(config.adminPassword()).isEqualTo("from-file");
        assertThat(config.mqttHost()).isEqualTo("env-host");
    }

    @Test
    void appliesDefaultsWhenOnlyPasswordGiven() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw"));

        assertThat(config.apiUrl()).hasToString("http://localhost:8080");
        assertThat(config.productName()).isEqualTo("三相电表（演示）");
        assertThat(config.deviceCount()).isEqualTo(100);
        assertThat(config.platformUsername()).isEqualTo("sysadmin");
        assertThat(config.platformPassword()).isEmpty();
        assertThat(config.multiTenant()).isFalse();
        assertThat(config.tenants().all()).isTrue();
        assertThat(config.maxDevicesPerProduct()).isEqualTo(100);
        assertThat(config.ackTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(config.maxPending()).isEqualTo(1000);
    }

    @Test
    void requiresAdminPassword() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONDUIT_ADMIN_PASSWORD");
    }

    @Test
    void payloadFormatDefaultsToStandardAndAcceptsFlat() {
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw")).payloadFormat())
                .isEqualTo(PayloadFormat.STANDARD);
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw", "SIM_PAYLOAD_FORMAT", "FLAT"))
                .payloadFormat()).isEqualTo(PayloadFormat.FLAT);
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "SIM_PAYLOAD_FORMAT", "xml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIM_PAYLOAD_FORMAT");
    }

    @Test
    void readsTheMultiTenantSettings() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of(
                "CONDUIT_ADMIN_PASSWORD", "pw",
                "SIM_PLATFORM_USERNAME", "platform-ops",
                "SIM_PLATFORM_PASSWORD", "platform-pass-1",
                "SIM_TENANTS", "east-power,south-water",
                "SIM_MAX_DEVICES_PER_PRODUCT", "20"));

        assertThat(config.platformUsername()).isEqualTo("platform-ops");
        assertThat(config.platformPassword()).isEqualTo("platform-pass-1");
        assertThat(config.multiTenant()).isTrue();
        assertThat(config.tenants().codes()).containsExactlyInAnyOrder("east-power", "south-water");
        assertThat(config.maxDevicesPerProduct()).isEqualTo(20);
    }

    /** 默认排除压测脚本建的「压测产品」；可配成逗号分隔的名称或 productKey，配成空则不排除。 */
    @Test
    void excludedProductsDefaultToTheLoadTestProduct() {
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw")).excludedProducts())
                .containsExactly("压测产品");
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "SIM_EXCLUDE_PRODUCTS", " 压测产品 , pk-abc ,, ")).excludedProducts())
                .containsExactlyInAnyOrder("压测产品", "pk-abc");
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "SIM_EXCLUDE_PRODUCTS", "")).excludedProducts()).isEmpty();
    }

    @Test
    void aBlankPlatformPasswordMeansDemoOnly() {
        assertThat(SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw", "SIM_PLATFORM_PASSWORD", "  "))
                .multiTenant()).isFalse();
    }

    @Test
    void rejectsAMaxDevicesPerProductBelowOne() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw",
                "SIM_MAX_DEVICES_PER_PRODUCT", "0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIM_MAX_DEVICES_PER_PRODUCT");
    }

    @Test
    void readsAndValidatesReliableUplinkSettings() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of(
                "CONDUIT_ADMIN_PASSWORD", "pw", "SIM_ACK_TIMEOUT_MS", "250", "SIM_MAX_PENDING", "7"));

        assertThat(config.ackTimeout()).isEqualTo(java.time.Duration.ofMillis(250));
        assertThat(config.maxPending()).isEqualTo(7);
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of(
                "CONDUIT_ADMIN_PASSWORD", "pw", "SIM_ACK_TIMEOUT_MS", "0")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SIM_ACK_TIMEOUT_MS");
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of(
                "CONDUIT_ADMIN_PASSWORD", "pw", "SIM_MAX_PENDING", "0")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SIM_MAX_PENDING");
    }
}
