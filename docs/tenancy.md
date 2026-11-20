# Tenant isolation (RNF03)

No tenant can read or write another tenant's data. The tenant always comes from the token.

## Where the tenant comes from

| User | Source | Rule |
|---|---|---|
| Designated Officer (`responsible`), Analyst | `tenant_id` claim | one tenant; `X-Alinha-Tenant`, if sent, must match it |
| Consultant | `X-Alinha-Tenant` header | required; must be one of the `tenants` claim (assigned organizations) |

A token with the `consultant` role follows the consultant rules even if it also has a client role
(e.g. `responsible`) and a `tenant_id` claim: `X-Alinha-Tenant` is required and `tenant_id` is ignored.

Never read the tenant from the body or the query string.

| Situation | Answer |
|---|---|
| Consultant without `X-Alinha-Tenant`, or a header that is not a UUID | 400 |
| Organization not assigned to the consultant, client user asking for another tenant, token without tenant | 403 |

## Keycloak contract (realm `alinha`)

- User attribute `tenant_id` → access token claim `tenant_id` (UUID as a string), for client users.
- Multivalued user attribute `tenants` → access token claim `tenants` (list of UUIDs), for consultants.

The BFF forwards `X-Alinha-Tenant` to the app unchanged.

## New organizations (ADR 0004)

A self-registered account has the `registrant` role and no tenant. The organization plugin registers the
organization through `core.tenant.TenantProvisioning.provisionForCurrentUser { tenantId -> ... }`:

- The caller must be a `registrant` with no `tenant_id`/`tenants` claim and not a consultant (otherwise 403/409).
- `core.tenant` gets one row per account (`owner_subject` unique), so a repeated or concurrent registration is a 409.
- One registration per account at a time, across instances: a PostgreSQL advisory lock on the account is held until
  the transaction and any Keycloak undo have finished. A registration that finds it held gets 503 `REGISTRATION_BUSY`.
- The block runs **as the new tenant**, in one transaction with the `tenant.provisioned` audit entry. This is the
  only tenant switch allowed inside a request, and only for a tenant created in that same call; `runAs` stays
  refused (rule 6 below).
- Last, the account gets `tenant_id` and `responsible` in Keycloak. A Keycloak failure rolls everything back; a
  commit failure afterwards undoes the Keycloak change.
- The caller's token is stale until it is refreshed (BFF, #14).

## How it is enforced

1. `TenantResolutionInterceptor` resolves the tenant for every endpoint, after the RBAC check.
   An endpoint with no tenant data (e.g. `/api/plugins`) opts out with `@TenantAgnostic`.
2. Entities holding tenant data extend `TenantOwnedEntity`. Hibernate's `@TenantId` fills
   `tenant_id` on insert and adds `tenant_id = ?` to every JPA/HQL query, bulk updates included.
3. A JPA session opened without a tenant (e.g. Spring Data validating `@Query` at startup) reads
   nothing, and any insert of a `TenantOwnedEntity` from it fails.

4. Plugins may not use raw JDBC (`JdbcTemplate`/`JdbcOperations`, `JdbcClient`, `SimpleJdbcInsert`/`Call`,
   `DataSource`, `Connection`, `Statement`, `DriverManager`), native queries (`@Query(nativeQuery = true)`, `@NativeQuery`,
   `EntityManager.createNativeQuery`) or `Session.doWork`/`doReturningWork`, which skip the tenant filter.
   `TenantFilterBypassTest` (ArchUnit) fails the build when a plugin does, and checks its rules against known
   bypasses so they cannot go silent. Raw SQL that really is needed lives in the core and filters by `tenant_id`
   by hand. There is no database-level backstop (no RLS).
5. The request tenant is bound to the request thread. Work handed to another thread (`@Async`, executors,
   `CompletableFuture`, virtual threads, `Callable`/`DeferredResult`) has **no tenant**: reads return nothing
   and inserts fail. It fails closed, but that work must set the tenant itself (see rule 4 for plugin authors).
6. `runAs` is refused inside any HTTP request (`@TenantAgnostic` endpoints included), so a handler cannot
   switch to another tenant.
7. A blank `X-Alinha-Tenant` counts as absent. The nil UUID (`00000000-…`) is never a tenant: it is rejected in
   the header (400) and ignored in the claims (403).

Tests: `TenantResolutionTest`, `JwtTenantContextTest`, `TenantFilterBypassTest`, `TenantResolutionWebTest`, `TenantIsolationIntegrationTest` (real PostgreSQL).

## Rules for plugin authors

1. Every table with tenant data has `tenant_id uuid not null` and an index on it:

   ```sql
   create table organization.organization_profile (
       id         uuid primary key,
       tenant_id  uuid not null,
       name       text not null
   );
   create index organization_profile_tenant_idx on organization.organization_profile (tenant_id);
   ```

2. Its entity extends `pt.alinha.core.tenant.TenantOwnedEntity`. Do not set `tenantId` yourself.
3. Endpoints get the tenant automatically through `TenantContext.current()`. Use `@TenantAgnostic` only
   for endpoints that touch no tenant data.
4. Work without a request (event listeners, jobs, `@Async` code): wrap it in
   `tenantContext.runAs(event.tenantId) { tx.execute { ... } }`, started **outside** any transaction (a JPA
   session keeps the tenant it was opened with). `@ApplicationModuleListener` and `@Transactional` listeners
   open the transaction before your code runs, so `runAs` fails there: use a plain
   `@Async @TransactionalEventListener` and open the transaction inside `runAs`. `TenantIsolationIntegrationTest`
   has a working example (`FixtureNoteListener`).
5. **Native SQL and raw JDBC are not filtered**, so `TenantFilterBypassTest` bans them in plugins.
   Use JPA/HQL. If a case truly needs raw SQL, ask for it in the core and add a cross-tenant test.
6. Provider credentials go through `pluginapi.TenantSecretStore`, which also takes the tenant from
   `TenantContext`: call it from a tenant-scoped request or inside `runAs`, never with a tenant id.
