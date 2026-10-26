package dev.conduit.server.tenant;

import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.support.IntegrationTestBase;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 ProductMapper.lockById 的 FOR UPDATE SQL 经 MyBatis-Plus 正确追加租户条件。 */
@Import(ProductLockTenantSqlIT.CaptureConfig.class)
class ProductLockTenantSqlIT extends IntegrationTestBase {

    @Autowired
    ProductMapper products;

    @Autowired
    SqlCapture capture;

    @Test
    void forUpdateKeepsLockClauseAndContainsTenantPredicate() {
        long tenant = createTenant("lock-");
        Long productId = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'lock') returning id",
                Long.class, tenant, unique("pk"));
        capture.sql.set(null);

        assertThat(TenantContext.callAs(tenant, () -> products.lockById(productId))).isEqualTo(productId);

        String sql = capture.sql.get().replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql).contains("tenant_id = " + tenant).endsWith("for update");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfig {
        @Bean
        SqlCapture sqlCapture() {
            return new SqlCapture();
        }
    }

    /** 在 JDBC prepare 前记录 MyBatis-Plus 已改写完成的 SQL。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class SqlCapture implements Interceptor {
        private final AtomicReference<String> sql = new AtomicReference<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler statement = (StatementHandler) invocation.getTarget();
            String current = statement.getBoundSql().getSql();
            if (current.toLowerCase().contains("for update")) {
                sql.set(current);
            }
            return invocation.proceed();
        }
    }
}
