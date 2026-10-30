package dev.conduit.server.mqttauth;

import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.security.SecretHasher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * MQTT 连接鉴权逻辑：判定 EMQX 上的连接是 conduit-transport（超级用户）还是合法设备。
 * 位于 设备 -MQTT-> EMQX 的入口，由 {@link MqttAuthController} 回调；设备以 deviceKey 为用户名/clientId、deviceSecret 为密码。
 * 每次结论按原因计入 {@code conduit.mqtt.auth{result, reason}}：按 EMQX 协议拒绝也回 HTTP 200，
 * EMQX 自身只知放行/拒绝，原因只有这里知道。
 */
@Service
public class MqttAuthService {

    /**
     * 鉴权结论。
     *
     * @param allow     是否允许连接
     * @param superuser 是否超级用户（仅 transport，不受 topic ACL 限制）
     */
    public record Decision(boolean allow, boolean superuser) {
        public static final Decision DENY = new Decision(false, false);
    }

    /** 鉴权结论的原因，即计数器的 reason 标签；取值有限，不含 clientId 等高基数内容。 */
    enum Outcome {
        TRANSPORT(true), DEVICE(true),
        MISSING_CREDENTIALS(false), TRANSPORT_REJECTED(false), CLIENT_ID_MISMATCH(false),
        UNKNOWN_DEVICE(false), DEVICE_DISABLED(false), TENANT_DISABLED(false), WRONG_SECRET(false);

        final boolean allow;

        Outcome(boolean allow) {
            this.allow = allow;
        }
    }

    static final String INTERNAL_CLIENT_PREFIX = "conduit-";
    private static final Logger log = LoggerFactory.getLogger(MqttAuthService.class);

    private final MqttAuthProperties props;
    private final DeviceIdentityLookup lookup;
    private final SecretHasher hasher;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    /** 启动即注册全部原因的计数器，从未发生的原因在 Prometheus 里是 0 而不是没有序列。 */
    public MqttAuthService(MqttAuthProperties props, DeviceIdentityLookup lookup, SecretHasher hasher,
                           MeterRegistry meters) {
        this.props = props;
        this.lookup = lookup;
        this.hasher = hasher;
        for (Outcome o : Outcome.values()) {
            counters.put(o, Counter.builder("conduit.mqtt.auth")
                    .description("MQTT 连接鉴权结论，按原因")
                    .tag("result", o.allow ? "allow" : "deny")
                    .tag("reason", o.name().toLowerCase())
                    .register(meters));
        }
    }

    /**
     * 判定一次连接。参数任一为 null 即拒绝。
     * <ul>
     *   <li>用户名等于配置的 transport 用户名：须密码一致且 clientId 以 {@code conduit-} 开头，通过则为超级用户；</li>
     *   <li>否则按设备处理：clientId 必须等于用户名（即 deviceKey），设备须存在、启用、所属租户启用且密钥哈希匹配。</li>
     * </ul>
     * 拒绝原因只记 debug 日志与计数器，不返回给调用方。
     */
    public Decision authenticate(String clientId, String username, String password) {
        if (clientId == null || username == null || password == null) {
            return deny(Outcome.MISSING_CREDENTIALS, clientId);
        }
        if (username.equals(props.transportUsername())) {
            boolean ok = props.transportPassword() != null && !props.transportPassword().isBlank()
                    && constantEquals(password, props.transportPassword())
                    && clientId.startsWith(INTERNAL_CLIENT_PREFIX);
            if (ok) {
                log.debug("MQTT auth allowed for {} as transport superuser", clientId);
                counters.get(Outcome.TRANSPORT).increment();
                return new Decision(true, true);
            }
            return deny(Outcome.TRANSPORT_REJECTED, clientId);
        }
        if (!clientId.equals(username)) {
            return deny(Outcome.CLIENT_ID_MISMATCH, clientId);
        }
        Optional<DeviceIdentity> identity = lookup.findForMqtt(username);
        if (identity.isEmpty()) {
            return deny(Outcome.UNKNOWN_DEVICE, clientId);
        }
        DeviceIdentity d = identity.get();
        if (!d.enabled()) {
            return deny(Outcome.DEVICE_DISABLED, clientId);
        }
        if (!d.tenantActive()) {
            return deny(Outcome.TENANT_DISABLED, clientId);
        }
        if (!hasher.matches(password, d.secretSalt(), d.secretHash())) {
            return deny(Outcome.WRONG_SECRET, clientId);
        }
        log.debug("MQTT auth allowed for {} as device (productKey={})", clientId, d.productKey());
        counters.get(Outcome.DEVICE).increment();
        return new Decision(true, false);
    }

    /** 常量时间字符串比较，防止通过响应耗时推测 token / 密码。 */
    public static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Decision deny(Outcome reason, String clientId) {
        log.debug("MQTT auth denied for {}: {}", clientId, reason);
        counters.get(reason).increment();
        return Decision.DENY;
    }
}
