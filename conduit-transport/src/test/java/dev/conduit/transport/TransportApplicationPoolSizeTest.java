package dev.conduit.transport;

import dev.conduit.transport.allocation.AllocationProperties;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动校验：mqtt.connections 决定是否共享订阅，allocation.initial-pool-size 决定初始 K，两者必须相等，
 * 否则（例如 K = 1 却用了共享订阅，或 K > 1 却用普通订阅、每个身份各收一份全量）启动即失败。
 */
class TransportApplicationPoolSizeTest {

    private static MqttTransportProperties mqtt(int connections) {
        return new Binder(new MapConfigurationPropertySource(
                Map.of("conduit.transport.mqtt.connections", Integer.toString(connections))))
                .bindOrCreate("conduit.transport.mqtt", Bindable.of(MqttTransportProperties.class));
    }

    private static AllocationProperties allocation(int initialPoolSize) {
        return new Binder(new MapConfigurationPropertySource(
                Map.of("conduit.transport.allocation.initial-pool-size", Integer.toString(initialPoolSize))))
                .bindOrCreate("conduit.transport.allocation", Bindable.of(AllocationProperties.class));
    }

    @Test
    void equalSizesPass() {
        assertThatCode(() -> TransportApplication.requireConsistentPoolSize(mqtt(64), allocation(64)))
                .doesNotThrowAnyException();
        assertThatCode(() -> TransportApplication.requireConsistentPoolSize(mqtt(1), allocation(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void mismatchFailsWithAClearMessage() {
        assertThatThrownBy(() -> TransportApplication.requireConsistentPoolSize(mqtt(8), allocation(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conduit.transport.mqtt.connections (8)")
                .hasMessageContaining("conduit.transport.allocation.initial-pool-size (64)")
                .hasMessageContaining("TRANSPORT_POOL_SIZE");
    }
}
