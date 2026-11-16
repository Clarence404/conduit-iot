package dev.conduit.server.mqttauth;

import dev.conduit.server.mqttauth.MqttAuthService.Decision;
import dev.conduit.server.web.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** EMQX HTTP 鉴权回调。只返回 200 + allow/deny；内部 token 不对返回 401（EMQX 视为失败并拒绝连接）。 */
@RestController
public class MqttAuthController {

    /** EMQX 回调请求体：连接的 clientId、用户名与密码。 */
    public record MqttAuthRequest(String clientid, String username, String password) {
    }

    private final MqttAuthService service;
    private final MqttAuthProperties props;

    public MqttAuthController(MqttAuthService service, MqttAuthProperties props) {
        this.service = service;
        this.props = props;
    }

    /**
     * 处理一次设备/transport 的连接鉴权。先以常量时间比较内部 token（缺失或不匹配抛 401，且未配置 token 时一律拒绝），
     * 再交给 {@link MqttAuthService} 判定；允许时返回 is_superuser，供 EMQX 对 transport 放开 ACL。
     * 请求头 {@code X-Conduit-Listener: internal} 表示连接来自只供 transport 使用的内部监听器。
     */
    @PostMapping("/internal/mqtt/auth")
    public Map<String, Object> authenticate(
            @RequestHeader(value = "X-Conduit-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Conduit-Listener", required = false) String listener,
            @RequestBody MqttAuthRequest request) {
        if (token == null || props.token() == null || props.token().isBlank()
                || !MqttAuthService.constantEquals(token, props.token())) {
            throw ApiException.unauthorized("invalid internal token");
        }
        // 内部监听器（tcp:internal）的鉴权链在回调中带 X-Conduit-Listener: internal，只许 transport 连接
        boolean internal = "internal".equals(listener);
        Decision decision = service.authenticate(request.clientid(), request.username(), request.password(), internal);
        if (!decision.allow()) {
            return Map.of("result", "deny");
        }
        return Map.of("result", "allow", "is_superuser", decision.superuser());
    }
}
