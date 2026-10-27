package dev.conduit.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户查看并吊销自己的登录会话。 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class UserSessionController {
    private final UserSessionRegistry registry;

    public UserSessionController(UserSessionRegistry registry) { this.registry = registry; }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal actor) {
        return registry.list(actor.userId()).stream().map(item -> {
            item.put("current", item.get("id").equals(actor.sessionId()));
            return item;
        }).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        registry.revoke(id, actor.userId());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(@AuthenticationPrincipal AuthPrincipal actor) {
        registry.revokeAll(actor.userId());
    }
}
