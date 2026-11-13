# Tenant isolation (RNF03)

No tenant can read or write another tenant's data. The tenant always comes from the token.

## Where the tenant comes from

| User | Source | Rule |
|---|---|---|
| Designated Officer (`responsible`), Analyst | `tenant_id` claim | one tenant; `X-Alinha-Tenant`, if sent, must match it |
| Consultant | `X-Alinha-Tenant` header | required; must be one of the `tenants` claim (assigned organizations) |

Never read the tenant from the body or the query string.

| Situation | Answer |
|---|---|
| Consultant without `X-Alinha-Tenant`, or a header that is not a UUID | 400 |
| Organization not assigned to the consultant, client user asking for another tenant, token without tenant | 403 |

## Keycloak contract (realm `alinha`)

- User attribute `tenant_id` → access token claim `tenant_id` (UUID as a string), for client users.
- Multivalued user attribute `tenants` → access token claim `tenants` (list of UUIDs), for consultants.

The BFF forwards `X-Alinha-Tenant` to the app unchanged.

## How it is enforced

1. `TenantResolutionInterceptor` resolves the tenant for every endpoint, after the RBAC check.
   An endpoint with no tenant data (e.g. `/api/plugins`) opts out with `@TenantAgnostic`.
2. Entities holding tenant data extend `TenantOwnedEntity`. Hibernate's `@TenantId` fills
   `tenant_id` on insert and adds `tenant_id = ?` to every JPA/HQL query, bulk updates included.
3. A JPA session opened without a tenant (e.g. Spring Data validating `@Query` at startup) reads
   nothing, and any insert of a `TenantOwnedEntity` from it fails.

Tests: `TenantResolutionTest`, `TenantResolutionWebTest`, `TenantIsolationIntegrationTest` (real PostgreSQL).

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
4. Work without a request (event listeners, jobs): wrap it in `tenantContext.runAs(event.tenantId) { ... }`,
   started **outside** any transaction (a JPA session keeps the tenant it was opened with).
5. **Native SQL and `JdbcTemplate` are not filtered.** Avoid them for tenant data, or add
   `where tenant_id = :tenant` by hand and cover it with a cross-tenant test.
6. Provider credentials go through `pluginapi.TenantSecretStore`, which also takes the tenant from
   `TenantContext`: call it from a tenant-scoped request or inside `runAs`, never with a tenant id.
