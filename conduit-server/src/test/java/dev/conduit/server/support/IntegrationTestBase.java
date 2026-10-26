package dev.conduit.server.support;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 集成测试基类：启动完整 Spring 上下文（Testcontainers 提供 PG / Redis / InfluxDB），提供 MockMvc 与直接写库造设备、定义属性等公共辅助。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected SecretHasher secretHasher;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /** 测试用户统一的初始密码（符合密码规则）。 */
    protected static final String TEST_PASSWORD = "Test-pass-123";

    /** 测试配置（application-test.yml）中的平台管理员。 */
    protected static final String PLATFORM_ADMIN = "sysadmin";
    protected static final String PLATFORM_ADMIN_PASSWORD = "sysadmin-pass-123";

    /** 测试用设备凭据：租户、产品 Key、设备 Key 与明文密钥。 */
    protected record TestDevice(long tenantId, String productKey, String deviceKey, String secret) {
    }

    /** 直接写库创建「产品 + 设备」，用于不经过 API 的测试。 */
    protected TestDevice createDevice(long tenantId, String status) {
        String productKey = unique("pk");
        String deviceKey = unique("dk");
        String secret = "secret-" + deviceKey;
        String salt = secretHasher.newSalt();
        Long productId = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'test product') returning id",
                Long.class, tenantId, productKey);
        Long siteId = jdbc.queryForObject("select id from site where tenant_id = ? and is_default", Long.class,
                tenantId);
        jdbc.update("insert into device (tenant_id, product_id, site_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, ?, ?, 'test device', ?, ?, ?)",
                tenantId, productId, siteId, deviceKey, secretHasher.hash(secret, salt), salt, status);
        return new TestDevice(tenantId, productKey, deviceKey, secret);
    }

    protected void setCodec(String productKey, String codec) {
        jdbc.update("update product set codec = ? where product_key = ?", codec, productKey);
    }

    /** 直接写库定义属性（产品所在租户）。物模型缓存只在流水线首次处理该产品后才有条目，先定义再上报即可。 */
    protected void insertProperty(String productKey, String identifier, String name, String dataType, String unit,
                                  Double min, Double max, Integer decimals, String enumItemsJson) {
        jdbc.update("insert into product_property (tenant_id, product_id, identifier, name, data_type, unit, "
                        + "min_value, max_value, decimals, enum_items) "
                        + "select tenant_id, id, ?, ?, ?, cast(? as varchar), cast(? as double precision), "
                        + "cast(? as double precision), cast(? as smallint), cast(? as jsonb) "
                        + "from product where product_key = ?",
                identifier, name, dataType, unit, min, max, decimals, enumItemsJson, productKey);
    }

    protected void defineProperty(String productKey, String identifier, String dataType, Double min, Double max) {
        insertProperty(productKey, identifier, identifier, dataType, null, min, max, null, null);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    protected long createTenant(String codePrefix) {
        String code = unique(codePrefix);
        long tenantId = jdbc.queryForObject("insert into tenant (code, name) values (?, ?) returning id",
                Long.class, code, code);
        Long projectId = jdbc.queryForObject("""
                insert into project (tenant_id, code, name, is_default)
                values (?, 'default', '默认项目', true) returning id
                """, Long.class, tenantId);
        jdbc.update("""
                insert into site (tenant_id, project_id, code, name, is_default)
                values (?, ?, 'default', '默认站点', true)
                """, tenantId, projectId);
        return tenantId;
    }

    /**
     * 直接写库在指定租户下建用户，用户名随机、密码为 TEST_PASSWORD。
     *
     * @return 用户名
     */
    protected String createUser(long tenantId, String role, boolean mustChangePassword) {
        String username = unique("u");
        jdbc.update("insert into app_user (tenant_id, username, password_hash, role, must_change_password) "
                        + "values (?, ?, ?, ?, ?)",
                tenantId, username, passwordEncoder.encode(TEST_PASSWORD), role, mustChangePassword);
        return username;
    }

    protected String loginAsAdmin() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** 用户名密码登录，返回完整结果（状态码、响应体与 refresh Cookie 由调用方断言）。 */
    protected MvcResult loginResult(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
    }

    /** 登录并返回 access token；登录不成功时断言失败。 */
    protected String login(String username, String password) throws Exception {
        MvcResult result = loginResult(username, password);
        assertThat(result.getResponse().getStatus()).as("login %s", username).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** 以测试配置的平台管理员登录。 */
    protected String loginAsPlatformAdmin() throws Exception {
        return login(PLATFORM_ADMIN, PLATFORM_ADMIN_PASSWORD);
    }

    /** 按用户名查用户 id。 */
    protected long userId(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", Long.class, username);
    }

    /** 按租户 id 查租户编码。 */
    protected String tenantCode(long tenantId) {
        return jdbc.queryForObject("select code from tenant where id = ?", String.class, tenantId);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected long defaultTenantId() {
        return jdbc.queryForObject("select id from tenant where code = 'default'", Long.class);
    }
}
