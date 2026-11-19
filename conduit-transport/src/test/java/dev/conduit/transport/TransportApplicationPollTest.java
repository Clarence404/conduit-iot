package dev.conduit.transport;

import dev.conduit.transport.allocation.IdentityAllocator;
import dev.conduit.transport.monitor.SessionMonitor;
import dev.conduit.transport.monitor.SessionMonitorGroup;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单次轮询：只采样本实例持有的身份（身份序号经 clientIdOf 换成 clientId），不再持有的身份被重置；
 * 任何异常（含 Error）都不得逸出（否则调度任务会被永久取消）。
 */
class TransportApplicationPollTest {

    private static final MqttTransportProperties MQTT = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("conduit.transport.mqtt", Bindable.of(MqttTransportProperties.class));

    /** 每个 clientId 一个 mock 监控，按需创建并留存，便于逐个校验。 */
    private final Map<String, SessionMonitor> monitors = new ConcurrentHashMap<>();
    private final SessionMonitorGroup group = new SessionMonitorGroup(
            id -> monitors.computeIfAbsent(id, k -> mock(SessionMonitor.class)), null);

    private static IdentityAllocator holding(Integer... identities) {
        IdentityAllocator allocator = mock(IdentityAllocator.class);
        when(allocator.held()).thenReturn(new TreeSet<>(Set.of(identities)));
        return allocator;
    }

    @Test
    void samplesOnlyHeldIdentitiesAndResetsReleasedOnes() {
        TransportApplication.pollOnce(holding(0, 2), MQTT, group);

        assertThat(monitors).containsOnlyKeys("conduit-transport", "conduit-transport-2");
        verify(monitors.get("conduit-transport")).sample();
        verify(monitors.get("conduit-transport-2")).sample();

        TransportApplication.pollOnce(holding(2), MQTT, group);

        verify(monitors.get("conduit-transport")).reset();
        verify(monitors.get("conduit-transport-2"), never()).reset();
        assertThat(monitors).hasSize(2);
    }

    @Test
    void holdingNothingSamplesNothing() {
        TransportApplication.pollOnce(holding(), MQTT, group);

        assertThat(monitors).isEmpty();
        assertThat(group.verdict().healthy()).isTrue();
    }

    @Test
    void swallowsAnyThrowable() {
        IdentityAllocator allocator = holding(0);
        TransportApplication.pollOnce(allocator, MQTT, group);
        SessionMonitor monitor = monitors.get("conduit-transport");

        doThrow(new NullPointerException("boom")).when(allocator).held();
        assertThatCode(() -> TransportApplication.pollOnce(allocator, MQTT, group)).doesNotThrowAnyException();

        IdentityAllocator healthy = holding(0);
        doThrow(new StackOverflowError()).when(monitor).sample();
        assertThatCode(() -> TransportApplication.pollOnce(healthy, MQTT, group)).doesNotThrowAnyException();
    }
}
