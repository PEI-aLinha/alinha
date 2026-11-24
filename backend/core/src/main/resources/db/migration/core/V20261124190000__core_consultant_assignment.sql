-- Consultant of each organization (FR04, organization#5). Access itself is the consultant's `tenants` attribute in
-- the identity provider (ADR 0002); this table keeps which consultant was assigned and when, for the tie-break of the
-- next assignment and for support. Read across tenants by the core only (ConsultantAssigner, JdbcClient).
create table core.consultant_assignment (
    tenant_id          uuid        primary key references core.tenant (id),
    consultant_subject text        not null check (char_length(consultant_subject) <= 255),
    assigned_at        timestamptz not null default now()
);

create index consultant_assignment_subject_idx on core.consultant_assignment (consultant_subject, assigned_at desc);

-- Runtime role: assignments are created, never changed or removed by the app.
revoke all on core.consultant_assignment from public, alinha_app;
grant select, insert on core.consultant_assignment to alinha_app;
