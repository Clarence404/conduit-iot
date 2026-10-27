create table user_invitation (
    id uuid primary key,
    tenant_id bigint not null references tenant(id) on delete cascade,
    email varchar(254) not null,
    username varchar(32) not null,
    role varchar(32) not null check (role in ('TENANT_ADMIN','TENANT_VIEWER')),
    invited_by bigint not null references app_user(id),
    token_hash char(64) not null unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    accepted_at timestamptz,
    revoked_at timestamptz
);
create index idx_user_invitation_tenant on user_invitation(tenant_id,created_at desc);
