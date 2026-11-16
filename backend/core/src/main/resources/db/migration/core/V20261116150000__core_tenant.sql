-- Tenants created by self-registration (ADR 0004). One per account: owner_subject is the Keycloak `sub`
-- of the person who registered the organization, so a repeated or concurrent registration fails here.
create schema if not exists core;

create table core.tenant (
    id            uuid primary key,
    owner_subject text        not null unique,
    created_at    timestamptz not null default now()
);

-- Runtime role: create and read tenants only. Rows are never changed or removed by the app.
grant usage on schema core to alinha_app;
revoke all on core.tenant from public, alinha_app;
grant select, insert on core.tenant to alinha_app;
