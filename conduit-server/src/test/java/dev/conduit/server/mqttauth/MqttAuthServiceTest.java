package dev.conduit.server.mqttauth;

import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.security.SecretHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 鉴权结论按原因计数：EMQX 只知道放行/拒绝，原因只有 server 知道。 */
class MqttAuthServiceTest {

    private static final String SALT = "00";
    private final SecretHasher hasher = new SecretHasher();
    private final DeviceIdentityLookup lookup = mock(DeviceIdentityLookup.class);
    private final SimpleMeterRegistry reg = new SimpleMeterRegistry();
    private final MqttAuthService service = new MqttAuthService(
            new MqttAuthProperties("tok", "transport", "tp"), lookup, hasher, reg);

    private void device(String key, String status, String tenantStatus) {
        when(lookup.findForMqtt(key)).thenReturn(Optional.of(new DeviceIdentity(key, 1L, status,
                hasher.hash("s3cret", SALT), SALT, "pk", tenantStatus)));
    }

    private double count(String result, String reason) {
        return reg.get("conduit.mqtt.auth").tag("result", result).tag("reason", reason).counter().count();
    }

    @Test
    void 每种结论都计入对应原因() {
        device("d1", "ENABLED", "ACTIVE");
        device("d2", "DISABLED", "ACTIVE");
        device("d3", "ENABLED", "DISABLED");

        service.authenticate("d1", "d1", "s3cret");
        service.authenticate("conduit-transport", "transport", "tp");
        service.authenticate("d1", "d1", "wrong");
        service.authenticate("d1", "d1", "wrong");
        service.authenticate("d2", "d2", "s3cret");
        service.authenticate("d3", "d3", "s3cret");
        service.authenticate("nope", "nope", "x");
        service.authenticate("other", "d1", "s3cret");
        service.authenticate("x-transport", "transport", "tp");
        service.authenticate(null, "d1", "s3cret");

        assertThat(count("allow", "device")).isEqualTo(1);
        assertThat(count("allow", "transport")).isEqualTo(1);
        assertThat(count("deny", "wrong_secret")).isEqualTo(2);
        assertThat(count("deny", "device_disabled")).isEqualTo(1);
        assertThat(count("deny", "tenant_disabled")).isEqualTo(1);
        assertThat(count("deny", "unknown_device")).isEqualTo(1);
        assertThat(count("deny", "client_id_mismatch")).isEqualTo(1);
        assertThat(count("deny", "transport_rejected")).isEqualTo(1);
        assertThat(count("deny", "missing_credentials")).isEqualTo(1);
    }

    @Test
    void 启动即注册全部原因的序列使从未发生的原因显示为0而不是无数据() {
        assertThat(reg.find("conduit.mqtt.auth").counters()).hasSize(9);
        assertThat(count("deny", "wrong_secret")).isZero();
    }
}
