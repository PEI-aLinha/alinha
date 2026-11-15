-- Spring Modulith event publication registry (schema v2, from spring-modulith-events-jdbc 2.0.0
-- org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql).
-- Created here, not at startup, because the runtime role alinha_app cannot CREATE in public.
-- IF NOT EXISTS: databases created before this migration already have it from schema-initialization.
create table if not exists public.event_publication
(
    id                     uuid                     not null,
    listener_id            text                     not null,
    event_type             text                     not null,
    serialized_event       text                     not null,
    publication_date       timestamp with time zone not null,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    primary key (id)
);
create index if not exists event_publication_serialized_event_hash_idx on public.event_publication using hash (serialized_event);
create index if not exists event_publication_by_completion_date_idx on public.event_publication (completion_date);

-- Default privileges do not cover a table that already existed.
grant select, insert, update, delete on public.event_publication to alinha_app;
