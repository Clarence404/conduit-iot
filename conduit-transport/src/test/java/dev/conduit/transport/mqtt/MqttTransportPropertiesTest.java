package dev.conduit.transport.mqtt;

import dev.conduit.transport.allocation.AllocationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * transport 连接 EMQX 的默认值：连只供 transport 使用的内部监听器（1884，zone transport 的信箱与队列放宽），
 * 初始身份池 64 个身份，使用共享订阅（见 docs/superpowers/specs/2026-11-17-conduit-transport-multi-active-design.md）。
 *
 * <p>connections 是初始身份池大小 K 的旧名，保留为兼容别名：经 application.yml，旧键与新旧环境变量都同时决定
 * mqtt.connections（是否共享订阅）与 allocation.initial-pool-size（初始 K），两者不会分叉。
 */
class MqttTransportPropertiesTest {

    private static MqttTransportProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("conduit.transport.mqtt", Bindable.of(MqttTransportProperties.class));
    }

    /** 以真实的 application.yml 为底、overrides 为更高优先级的配置源（相当于命令行或环境变量）绑定。 */
    private static Binder applicationYml(Map<String, Object> overrides) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        for (PropertySource<?> s : new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"))) {
            sources.addLast(s);
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    private static int connections(Binder binder) {
        return binder.bindOrCreate("conduit.transport.mqtt", Bindable.of(MqttTransportProperties.class))
                .connections();
    }

    private static int initialPoolSize(Binder binder) {
        return binder.bindOrCreate("conduit.transport.allocation", Bindable.of(AllocationProperties.class))
                .initialPoolSize();
    }

    @Test
    void 默认连内部监听器且初始身份池为64并使用共享订阅() {
        MqttTransportProperties p = bind(Map.of());

        assertThat(p.port()).isEqualTo(1884);
        assertThat(p.connections()).isEqualTo(64);
        assertThat(p.shared()).isTrue();
    }

    @Test
    void 仍可显式改回设备监听器与单连接() {
        MqttTransportProperties p = bind(Map.of("conduit.transport.mqtt.port", "1883",
                "conduit.transport.mqtt.connections", "1"));

        assertThat(p.port()).isEqualTo(1883);
        assertThat(p.shared()).isFalse();
    }

    @Test
    void application_yml默认初始K为64且两处一致() throws IOException {
        Binder b = applicationYml(Map.of());

        assertThat(connections(b)).isEqualTo(64);
        assertThat(initialPoolSize(b)).isEqualTo(64);
    }

    @Test
    void 旧键connections仍能设置初始K() throws IOException {
        Binder b = applicationYml(Map.of("conduit.transport.mqtt.connections", "4"));

        assertThat(connections(b)).isEqualTo(4);
        assertThat(initialPoolSize(b)).isEqualTo(4);
    }

    @Test
    void 旧环境变量TRANSPORT_MQTT_CONNECTIONS仍能设置初始K() throws IOException {
        Binder b = applicationYml(Map.of("TRANSPORT_MQTT_CONNECTIONS", "1"));

        assertThat(connections(b)).isEqualTo(1);
        assertThat(initialPoolSize(b)).isEqualTo(1);
    }

    @Test
    void 新环境变量TRANSPORT_POOL_SIZE同时设置两处且优先于旧变量() throws IOException {
        Binder b = applicationYml(Map.of("TRANSPORT_POOL_SIZE", "128", "TRANSPORT_MQTT_CONNECTIONS", "8"));

        assertThat(connections(b)).isEqualTo(128);
        assertThat(initialPoolSize(b)).isEqualTo(128);
    }
}
