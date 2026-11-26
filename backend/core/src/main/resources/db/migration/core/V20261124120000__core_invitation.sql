-- Analyst invitations (ADR 0007, organization#6). Owned by the core: accepting one is what gives an account a tenant.
-- Only a SHA-256 hash of the secret is stored. Rows are kept after use, for the history and to make reuse fail.
create table core.invitation (
    id          uuid primary key,
    tenant_id   uuid        not null,
    email       text        not null check (email = lower(email) and char_length(email) <= 254),
    role        text        not null check (role = 'analyst'),
    token_hash  bytea       not null unique check (octet_length(token_hash) = 32),
    invited_by  text        not null,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    accepted_at timestamptz,
    accepted_by text,
    revoked_at  timestamptz,
    check ((accepted_at is null) = (accepted_by is null)),
    check (accepted_at is null or revoked_at is null)
);

-- One pending invitation per email in an organization: inviting again revokes the previous one first.
create unique index invitation_pending_email_uq on core.invitation (tenant_id, email)
    where accepted_at is null and revoked_at is null;
-- An account joins through at most one invitation (and provisioning refuses accounts that did).
create unique index invitation_accepted_by_uq on core.invitation (accepted_by) where accepted_by is not null;

grant usage on schema core to alinha_app;
revoke all on core.invitation from public, alinha_app;
grant select, insert, update on core.invitation to alinha_app;
