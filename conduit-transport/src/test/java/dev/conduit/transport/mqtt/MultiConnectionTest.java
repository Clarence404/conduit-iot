package dev.conduit.transport.mqtt;

import dev.conduit.common.bus.MessageBus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 主实例多连接共享订阅的命名与订阅规则。
 *
 * <p>这些规则各自对应一个不做就会出事的约束：clientId 必须保留 conduit- 前缀（否则超级用户鉴权被拒）；
 * 第 0 条必须沿用旧 clientId（否则升级时旧会话里排队的消息无人接收）；上下线事件只能由一条连接订阅
 * （否则重复 K 次）；单连接必须与改动前完全一致（它是回退开关）。
 */
class MultiConnectionTest {

    private final MessageBus bus = mock(MessageBus.class);

    private static MqttTransportProperties props(int connections) {
        return new MqttTransportProperties("localhost", 1883, "conduit-transport", null, null, 86400,
                "conduit-", 64, Duration.ofMillis(5), connections, "conduit-transport");
    }

    private MqttProtocolAdapter adapter(int connections, int index) {
        return new MqttProtocolAdapter(props(connections), bus, Clock.systemUTC(), index);
    }

    @Test
    void 第0条沿用旧clientId以接管旧会话其余加序号() {
        MqttTransportProperties p = props(4);
        assertThat(p.clientIdOf(0)).isEqualTo("conduit-transport");
        assertThat(p.clientIdOf(1)).isEqualTo("conduit-transport-1");
        assertThat(p.clientIdOf(3)).isEqualTo("conduit-transport-3");
    }

    @Test
    void 所有clientId都保留conduit前缀否则超级用户鉴权被拒() {
        MqttTransportProperties p = props(4);
        for (int i = 0; i < 4; i++) {
            assertThat(p.clientIdOf(i)).startsWith("conduit-");
        }
    }

    @Test
    void 多连接时用共享订阅() {
        assertThat(adapter(4, 0).deviceTopicFilter()).isEqualTo("$share/conduit-transport/conduit/+/+/up/#");
        assertThat(adapter(4, 3).deviceTopicFilter()).isEqualTo("$share/conduit-transport/conduit/+/+/up/#");
    }

    @Test
    void 单连接与改动前完全一致() {
        MqttProtocolAdapter a = adapter(1, 0);
        assertThat(props(1).shared()).isFalse();
        assertThat(a.deviceTopicFilter()).isEqualTo(MqttProtocolAdapter.DEVICE_TOPIC_FILTER);
        assertThat(a.clientId()).isEqualTo("conduit-transport");
        assertThat(a.name()).isEqualTo("mqtt");
        assertThat(a.subscribesPresence()).isTrue();
    }

    @Test
    void 上下线事件只由第0条订阅避免重复K次() {
        assertThat(adapter(4, 0).subscribesPresence()).isTrue();
        assertThat(adapter(4, 1).subscribesPresence()).isFalse();
        assertThat(adapter(4, 3).subscribesPresence()).isFalse();
    }

    @Test
    void 多连接时适配器名带序号便于定位断开的是哪一条() {
        assertThat(adapter(4, 0).name()).isEqualTo("mqtt-0");
        assertThat(adapter(4, 2).name()).isEqualTo("mqtt-2");
    }

    @Test
    void 连接数为0时启动即失败而不是静默不订阅() {
        assertThatThrownBy(() -> props(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connections");
    }

    @Test
    void 序号越界时拒绝构造() {
        assertThatThrownBy(() -> adapter(4, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter(4, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
