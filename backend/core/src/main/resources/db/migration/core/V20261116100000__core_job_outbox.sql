-- Job outbox (RNF02). A job is written here in the caller's transaction and the relay publishes it to
-- Redis Streams afterwards, so a crash between commit and publish never loses it (at-least-once).
-- Read across tenants by the relay (core only, JdbcTemplate); dispatch stamps tenant_id from TenantContext.
create schema if not exists core;

create table core.job_outbox (
    id               uuid        primary key,              -- the jobId sent to the worker
    tenant_id        uuid        not null,
    type             text        not null,                 -- e.g. evidence.collect
    version          int         not null check (version >= 1),
    requested_by     text        not null,                 -- plugin id that dispatched it
    envelope         jsonb       not null,                 -- validated against contracts/jobs/<type>.v<n>
    status           text        not null default 'pending'
                     check (status in ('pending', 'published', 'completed', 'failed', 'dead')),
    attempt          int         not null default 1 check (attempt >= 1),
    max_attempts     int         not null check (max_attempts >= 1),
    publish_failures int         not null default 0 check (publish_failures >= 0),
    next_attempt_at  timestamptz not null default now(),
    last_error       text,                                 -- never secrets or payload content
    idempotency_key  text,
    result           jsonb,
    created_at       timestamptz not null default now(),
    published_at     timestamptz,
    finished_at      timestamptz,                          -- set once JobCompleted/JobFailed is emitted
    -- Keys are per plugin: another plugin reusing a key gets its own job. NULL keys are distinct, so jobs without
    -- a key never clash.
    constraint job_outbox_idempotency unique (tenant_id, type, requested_by, idempotency_key)
);

-- Relay: due pending jobs, oldest first.
create index job_outbox_pending_idx on core.job_outbox (next_attempt_at) where status = 'pending';
-- Dead jobs whose JobFailed has not been emitted yet.
create index job_outbox_dead_unnotified_idx on core.job_outbox (created_at)
    where status = 'dead' and finished_at is null;

-- Default privileges from V20261116090000 cover schema core and this table; explicit for databases
-- whose schema core already existed before those defaults.
grant usage on schema core to alinha_app;
grant select, insert, update on core.job_outbox to alinha_app;
