# ADR 0004 — Self-registration and tenant provisioning

- **Status:** accepted
- **Date:** 2026-11-16
- **Issues:** #91 (tenant provisioning), PEI-aLinha/alinha-plugin-organization#2 (organization registration),
  #14 (BFF)
- **Refs:** FR01, US10, NFR03, NFR04, NFR07, `docs/tenancy.md`, ADR 0002

## Context
FR01/US10: a Designated Officer creates an account and registers their organization. Every request takes its
tenant from the token (ADR 0002): the `tenant_id` claim comes from a Keycloak user attribute that only an admin can
edit. A person who just created an account has no tenant, so `TenantContext` cannot resolve one and
`NoTenantWriteGuard` refuses every tenant-owned insert. `TenantContext.runAs` is refused inside HTTP requests, so a
handler cannot pick a tenant either. Nothing in the platform creates a tenant.

## Decision
- **Accounts are created in Keycloak.** The realm allows registration (email as username, verified email, TOTP on
  first login). New users get the default realm role `registrant` and no `tenant_id`. `registrant` is a known role
  for RBAC; plugins grant it only what registration needs (e.g. `organization:register`).
- **The core creates tenants.** `core.tenant.TenantProvisioning.provisionForCurrentUser { tenantId -> ... }`:
  1. Refuses callers that already have a tenant (`tenant_id` or `tenants` claim), are consultants, or lack
     `registrant` (409/403).
  2. Generates a new tenant id and inserts `core.tenant` (`owner_subject` unique: one tenant per account, so a
     repeated or concurrent request gets 409).
  3. Runs the caller's block **as the new tenant** in one transaction, records `tenant.provisioned` in the audit
     trail, and as the last step sets `tenant_id`, adds `responsible` and removes `registrant` in the identity
     provider (in Keycloak `registrant` comes from the default composite role, so that mapping is removed).
  4. Identity provider fails: the exception rolls everything back. The commit fails after the identity provider
     changed: a rollback hook restores the account (remove `tenant_id`, `responsible`; add `registrant`).
- **This is the only exception to "no tenant switch inside a request"**, and it is internal to the core. It is safe
  because the tenant is brand new, so no data of any other tenant can be reached, and it is bound to the caller's
  own account. The public `runAs` stays refused inside requests.
- **The identity provider is behind an internal, vendor-neutral `IdentityDirectory`.** The Keycloak implementation
  uses the Admin REST API with a confidential client (`alinha-provisioner`, service account with
  `realm-management` `manage-users` and `view-users`). Its secret comes from OpenBao. Plugins never call Keycloak.
  Keycloak gives the default role (and so `registrant`) to every account it creates, except imported ones, so any
  `IdentityDirectory` method that creates accounts (invitations, consultants) must remove that role.
- **Registration is limited** because each one holds a database connection while it calls the identity provider:
  at most 4 at once on the instance, one in flight per account (checked first, so a double submit is a 503), and 5 attempts per client IP per
  hour (`alinha.provisioning.max-per-client-per-hour`). Over the limits: 503 `REGISTRATION_BUSY` or 429
  `TOO_MANY_REGISTRATIONS`, both with `Retry-After` (as is 503 `IDENTITY_UNAVAILABLE`). The IP comes from `X-Forwarded-For` only through an internal proxy. The limits are in
  memory, per instance; with several instances they move to the BFF on Redis (#14).
- **The token is stale after provisioning.** The new `tenant_id` and role appear in the next token, so the BFF
  refreshes the session (or the shell re-authenticates silently) after a successful registration (#14).

## Consequences
- (+) The registration in the prototype (account → MFA → organization) maps onto Keycloak plus one API call; the
  organization plugin needs no identity code.
- (+) One tenant per account, enforced by the database, not by timing.
- (+) The same `IdentityDirectory` serves later work: analyst invitations (organization#6), consultant assignment
  (organization#5), actor names in the audit view (#75).
- (−) A distributed write (database + Keycloak) without a shared transaction: a crash between the Keycloak call and
  the commit can leave an account pointing to a tenant that does not exist. The account then has no data and the
  registration can be repeated by an admin clearing `tenant_id`. Logged as ERROR and counted in
  `alinha.tenant.provisioning.orphaned`: `outcome=revoke_failed` (the undo failed: alert) or
  `outcome=unknown_commit` (the commit outcome is unknown, so the account may be right: check it).
- (−) Accounts that never finish registration stay as `registrant` (cleanup: #92).
- (−) The app holds a credential that can manage users. It is scoped to one realm and stored in OpenBao, but
  `manage-users` can map any realm role, `consultant` included, so it is as sensitive as an admin password.
- (−) The per-IP limit trusts `X-Forwarded-For` from internal proxies (Tomcat's default: private and loopback
  ranges). In production the backend must be reachable only through the BFF, which sets `X-Forwarded-For` from the
  socket peer, and `server.tomcat.remoteip.internal-proxies` should name only the BFF. Otherwise every registration
  shares the proxy's address and one limit (#14).

## Alternatives considered
- **Consultant registers the client (`/portfolio`):** still needs Keycloak admin access and a tenant switch inside a
  request, contradicts automatic consultant assignment (organization#5) and the self-service scenario.
- **Public registration endpoint in the application:** a second account system next to Keycloak, and an
  unauthenticated write (NFR07 allows only login and health check without a token).
- **Admin creates every account by hand:** does not meet FR01/US10.
- **`keycloak-admin-client` library:** pulls RESTEasy into the application for four HTTP calls; a `RestClient` is
  enough.
