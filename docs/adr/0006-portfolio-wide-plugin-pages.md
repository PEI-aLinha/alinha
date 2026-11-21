# ADR 0006 — Plugin pages across the whole portfolio at `/portfolio/<path>`

- **Status:** accepted
- **Date:** 2026-11-21
- **Issues:** #66 (this decision), alinha-plugin-dashboard-reports#3 (first user), #143 (backend `NavArea`)
- **Refs:** ADR 0002 (tenant header), ADR 0005 (English paths), `frontend/shell/src/routing/routes.tsx`

## Context
A plugin could mount consultant pages only for one client, at `/portfolio/:org/<path>`. Some pages are about the
whole portfolio instead: the consultant's dashboard of every client (dashboard-reports#3), or notifications across
clients. They had no place. The obvious address, `/portfolio/<path>`, shares its first segment with
`/portfolio/:org`, so a plugin page and a client could in principle claim the same URL.

## Decision
- A new plugin area, **`portfolio`**, is mounted at **`/portfolio/<path>`** next to the client list (`/portfolio`)
  and the per-client area (`/portfolio/:org/<path>`). Its menu entries (`area: 'portfolio'`) sit under
  «Carteira de clientes», both on the portfolio and inside a client.
- A portfolio page has **no organization**: `useHost().organization` and `tenantId` are `null`, and its API calls
  carry no `X-Alinha-Tenant`. Its backend endpoints work across the consultant's assigned tenants (the token's
  `tenants` claim), never on one tenant chosen by the page.
- Collisions are avoided by routing, not by a prefix. React Router ranks a fixed segment (`overview`) above a
  dynamic one (`:org`), so `/portfolio/overview` is the plugin page and `/portfolio/<any other slug>` is a client.
  The shell refuses, at start-up, a portfolio route that is an index route or starts with a dynamic segment or a
  splat, since it would replace the client list or catch every client.
- Today a slug is the tenant UUID (BFF `MeService`, pinned by `MeServiceTest`), so it can never equal a plugin path. **If slugs ever become
  readable names, the first segments of the plugins' portfolio routes must be reserved** and never given to an
  organization.

### Reading across tenants
A portfolio endpoint has no `X-Alinha-Tenant`, so `TenantContext.current()` does not apply. It reads instead:

- **`TenantContext.assignedTenants(): Set<TenantId>`**, a new core method that returns the token's `tenants` claim.
  It always comes from the token and never from the request (no header, body or query), the same rule as RNF03.
  The first portfolio endpoint (dashboard-reports#2) adds it to the core.
- Every query is filtered by that set: `WHERE tenant_id = ANY(:assigned)` (or `IN (...)`), never by a list the
  page sends. An empty set returns an empty answer without querying. A page that wants one client calls that
  client's endpoint with its `X-Alinha-Tenant` instead.
- Only roles that open the consultant area may call these endpoints. Their permission (e.g. `portfolio:read`,
  declared by dashboard-reports) is granted to `consultant` only, so a client user never reads across tenants.
- The answer holds one entry per assigned tenant at most. Tenants outside the token's set are dropped by the query,
  not by the page.

The backend does not yet know the `portfolio` area for menu entries (`NavArea` in `plugin-api`, the contract, and
`Roles`). Until #143 adds it, portfolio menu entries come from the plugin's frontend `definePlugin({ nav })`.

## Consequences
- (+) Short, readable addresses (`/portfolio/overview`), consistent with the rest of the consultant area.
- (+) Plugins do not change: their paths stay relative, and the shell builds every link.
- (−) Plugin portfolio paths and organization slugs share one namespace. Safe while slugs are UUIDs; needs a
  reserved-word check if that changes.

## Alternatives considered
- **`/portfolio/all/<path>`:** no shared namespace beyond one reserved word, but a longer address for no gain while
  slugs are UUIDs.
- **Move one client to `/portfolio/clients/:org/...`:** frees `/portfolio/<path>` completely, but changes an address
  scheme the team had agreed and documented, and every link and test in the shell.
