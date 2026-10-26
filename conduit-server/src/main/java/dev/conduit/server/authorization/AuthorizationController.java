package dev.conduit.server.authorization;

import dev.conduit.server.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 租户授权管理；权限解析和范围校验统一由 AuthorizationService 处理。 */
@RestController
@RequestMapping("/api/v1")
public class AuthorizationController {
    private final AuthorizationService service;
    public AuthorizationController(AuthorizationService service) { this.service=service; }
    /** 展示当前请求身份的有效授权；权限决策仍由统一过滤器与 SQL 范围执行。 */
    @GetMapping("/authorization/me")
    public Map<String,Object> me(@AuthenticationPrincipal AuthPrincipal actor) {
        return service.snapshot(actor);
    }

    /** 返回当前租户可分配的预置角色及其权限目录。 */
    @GetMapping("/roles")
    public List<Map<String,Object>> roles() {
        return service.roles();
    }

    /** 稳定权限码目录；预留权限不意味着已开放同名业务接口。 */
    @GetMapping("/roles/permissions")
    public Set<String> permissions() {
        return PermissionCatalog.ALL;
    }

    /** 读取目标账号显式绑定，目标必须属于当前租户。 */
    @GetMapping("/users/{username}/bindings")
    public List<Map<String,Object>> bindings(@PathVariable String username) {
        return service.bindings(username);
    }

    /** 全量替换授权；校验失败时事务整体回滚，不留下部分绑定。 */
    @PutMapping("/users/{username}/bindings")
    public void replace(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String username,
                        @RequestBody List<AuthorizationService.Binding> bindings) {
        service.replace(actor,username,bindings);
    }
}
