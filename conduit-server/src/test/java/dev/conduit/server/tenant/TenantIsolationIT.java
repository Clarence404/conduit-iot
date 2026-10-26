package dev.conduit.server.tenant;

import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.tenant.system.DeviceIdentityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIsolationIT extends IntegrationTestBase {

    @Autowired
    ProductMapper products;

    @Autowired
    DeviceIdentityMapper identities;

    @Test
    void insertFillsTenantIdAndQueriesAreScopedToCurrentTenant() {
        long t1 = createTenant("t1-");
        long t2 = createTenant("t2-");
        String key = unique("pk");

        TenantContext.runAs(t1, () -> {
            Product p = new Product();
            p.setProductKey(key);
            p.setName("meter");
            products.insert(p);
        });

        assertThat(jdbc.queryForObject("select tenant_id from product where product_key = ?", Long.class, key))
                .isEqualTo(t1);
        assertThat(TenantContext.callAs(t1, () -> products.selectList(null)))
                .extracting(Product::getProductKey).containsExactly(key);
        assertThat(TenantContext.callAs(t2, () -> products.selectList(null))).isEmpty();
    }

    @Test
    void queryWithoutBoundTenantFails() {
        assertThatThrownBy(() -> products.selectList(null))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryMapperStillFailsInsideSystemMode() {
        assertThatThrownBy(() -> TenantContext.callAsSystem("platform-stats", () -> products.selectList(null)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityLookupWorksAcrossTenantsWithoutContext() {
        long tenant = createTenant("t3-");
        String pk = unique("pk");
        String dk = unique("dk");
        Long productId = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'p') returning id",
                Long.class, tenant, pk);
        jdbc.update("insert into device (tenant_id, product_id, device_key, name, secret_hash, secret_salt) "
                + "values (?, ?, ?, 'd', 'h', 's')", tenant, productId, dk);

        DeviceIdentity identity = TenantContext.callAsSystem("pipeline-identity",
                () -> identities.findIdentity(dk));

        assertThat(identity).isEqualTo(new DeviceIdentity(dk, tenant, "ENABLED", "h", "s", pk, "ACTIVE"));
        assertThat(identity.enabled()).isTrue();
        assertThat(TenantContext.callAsSystem("pipeline-identity",
                () -> identities.findIdentity("missing"))).isNull();
    }
}
