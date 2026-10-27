-- B3: sessions are server-side so revocation takes effect on the next request.
alter table app_user add column authz_version bigint not null default 0;

create table user_session (
    id uuid primary key,
    user_id bigint not null references app_user(id) on delete cascade,
    tenant_id bigint references tenant(id) on delete cascade,
    refresh_hash char(64) not null,
    created_at timestamptz not null default now(),
    last_used_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    client_summary varchar(256)
);
create index idx_user_session_user_active on user_session(user_id, expires_at) where revoked_at is null;
