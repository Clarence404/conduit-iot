package dev.conduit.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import dev.conduit.server.authorization.ScopeDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import dev.conduit.server.tenant.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 配置：为 PostgreSQL 访问自动追加租户条件（多租户隔离）并启用分页。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfig {

    /** 不按租户过滤的表：租户表本身，以及登录时需要全局查找的用户表。 */
    static final Set<String> TENANT_FREE_TABLES = Set.of("tenant", "app_user");

    /**
     * 租户拦截器会给 TENANT_FREE_TABLES 之外的表的 SQL 自动加上 tenant_id = 当前租户。
     * 租户取自 {@link TenantContext#require()}：线程未绑定租户时执行 SQL 会抛 IllegalStateException，
     * 因此后台任务必须用 TenantContext.runAs/callAs 显式绑定。
     */
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContext.require());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TENANT_FREE_TABLES.contains(tableName);
            }
        }));
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(new ScopeDataPermissionHandler()));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
