package dev.conduit.server.organization;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6 数据库约束：默认组织、跨租户拒绝以及设备资产必须同站点。 */
class OrganizationModelIT extends IntegrationTestBase {

    @Test
    void everyMigratedTenantHasOneDefaultProjectAndSite() {
        int tenants = jdbc.queryForObject("select count(*) from tenant", Integer.class);
        assertThat(jdbc.queryForObject("select count(*) from project where is_default", Integer.class)).isEqualTo(tenants);
        assertThat(jdbc.queryForObject("select count(*) from site where is_default", Integer.class)).isEqualTo(tenants);
        assertThat(jdbc.queryForObject("select count(*) from device where site_id is null", Integer.class)).isZero();
    }

    @Test
    void rejectsCrossTenantAndCrossSiteRelationships() {
        long a = createTenant("org-a-");
        long b = createTenant("org-b-");
        Long customerB = jdbc.queryForObject(
                "insert into customer (tenant_id, code, name) values (?, ?, 'B') returning id",
                Long.class, b, unique("cb"));

        assertThatThrownBy(() -> jdbc.update(
                "insert into project (tenant_id, customer_id, code, name) values (?, ?, ?, 'cross')",
                a, customerB, unique("pa"))).isInstanceOf(DataIntegrityViolationException.class);

        Long siteA = jdbc.queryForObject("select id from site where tenant_id = ? and is_default", Long.class, a);
        Long siteB = jdbc.queryForObject("select id from site where tenant_id = ? and is_default", Long.class, b);
        Long assetB = jdbc.queryForObject(
                "insert into asset (tenant_id, site_id, code, name) values (?, ?, ?, 'B asset') returning id",
                Long.class, b, siteB, unique("ab"));
        Long productA = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'A product') returning id",
                Long.class, a, unique("pk"));

        assertThatThrownBy(() -> jdbc.update("""
                insert into device (tenant_id, product_id, site_id, asset_id, device_key, name, secret_hash, secret_salt)
                values (?, ?, ?, ?, ?, 'cross', 'h', 's')
                """, a, productA, siteA, assetB, unique("dk")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
