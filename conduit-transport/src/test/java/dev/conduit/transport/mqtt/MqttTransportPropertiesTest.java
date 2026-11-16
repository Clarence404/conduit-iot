package dev.conduit.transport.mqtt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * transport 连接 EMQX 的默认值：连只供 transport 使用的内部监听器（1884，zone transport 的信箱与队列放宽），
 * 默认 8 条连接分摊共享订阅，总缓冲约 8 × 10 万条，扛住断网补传洪峰
 * （见 docs/superpowers/specs/2026-11-16-conduit-transport-internal-listener-design.md）。
 */
class MqttTransportPropertiesTest {

    private static MqttTransportProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("conduit.transport.mqtt", Bindable.of(MqttTransportProperties.class));
    }

    @Test
    void 默认连内部监听器且开8条连接() {
        MqttTransportProperties p = bind(Map.of());

        assertThat(p.port()).isEqualTo(1884);
        assertThat(p.connections()).isEqualTo(8);
        assertThat(p.shared()).isTrue();
    }

    @Test
    void 仍可显式改回设备监听器与单连接() {
        MqttTransportProperties p = bind(Map.of("conduit.transport.mqtt.port", "1883",
                "conduit.transport.mqtt.connections", "1"));

        assertThat(p.port()).isEqualTo(1883);
        assertThat(p.shared()).isFalse();
    }
}
