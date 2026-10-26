-- 跨租户关联约束：子行的 tenant_id 必须与父行一致，由数据库强制。
-- 约束名已于 2026-10-22 在演示 PostgreSQL 的 pg_constraint 中核实。
alter table product
    add constraint uk_product_tenant_id unique (tenant_id, id);

alter table device
    drop constraint device_product_id_fkey;
alter table device
    add constraint fk_device_product
        foreign key (tenant_id, product_id) references product (tenant_id, id);

alter table product_property
    drop constraint product_property_product_id_fkey;
alter table product_property
    add constraint fk_product_property_product
        foreign key (tenant_id, product_id) references product (tenant_id, id) on delete cascade;
