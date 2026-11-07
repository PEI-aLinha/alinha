-- Core: cross-cutting schemas. Each plugin creates its own schema.
create schema if not exists audit;

create table if not exists audit.audit_event (
    id           uuid primary key,
    tenant_id    uuid        not null,
    actor        text        not null,
    action       text        not null,
    entity_type  text        not null,
    entity_id    text        not null,
    details      jsonb       not null default '{}'::jsonb,
    occurred_at  timestamptz not null default now()
);

-- Append-only (RNF04): the application user may only insert.
--   REVOKE UPDATE, DELETE ON audit.audit_event FROM <application user>;
-- Anonymising personal data after 1 year (RNF15) runs under its own,
-- separate role, never the application user.
