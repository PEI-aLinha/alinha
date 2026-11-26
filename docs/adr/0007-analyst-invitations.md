# ADR 0007 — Analyst invitations

- **Status:** proposed
- **Date:** 2026-11-24
- **Issues:** PEI-aLinha/alinha-plugin-organization#6 (invite analysts and manage members)
- **Refs:** FR05, US12, NFR03, NFR04, NFR07, ADR 0002, ADR 0004, `docs/tenancy.md`

## Context
FR05/US12: the Designated Officer invites analysts by email, and they get restricted accounts in the organization.
The tenant comes only from the token (ADR 0002), and the `tenant_id` attribute behind it can only be set through the
identity provider's admin API, which only the core uses (ADR 0004). The issue's first idea was to create the
analyst's Keycloak account when the invitation is accepted. But the invitee has no session then, and NFR07 allows no
unauthenticated writes: that would be the public registration endpoint ADR 0004 already rejected.

## Decision
- **The invitee creates their own account first.** The invitation link opens the onboarding area. Without a session,
  the shell sends the person to the normal Keycloak registration (verified email, TOTP on first login), so every
  account is still created by Keycloak, with MFA. Signed in as a `registrant` with no tenant, the person accepts.
- **The core owns invitations** (`core.tenant.TenantMembership`, table `core.invitation`). Accepting one gives an
  account a tenant, which is the core's job. If the plugin owned the invitations, the core would need a method
  meaning "put this account in this tenant" and would have to trust that the caller checked an invitation first.
  The organization plugin owns the endpoints, their permissions, the pages and the email.
- **The secret is 256 random bits in the link; only its SHA-256 hash is stored.** Single use, valid 7 days
  (`alinha.invitations.ttl`), bound to the invited email: the accepting token's verified `email` must match.
  Inviting the same email again revokes the pending invitation. Rows are kept after use and revocation.
  The plugin puts the secret in the link's fragment (`/onboarding/invitation#<token>`), so it never reaches a server
  log or a `Referer` header, and sends it in the body of the accept request.
- **Accepting works like provisioning** (ADR 0004): the same per-account advisory lock, so an account cannot register
  an organization and accept an invitation at once. It runs as the invitation's tenant in one transaction with the
  `tenant.invitation-accepted` audit entry, then sets `tenant_id`, maps `analyst` and removes `registrant` in Keycloak
  as the last step. A Keycloak failure rolls everything back, and a known rollback undoes the Keycloak change.
  This is a second internal tenant switch inside a request (`runAsInvitedTenant`), next to `runAsNewTenant`. It is
  safe because the tenant comes from an invitation the caller holds the secret of, and the block only marks that
  invitation as used.
- **One organization per account, checked in the database**, not only in the token, which stays stale (still
  `registrant`) until it is refreshed: provisioning refuses an account that accepted an invitation, and accepting
  refuses an account that owns a tenant or already accepted one.
- **Members come from the identity provider**: the accounts whose `tenant_id` is the tenant, with their realm role.
  Removing an analyst undoes the join (the account gets `registrant` back, as a new account of its own), marks the
  invitation it joined with as left (`left_at`), so the one-organization check no longer counts it, and ends the
  account's sessions, so a refresh token cannot keep the old tenant. The Designated Officer cannot be removed this
  way. Consultants are not members: they are assigned (organization#5).
- The identity calls are in `IdentityMembership`, internal and implemented by `KeycloakIdentityDirectory` next to
  `IdentityDirectory`. A separate interface, so the `IdentityDirectory` fakes in plugin tests keep compiling.

## Consequences
- (+) No unauthenticated endpoint and no second account system. MFA comes from the realm for analysts too.
- (+) A leaked database dump does not leak usable invitation links.
- (−) Two steps for the invitee: create the account, then accept. The shell keeps the return path across the login
  (`frontend/shell/src/session/login.ts`), so the link usually survives the registration. When the email
  verification opens another tab or browser, that path is lost, and opening the link again works.
- (−) Same distributed write as ADR 0004: a crash between the Keycloak call and the commit can leave an account in a
  tenant without an accepted invitation. It is logged as ERROR.
- (−) Until the access token expires (minutes), a removed analyst's current token still names the tenant. Ending
  the sessions stops refreshes, not tokens already issued.
- (−) The member list is one admin API call plus one per member. Fine for an SME's team (at most 200 members are
  read).

## Alternatives considered
- **Create the account on acceptance, from an unauthenticated endpoint:** the issue's first idea. Violates NFR07
  and needs password and MFA set-up outside Keycloak.
- **Keycloak's own "execute actions" email (admin-created user plus required actions):** the account would exist
  before the person agrees, with a vendor-specific email and link, and with `registrant` from the default role.
- **Invitations in the organization plugin's schema:** the core would have to trust any plugin asking to put an
  account in a tenant.
