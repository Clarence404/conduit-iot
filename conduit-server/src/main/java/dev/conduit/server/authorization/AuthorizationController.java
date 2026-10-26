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

    /** 创建租户自定义角色；内置角色不可覆盖。 */
    @PostMapping("/roles")
    public void createRole(@AuthenticationPrincipal AuthPrincipal actor,
                           @RequestBody AuthorizationService.RoleInput input) {
        service.createRole(actor,input);
    }

    /** 更新自定义角色名称、作用域、状态和权限。 */
    @PutMapping("/roles/{key}")
    public void updateRole(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String key,
                           @RequestBody AuthorizationService.RoleInput input) {
        service.updateRole(actor,key,input);
    }

    /** 只删除没有任何绑定的自定义角色。 */
    @DeleteMapping("/roles/{key}")
    public void deleteRole(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String key) {
        service.deleteRole(actor,key);
    }

    /** 查看当前项目已经显式授权的成员。 */
    @GetMapping("/projects/{code}/members")
    public List<Map<String,Object>> projectMembers(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String code) {
        return service.projectMembers(actor,code);
    }

    /** 列出当前项目管理员可委派的角色。 */
    @GetMapping("/projects/{code}/members/roles")
    public List<Map<String,Object>> projectRoles(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String code) {
        return service.projectRoles(actor,code);
    }

    /** 读取目标账号仅在当前项目的绑定。 */
    @GetMapping("/projects/{code}/members/{username}/bindings")
    public List<Map<String,Object>> projectMemberBindings(@AuthenticationPrincipal AuthPrincipal actor,
                                                          @PathVariable String code,@PathVariable String username) {
        return service.projectMemberBindings(actor,code,username);
    }

    /** 只替换目标成员在当前项目的授权，不触及其他范围。 */
    @PutMapping("/projects/{code}/members/{username}/bindings")
    public void replaceProjectMember(@AuthenticationPrincipal AuthPrincipal actor,@PathVariable String code,
                                     @PathVariable String username,@RequestBody List<AuthorizationService.Binding> bindings) {
        service.replaceProjectMember(actor,code,username,bindings);
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
