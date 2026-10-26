-- V0.3-B1 组织骨架：客户 -> 项目 -> 站点 -> 资产；设备必须归属站点，可选归属资产。

create table customer (
    id         bigserial primary key,
    tenant_id  bigint       not null references tenant (id) on delete cascade,
    code       varchar(32)  not null,
    name       varchar(128) not null,
    status     varchar(16)  not null default 'ACTIVE',
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint uk_customer_tenant_code unique (tenant_id, code),
    constraint uk_customer_tenant_id unique (tenant_id, id),
    constraint ck_customer_code check (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    constraint ck_customer_status check (status in ('ACTIVE', 'DISABLED'))
);

create table project (
    id          bigserial primary key,
    tenant_id   bigint       not null references tenant (id) on delete cascade,
    customer_id bigint,
    code        varchar(32)  not null,
    name        varchar(128) not null,
    status      varchar(16)  not null default 'ACTIVE',
    is_default  boolean      not null default false,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uk_project_tenant_code unique (tenant_id, code),
    constraint uk_project_tenant_id unique (tenant_id, id),
    constraint ck_project_code check (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    constraint ck_project_status check (status in ('ACTIVE', 'DISABLED')),
    constraint fk_project_customer foreign key (tenant_id, customer_id)
        references customer (tenant_id, id)
);
create unique index uk_project_tenant_default on project (tenant_id) where is_default;

create table site (
    id         bigserial primary key,
    tenant_id  bigint       not null references tenant (id) on delete cascade,
    project_id bigint       not null,
    code       varchar(32)  not null,
    name       varchar(128) not null,
    timezone   varchar(64)  not null default 'Asia/Shanghai',
    status     varchar(16)  not null default 'ACTIVE',
    is_default boolean      not null default false,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint uk_site_tenant_code unique (tenant_id, code),
    constraint uk_site_tenant_id unique (tenant_id, id),
    constraint uk_site_tenant_project_id unique (tenant_id, project_id, id),
    constraint ck_site_code check (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    constraint ck_site_status check (status in ('ACTIVE', 'DISABLED')),
    constraint fk_site_project foreign key (tenant_id, project_id)
        references project (tenant_id, id)
);
create unique index uk_site_tenant_default on site (tenant_id) where is_default;

create table asset (
    id         bigserial primary key,
    tenant_id  bigint       not null references tenant (id) on delete cascade,
    site_id    bigint       not null,
    parent_id  bigint,
    code       varchar(32)  not null,
    name       varchar(128) not null,
    type       varchar(64),
    status     varchar(16)  not null default 'ACTIVE',
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint uk_asset_tenant_code unique (tenant_id, code),
    constraint uk_asset_tenant_id unique (tenant_id, id),
    constraint uk_asset_tenant_site_id unique (tenant_id, site_id, id),
    constraint ck_asset_code check (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    constraint ck_asset_status check (status in ('ACTIVE', 'DISABLED')),
    constraint fk_asset_site foreign key (tenant_id, site_id)
        references site (tenant_id, id),
    constraint fk_asset_parent foreign key (tenant_id, site_id, parent_id)
        references asset (tenant_id, site_id, id)
);

insert into project (tenant_id, code, name, is_default)
select id, 'default', '默认项目', true from tenant;

insert into site (tenant_id, project_id, code, name, is_default)
select p.tenant_id, p.id, 'default', '默认站点', true
from project p where p.is_default;

alter table device add column site_id bigint;
alter table device add column asset_id bigint;

update device d
set site_id = s.id
from site s
where s.tenant_id = d.tenant_id and s.is_default;

alter table device alter column site_id set not null;
alter table device
    add constraint fk_device_site foreign key (tenant_id, site_id)
        references site (tenant_id, id),
    add constraint fk_device_asset foreign key (tenant_id, site_id, asset_id)
        references asset (tenant_id, site_id, id);

create index idx_customer_tenant_status on customer (tenant_id, status);
create index idx_project_tenant_customer on project (tenant_id, customer_id);
create index idx_site_tenant_project on site (tenant_id, project_id);
create index idx_asset_tenant_site_parent on asset (tenant_id, site_id, parent_id);
create index idx_device_tenant_site on device (tenant_id, site_id);
create index idx_device_tenant_asset on device (tenant_id, asset_id) where asset_id is not null;
