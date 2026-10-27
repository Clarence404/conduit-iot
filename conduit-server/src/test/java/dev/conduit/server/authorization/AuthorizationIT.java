package dev.conduit.server.authorization;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import com.jayway.jsonpath.JsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** A可写/B只读/C不可见；真实SQL分页、统计与遥测入口验证。 */
@SpringBootTest(properties="conduit.authorization.mode=enforce")
class AuthorizationIT extends IntegrationTestBase {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    String admin, username, token, a,b,c;
    long pa,pb,pc,sa,sb,sc;
    @BeforeEach void setup() throws Exception {
        admin=bearer(loginAsAdmin());
        long tenant=defaultTenantId();
        username=createUser(tenant,"TENANT_VIEWER",false);
        pa=project(tenant);pb=project(tenant);pc=project(tenant);
        sa=site(tenant,pa);sb=site(tenant,pb);sc=site(tenant,pc);
        var da=createDevice(tenant,"ENABLED");var db=createDevice(tenant,"ENABLED");var dc=createDevice(tenant,"ENABLED");
        a=da.deviceKey();b=db.deviceKey();c=dc.deviceKey();
        jdbc.update("update device set site_id=? where device_key=?",sa,a);
        jdbc.update("update device set site_id=? where device_key=?",sb,b);
        jdbc.update("update device set site_id=? where device_key=?",sc,c);
        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION,admin)).andExpect(status().isOk());
        String ac=jdbc.queryForObject("select code from project where id=?",String.class,pa);
        String bc=jdbc.queryForObject("select code from project where id=?",String.class,pb);
        mvc.perform(put("/api/v1/users/{u}/bindings",username).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                [{"roleKey":"PROJECT_ADMIN","scopeType":"PROJECT","projectCode":"%s"},
                 {"roleKey":"VIEWER","scopeType":"PROJECT","projectCode":"%s"}]
                """.formatted(ac,bc))).andExpect(status().isOk());
        String response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\""+username+"\",\"password\":\""+TEST_PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token="Bearer "+JsonPath.read(response,"$.accessToken");
    }
    long project(long tenant) { return jdbc.queryForObject("insert into project(tenant_id,code,name) values(?,?,'权限项目') returning id",Long.class,tenant,unique("p")); }
    long site(long tenant,long project) { return jdbc.queryForObject("insert into site(tenant_id,project_id,code,name) values(?,?,?,'权限站点') returning id",Long.class,tenant,project,unique("s")); }
    @Test void scopeFiltersListsCountsAndAllDeviceEntrypoints() throws Exception {
        mvc.perform(get("/api/v1/devices?size=100").header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[?(@.deviceKey == '"+c+"')]").isEmpty());
        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.deadLastHour").isEmpty());
        String hiddenProduct=jdbc.queryForObject("select p.product_key from product p join device d on d.product_id=p.id where d.device_key=?",String.class,c);
        mvc.perform(get("/api/v1/products/"+hiddenProduct).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(0)).andExpect(jsonPath("$.onlineCount").value(0));
        for(String suffix:new String[]{"","/assignment","/latest","/history?properties=Ua"})
            mvc.perform(get("/api/v1/devices/"+c+suffix).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices/"+b).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("device.manage")));
        mvc.perform(get("/api/v1/devices/"+b).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("device.manage"))));
        mvc.perform(put("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"可管理\",\"status\":\"ENABLED\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/devices/"+b).header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权\",\"status\":\"ENABLED\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/devices/"+b+"/reset-secret").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isForbidden());
    }
    @Test void revokeIsImmediateAndNoLegacyViewerFallback() throws Exception {
        mvc.perform(put("/api/v1/users/{u}/bindings",username).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isUnauthorized());
        token=bearer(login(username,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isForbidden());
    }
    @Test void cannotMoveIntoUnauthorizedSiteOrRevokeLastOwner() throws Exception {
        String target=jdbc.queryForObject("select code from site where id=?",String.class,sc);
        mvc.perform(put("/api/v1/devices/"+a+"/assignment").header(HttpHeaders.AUTHORIZATION,token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"siteCode\":\""+target+"\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/users/admin/bindings").header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select site_id from device where device_key=?",Long.class,a)).isEqualTo(sa);
    }
    @Test void siteScopeUnionCannotCreateProjectOrModifySharedProduct() throws Exception {
        mvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"forbidden-project\",\"name\":\"越权\",\"status\":\"ACTIVE\"}")).andExpect(status().isForbidden());
        String ac=jdbc.queryForObject("select code from project where id=?",String.class,pa);
        String asc=jdbc.queryForObject("select code from site where id=?",String.class,sa);
        long hiddenSite=site(defaultTenantId(),pa);
        var hidden=createDevice(defaultTenantId(),"ENABLED");
        jdbc.update("update device set site_id=? where device_key=?",hiddenSite,hidden.deviceKey());
        mvc.perform(put("/api/v1/users/{u}/bindings",username).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("""
                [{"roleKey":"OPERATOR","scopeType":"SITE","projectCode":"%s","siteCode":"%s"}]
                """.formatted(ac,asc))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isUnauthorized());
        token=bearer(login(username,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices?size=100").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/devices/"+hidden.deviceKey()).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"站点运维\",\"status\":\"ENABLED\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"forbidden-project\",\"name\":\"越权\",\"status\":\"ACTIVE\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权产品\"}")).andExpect(status().isForbidden());
    }
    @Test void disabledAccountAndDisabledSiteBlockNextWrite() throws Exception {
        jdbc.update("update site set status='DISABLED' where id=?",sa);
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权\",\"status\":\"ENABLED\"}")).andExpect(status().isNotFound());
        String product=jdbc.queryForObject("select p.product_key from product p join device d on d.product_id=p.id where d.device_key=?",String.class,a);
        mvc.perform(delete("/api/v1/products/"+product).header(HttpHeaders.AUTHORIZATION,admin)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from device where device_key=?",Integer.class,a)).isEqualTo(1);
        jdbc.update("update app_user set status='DISABLED' where username=?",username);
        mvc.perform(get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isUnauthorized());
    }
    @Test void bindingsAreTenantBoundAndInvalidReplacementIsAtomic() throws Exception {
        long other=createTenant("authz");
        String otherUser=createUser(other,"TENANT_VIEWER",false);
        mvc.perform(put("/api/v1/users/{u}/bindings",otherUser).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/users/{u}/bindings",username).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[null]")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/devices?size=100").header(HttpHeaders.AUTHORIZATION,token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        long role=jdbc.queryForObject("select id from role_definition where tenant_id=? and role_key='VIEWER'",Long.class,defaultTenantId());
        assertThatThrownBy(()->jdbc.update("insert into role_binding(tenant_id,user_id,role_id,scope_type) values(?,?,?,'TENANT')",
                defaultTenantId(),userId(otherUser),role)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void everyExistingBusinessRouteHasRegisteredPermission() {
        mappings.getHandlerMethods().keySet().stream().flatMap(m->m.getPatternValues().stream())
                .filter(p->p.startsWith("/api/v1/"))
                .filter(p->!p.startsWith("/api/v1/auth/") && !p.equals("/api/v1/me") && !p.startsWith("/api/v1/me/") && !p.startsWith("/api/v1/platform/"))
                .forEach(p->{assertThat(PermissionGateFilter.permission(p,true)).as(p).isIn(PermissionCatalog.ALL);
                    assertThat(PermissionGateFilter.permission(p,false)).as(p).isIn(PermissionCatalog.ALL);});
    }
    @Test void platformImpersonationUsesFreshTenantOwnerScope() throws Exception {
        String platform=bearer(loginAsPlatformAdmin());
        String response=mvc.perform(post("/api/v1/platform/tenants/default/enter").header(HttpHeaders.AUTHORIZATION,platform))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.authorizationEnforced").value(true))
                .andReturn().getResponse().getContentAsString();
        String impersonated="Bearer "+JsonPath.read(response,"$.accessToken");
        mvc.perform(get("/api/v1/devices/"+c).header(HttpHeaders.AUTHORIZATION,impersonated)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/platform/overview").header(HttpHeaders.AUTHORIZATION,impersonated)).andExpect(status().isForbidden());
    }
    @Test void tenantAdministratorCannotGrantOrModifyOwner() throws Exception {
        String manager=createUser(defaultTenantId(),"TENANT_VIEWER",false);
        mvc.perform(put("/api/v1/users/{u}/bindings",manager).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[{\"roleKey\":\"TENANT_ADMIN\",\"scopeType\":\"TENANT\"}]"))
                .andExpect(status().isOk());
        String management=bearer(login(manager,TEST_PASSWORD));
        mvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION,management).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+unique("adminp")+"\",\"name\":\"租户管理员可新建\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/users/{u}/bindings",username).header(HttpHeaders.AUTHORIZATION,management)
                .contentType(MediaType.APPLICATION_JSON).content("[{\"roleKey\":\"TENANT_OWNER\",\"scopeType\":\"TENANT\"}]"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users/admin/reset-password").header(HttpHeaders.AUTHORIZATION,management)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Valid-reset-123\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void customRoleCanBeBoundDisabledAndDeletedOnlyAfterUnbind() throws Exception {
        String key="CUSTOM_"+unique("METER").toUpperCase().replace('-','_');
        String target=createUser(defaultTenantId(),"TENANT_VIEWER",false);
        String project=jdbc.queryForObject("select code from project where id=?",String.class,pa);
        String active="""
                {"roleKey":"%s","name":"巡检只读","scopeMask":"PROJECT","status":"ACTIVE",
                 "permissions":["device.read","telemetry.latest.read"]}
                """.formatted(key);
        mvc.perform(post("/api/v1/roles").header(HttpHeaders.AUTHORIZATION,admin).contentType(MediaType.APPLICATION_JSON)
                .content(active)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/roles").header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content(active)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/{u}/bindings",target).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[{\"roleKey\":\""+key+"\",\"scopeType\":\"PROJECT\",\"projectCode\":\""+project+"\"}]"))
                .andExpect(status().isOk());
        String member=bearer(login(target,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+b).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/roles/"+key).header(HttpHeaders.AUTHORIZATION,admin)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/roles/"+key).header(HttpHeaders.AUTHORIZATION,admin).contentType(MediaType.APPLICATION_JSON)
                .content(active.replace("\"ACTIVE\"","\"DISABLED\""))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isUnauthorized());
        member=bearer(login(target,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/users/{u}/bindings",target).header(HttpHeaders.AUTHORIZATION,admin)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/roles/"+key).header(HttpHeaders.AUTHORIZATION,admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/roles/VIEWER").header(HttpHeaders.AUTHORIZATION,admin)).andExpect(status().isConflict());
    }
    @Test void projectAdminDelegatesOnlyOwnProjectWithoutTenantEscalation() throws Exception {
        String target=createUser(defaultTenantId(),"TENANT_VIEWER",false);
        String project=jdbc.queryForObject("select code from project where id=?",String.class,pa);
        String foreign=jdbc.queryForObject("select code from project where id=?",String.class,pb);
        mvc.perform(get("/api/v1/projects/{p}/members/roles",project).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.role_key == 'VIEWER')]").isNotEmpty());
        String view="[{\"roleKey\":\"VIEWER\",\"scopeType\":\"PROJECT\",\"projectCode\":\""+project+"\"}]";
        mvc.perform(put("/api/v1/projects/{p}/members/{u}/bindings",foreign,target)
                .header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/projects/{p}/members/{u}/bindings",project,"admin")
                .header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/projects/{p}/members/{u}/bindings",project,target)
                .header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"roleKey\":\"TENANT_ADMIN\",\"scopeType\":\"TENANT\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/projects/{p}/members/{u}/bindings",project,target)
                .header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isOk());
        String member=bearer(login(target,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+b).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/{p}/members",project).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.username == '"+target+"')]").isNotEmpty());
        mvc.perform(get("/api/v1/projects/{p}/members/{u}/bindings",project,target).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].role_key").value("VIEWER"));
        mvc.perform(get("/api/v1/projects/{p}/members/{u}/bindings",foreign,target).header(HttpHeaders.AUTHORIZATION,token))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/projects/{p}/members/{u}/bindings",project,target)
                .header(HttpHeaders.AUTHORIZATION,token).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isUnauthorized());
        member=bearer(login(target,TEST_PASSWORD));
        mvc.perform(get("/api/v1/devices/"+a).header(HttpHeaders.AUTHORIZATION,member)).andExpect(status().isNotFound());
    }
}
