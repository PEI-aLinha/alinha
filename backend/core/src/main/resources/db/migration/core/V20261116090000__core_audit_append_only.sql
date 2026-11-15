-- RNF14: AI provenance. All four or none.
alter table audit.audit_event
    add column ai_model          text,
    add column ai_prompt_version text,
    add column ai_sources        jsonb,
    add column ai_generated_at   timestamptz,
    add constraint audit_event_ai_provenance_complete check (
        (ai_model is null and ai_prompt_version is null and ai_sources is null and ai_generated_at is null)
        or (ai_model is not null and ai_prompt_version is not null and ai_sources is not null and ai_generated_at is not null)
    );

-- History view: per tenant, newest first; per entity.
create index audit_event_tenant_time_idx   on audit.audit_event (tenant_id, occurred_at desc);
create index audit_event_tenant_entity_idx on audit.audit_event (tenant_id, entity_type, entity_id);

-- RNF04: append-only for EVERY role, owner and superuser included (grants alone do not bind them).
-- The RNF15 anonymisation job will add an exception for its own role.
create function audit.reject_change() returns trigger
    language plpgsql as $$
begin
    raise exception 'audit.audit_event is append-only (RNF04): % refused', tg_op
        using errcode = 'insufficient_privilege';
end
$$;

create trigger audit_event_no_update_delete
    before update or delete on audit.audit_event
    for each row execute function audit.reject_change();

create trigger audit_event_no_truncate
    before truncate on audit.audit_event
    for each statement execute function audit.reject_change();

-- Runtime role. Flyway runs as the schema owner; the application runs as alinha_app.
-- Dev creates it with LOGIN in deploy/postgres/init; this fallback keeps tests and fresh databases working.
do $$
begin
    if not exists (select from pg_roles where rolname = 'alinha_app') then
        create role alinha_app nologin;
    end if;
end
$$;

grant usage on schema audit to alinha_app;  -- schema core is not created yet; default privileges cover it
revoke all on audit.audit_event from public, alinha_app;
grant select, insert on audit.audit_event to alinha_app;

-- Plugin schemas created later by the owner are usable by the app without per-plugin grants.
-- NOTE: new tables in schema audit would also get update/delete: give them their own revoke + trigger.
alter default privileges grant usage on schemas to alinha_app;
alter default privileges grant select, insert, update, delete on tables to alinha_app;
alter default privileges grant usage, select on sequences to alinha_app;

-- Plugin schemas that already exist.
do $$
declare
    s text;
begin
    for s in
        select nspname from pg_namespace
        where nspowner = (select oid from pg_roles where rolname = current_user)
          and nspname not in ('audit', 'core', 'public', 'information_schema')
          and nspname not like 'pg\_%'
    loop
        execute format('grant usage on schema %I to alinha_app', s);
        execute format('grant select, insert, update, delete on all tables in schema %I to alinha_app', s);
        execute format('grant usage, select on all sequences in schema %I to alinha_app', s);
    end loop;
end
$$;
