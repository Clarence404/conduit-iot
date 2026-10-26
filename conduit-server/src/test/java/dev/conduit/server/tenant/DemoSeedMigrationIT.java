package dev.conduit.server.tenant;

import dev.conduit.server.support.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 演示种子迁移测试：全新库得到固定资产基线，已有同名资产升级时不会重复插入。 */
class DemoSeedMigrationIT extends IntegrationTestBase {

    @Autowired
    DataSource dataSource;

    @Test
    void seedsTheCompleteDemoInventoryOnAFreshDatabase() {
        String schema = "demo_seed_" + unique("s");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            assertThat(count(schema, "tenant")).isEqualTo(4);
            assertThat(count(schema, "product")).isEqualTo(8);
            assertThat(count(schema, "device")).isEqualTo(174);
            assertThat(count(schema, "product_property")).isEqualTo(36);
            assertThat(count(schema, "customer")).isEqualTo(8);
            assertThat(count(schema, "project")).isEqualTo(12);
            assertThat(count(schema, "site")).isEqualTo(20);
            assertThat(count(schema, "asset")).isEqualTo(48);
            assertThat(jdbc.queryForObject("select count(*) from " + schema
                    + ".device where asset_id is not null", Integer.class)).isEqualTo(174);
            assertThat(jdbc.queryForObject("select count(*) from " + schema
                    + ".asset where parent_id is not null", Integer.class)).isEqualTo(32);
            assertThat(deviceCounts(schema)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "default", 110,
                    "huadong-power", 26,
                    "huanan-water", 20,
                    "xibei-energy", 18));
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".device d join " + schema
                    + ".product p on p.id=d.product_id where p.name='三相电表（演示）' "
                    + "and d.name='10号楼配电室 电表10'", Integer.class)).isOne();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void reusesExistingNamedDemoAssetsDuringUpgrade() {
        String schema = "demo_upgrade_" + unique("s");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("3").load().migrate();
            jdbc.update("insert into " + schema + ".tenant (code, name) values ('default', '默认租户')");
            jdbc.update("insert into " + schema + ".product (tenant_id, product_key, name) "
                    + "select id, 'dejtincij1l0yqa4', '三相电表（演示）' from " + schema
                    + ".tenant where code='default'");
            jdbc.update("insert into " + schema + ".device "
                    + "(tenant_id, product_id, device_key, name, secret_hash, secret_salt) "
                    + "select tenant_id, id, 'legacy-meter-key', '1号楼配电室 电表01', repeat('1',64), repeat('1',32) "
                    + "from " + schema + ".product where product_key='dejtincij1l0yqa4'");

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".product "
                    + "where name='三相电表（演示）'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".device d join " + schema
                    + ".product p on p.id=d.product_id where p.name='三相电表（演示）'", Integer.class))
                    .isEqualTo(100);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".device "
                    + "where name='1号楼配电室 电表01'", Integer.class)).isOne();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void organizationSeedPreservesManualAssignmentsAndOrdinaryTenants() {
        String schema = "org_upgrade_" + unique("s");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("6").load().migrate();
            jdbc.update("insert into " + schema + ".tenant(code,name) values ('ordinary','普通租户')");
            jdbc.update("insert into " + schema + ".site(tenant_id,project_id,code,name) "
                    + "select tenant_id,id,'manual-site','人工站点' from " + schema
                    + ".project where tenant_id=(select id from " + schema + ".tenant where code='default') and is_default");
            jdbc.update("update " + schema + ".device set site_id=(select id from " + schema
                    + ".site where code='manual-site') where device_key='demo-meter-001'");
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
            assertThat(count(schema, "customer")).isEqualTo(8);
            assertThat(count(schema, "asset")).isEqualTo(48);
            assertThat(jdbc.queryForObject("select s.code from " + schema + ".device d join " + schema
                    + ".site s on s.id=d.site_id where d.device_key='demo-meter-001'", String.class))
                    .isEqualTo("manual-site");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".customer c join " + schema
                    + ".tenant t on t.id=c.tenant_id where t.code='ordinary'", Integer.class)).isZero();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    /** 指定 schema 的整表行数。 */
    private int count(String schema, String table) {
        return jdbc.queryForObject("select count(*) from " + schema + "." + table, Integer.class);
    }

    /** 各固定租户的设备数。 */
    private Map<String, Integer> deviceCounts(String schema) {
        return jdbc.query("select t.code, count(d.id) from " + schema + ".tenant t left join " + schema
                        + ".device d on d.tenant_id=t.id group by t.code",
                rs -> {
                    Map<String, Integer> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getInt(2));
                    }
                    return result;
                });
    }
}
