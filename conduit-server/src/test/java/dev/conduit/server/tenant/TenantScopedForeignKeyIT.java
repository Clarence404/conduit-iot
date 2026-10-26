package dev.conduit.server.tenant;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 组合外键集成测试：拒绝跨租户关联并保留属性级联删除。 */
class TenantScopedForeignKeyIT extends IntegrationTestBase {

    @Test
    void rejectsCrossTenantDeviceAndPropertyButAcceptsSameTenantRows() {
        long a = createTenant("fk-a-");
        long b = createTenant("fk-b-");
        Long productB = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'B') returning id",
                Long.class, b, unique("pk"));

        assertThatThrownBy(() -> jdbc.update("""
                insert into device (tenant_id, product_id, site_id, device_key, name, secret_hash, secret_salt)
                values (?, ?, (select id from site where tenant_id = ? and is_default), ?, 'cross', 'h', 's')
                """, a, productB, a, unique("dk"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into product_property (tenant_id, product_id, identifier, name, data_type)
                values (?, ?, 'Ua', 'cross', 'DOUBLE')
                """, a, productB)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                insert into product_property (tenant_id, product_id, identifier, name, data_type)
                values (?, ?, 'Ua', 'same', 'DOUBLE')
                """, b, productB);
        assertThat(jdbc.queryForObject("select count(*) from product_property where product_id = ?", Integer.class,
                productB)).isOne();

        jdbc.update("delete from product where id = ?", productB);
        assertThat(jdbc.queryForObject("select count(*) from product_property where product_id = ?", Integer.class,
                productB)).isZero();
    }
}
