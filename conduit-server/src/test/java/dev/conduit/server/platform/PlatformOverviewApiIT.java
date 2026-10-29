package dev.conduit.server.platform;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 平台概况集成测试：各计数与数据库一致（共享库，按当时的库内数量比较）；设备最多的租户最多 5 个、按设备数降序；带本进程吞吐；租户用户 403。 */
class PlatformOverviewApiIT extends IntegrationTestBase {

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void countsMatchTheDatabase() throws Exception {
        long tenant = createTenant("po-");
        createDevice(tenant, "ENABLED");
        createUser(tenant, Roles.TENANT_VIEWER, false);

        String body = mvc.perform(get("/api/v1/platform/overview").header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat((Integer) JsonPath.read(body, "$.tenantCount")).isEqualTo(count("select count(*) from tenant"));
        assertThat((Integer) JsonPath.read(body, "$.activeTenantCount"))
                .isEqualTo(count("select count(*) from tenant where status = 'ACTIVE'"));
        assertThat((Integer) JsonPath.read(body, "$.disabledTenantCount"))
                .isEqualTo(count("select count(*) from tenant where status = 'DISABLED'"));
        assertThat((Integer) JsonPath.read(body, "$.userCount"))
                .isEqualTo(count("select count(*) from app_user where tenant_id is not null"));
        assertThat((Integer) JsonPath.read(body, "$.productCount")).isEqualTo(count("select count(*) from product"));
        assertThat((Integer) JsonPath.read(body, "$.deviceCount")).isEqualTo(count("select count(*) from device"));
        assertThat((Object) JsonPath.read(body, "$.onlineCount")).isNotNull();

        // 吞吐是本进程口径：累计值一定有；速率在采样不足时为 null，这里只断言字段存在、类型正确
        assertThat((Integer) JsonPath.read(body, "$.throughput.ingestTotal")).isNotNegative();
        assertThat((Integer) JsonPath.read(body, "$.throughput.deadTotal")).isNotNegative();
        assertThat((Integer) JsonPath.read(body, "$.throughput.windowSeconds")).isPositive();

        List<Integer> top = JsonPath.read(body, "$.topTenants[*].deviceCount");
        assertThat(top).hasSizeBetween(1, 5);
        List<Integer> sorted = new ArrayList<>(top);
        sorted.sort(Comparator.reverseOrder());
        assertThat(top).isEqualTo(sorted);
    }

    @Test
    void tenantUsersAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/platform/overview").header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isForbidden());
    }
}
