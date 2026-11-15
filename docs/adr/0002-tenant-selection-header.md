# ADR 0002 — Consultants choose the organization per request with `X-Alinha-Tenant`

- **Status:** proposed
- **Date:** 2026-11-14
- **Issues:** #8 (tenant context, backend), #17 (shell), #14 (BFF)

## Context
RNF03: no tenant may read or write another tenant's data, and the tenant always comes from the token.
A client user (designated responsible person, analyst) has exactly one organization, in the `tenant_id` claim.
A consultant manages several client organizations (the `tenants` claim) and works on one of them at a time,
switching between them in the same session. The token alone cannot say which one a given request is for.

## Decision
- Every call the shell makes inside an organization sends `X-Alinha-Tenant: <tenant UUID>`. The portfolio
  (`/portfolio`) sends none. Plugins cannot set it: the shell's `host.api` overwrites or removes it.
- The header only **chooses** among the organizations the token already allows. `TenantContext` (#8) resolves
  the tenant from the token and validates the header:
  - consultant: header required (400 when missing or not a UUID) and must be in the `tenants` claim (403 otherwise);
  - client user: the tenant is `tenant_id`; a header, if sent, must match it (403 otherwise).
- Never from the body or the query string. The BFF forwards the header unchanged; it cannot tell who set it,
  so the protection is the backend check above, covered by `TenantResolutionTest`/`TenantResolutionWebTest` (#8).
- The shell keeps one query cache per organization and remounts plugin pages on a switch, so data of one
  client is never shown, or sent, under another.

## Consequences
- (+) Links name the client (`/portfolio/:org/...`); several tabs can work on different clients.
- (+) A forged header cannot widen access: the token's claims are the upper bound.
- (−) Every tenant-scoped endpoint depends on the header for consultants; a missing header is a 400.
- (−) Assigning or removing a consultant's organization takes effect when the token is refreshed.

## Alternatives considered
- **Organization in the token (one token per organization):** a token exchange or re-login on every switch.
- **Organization kept in the server session (BFF):** hidden state; tabs on different clients interfere.
- **Organization in the URL of every API call (`/api/orgs/{id}/...`):** the same check is needed, and every
  plugin's API would carry the tenant in its paths.
