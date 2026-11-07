-- Core: schemas transversais. Cada plugin cria o seu próprio schema.
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

-- Append-only (RNF05): a aplicação só pode inserir.
-- Em produção: REVOKE UPDATE, DELETE ON audit.audit_event FROM <utilizador da app>;
