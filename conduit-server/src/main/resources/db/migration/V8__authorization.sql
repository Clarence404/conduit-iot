-- 扩展授权模型，保留旧 app_user.role/JWT role；authz_managed=false 的新建用户兼容旧角色。
alter table app_user add column authz_managed boolean not null default false;
alter table app_user add constraint uk_app_user_tenant_id unique (tenant_id, id);

create table role_definition (
    id bigserial primary key,
    tenant_id bigint not null references tenant(id) on delete cascade,
    role_key varchar(64) not null,
    name varchar(128) not null,
    scope_mask varchar(32) not null,
    built_in boolean not null default false,
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    unique (tenant_id, role_key), unique (tenant_id, id),
    check (scope_mask in ('TENANT','PROJECT','SITE','PROJECT,SITE','TENANT,PROJECT,SITE'))
);
create table role_permission (
    tenant_id bigint not null,
    role_id bigint not null,
    permission_code varchar(64) not null,
    primary key (tenant_id, role_id, permission_code),
    foreign key (tenant_id,role_id) references role_definition(tenant_id,id) on delete cascade
);
create table role_binding (
    id bigserial primary key,
    tenant_id bigint not null references tenant(id) on delete cascade,
    user_id bigint not null,
    role_id bigint not null,
    scope_type varchar(16) not null,
    project_id bigint,
    site_id bigint,
    created_by bigint,
    created_at timestamptz not null default now(),
    foreign key (tenant_id,user_id) references app_user(tenant_id,id) on delete cascade,
    foreign key (tenant_id,role_id) references role_definition(tenant_id,id),
    foreign key (tenant_id,project_id) references project(tenant_id,id),
    foreign key (tenant_id,project_id,site_id) references site(tenant_id,project_id,id),
    check ((scope_type='TENANT' and project_id is null and site_id is null)
        or (scope_type='PROJECT' and project_id is not null and site_id is null)
        or (scope_type='SITE' and project_id is not null and site_id is not null))
);
create unique index uk_binding_tenant on role_binding(tenant_id,user_id,role_id) where scope_type='TENANT';
create unique index uk_binding_project on role_binding(tenant_id,user_id,role_id,project_id) where scope_type='PROJECT';
create unique index uk_binding_site on role_binding(tenant_id,user_id,role_id,site_id) where scope_type='SITE';
create index idx_binding_user on role_binding(tenant_id,user_id);
create index idx_binding_project on role_binding(tenant_id,project_id);
create index idx_binding_site on role_binding(tenant_id,site_id);

-- 内置角色权限由不可变的应用目录定义；新租户首次管理角色时幂等补齐定义行。
-- 不在迁移中引入明文账户或新登录角色。
insert into role_definition(tenant_id,role_key,name,scope_mask,built_in)
select t.id,r.k,r.n,r.s,true from tenant t cross join (values
 ('TENANT_OWNER','租户所有者','TENANT'),('TENANT_ADMIN','租户管理员','TENANT'),
 ('PROJECT_ADMIN','项目管理员','PROJECT'),('OPERATOR','运维人员','PROJECT,SITE'),
 ('VIEWER','只读成员','TENANT,PROJECT,SITE')) r(k,n,s);

-- 旧用户先保留兼容授权（authz_managed=false），进入显式授权时才取消兼容权限。
-- 最早创建的启用管理员在兼容解析中视为 OWNER，避免新库启动晚于迁移而没有所有者。
