package dev.conduit.server.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 邀请管理与公开的一次性接受接口。 */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {
    private final InvitationService service;
    public InvitationController(InvitationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal AuthPrincipal actor,
                                      @RequestBody InvitationService.Create request) {
        return Map.of("id", service.create(actor, request));
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal actor) {
        return service.list(actor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        service.revoke(actor, id);
    }

    @GetMapping("/accept")
    public Map<String, Object> preview(@RequestParam String token) { return service.preview(token); }

    public record Accept(String token, String password) { }
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@RequestBody Accept request) { service.accept(request.token(), request.password()); }
}
