/**
 * Types generated from the backend OpenAPI spec (contracts/openapi/alinha.yaml).
 * Generate with:  pnpm dlx openapi-typescript ../../../contracts/openapi/alinha.yaml -o src/schema.ts
 * Until then, this file only exports hand-written shared types.
 */
/** Keycloak realm roles known by the platform (same as the backend RBAC). */
export type Role = 'consultant' | 'responsible' | 'analyst'

/** An organization (tenant) the user can work on. `id` is the tenant UUID; `slug` is used in URLs. */
export interface OrganizationRefDto {
  id: string
  slug: string
  name: string
}

/**
 * GET /api/me (BFF, issue #14): who is signed in. 401 when there is no session.
 * - `permissions`: "<resource>:<action>" strings, computed by the backend from the roles.
 * - `organizations`: the client user's own organization (exactly one), or the consultant's
 *   assigned organizations (the `tenants` claim).
 */
export interface MeDto {
  user: { id: string; name: string; email?: string }
  roles: Role[]
  permissions: string[]
  organizations: OrganizationRefDto[]
}

export interface PluginManifestDto {
  id: string
  name: string
  kind: 'FEATURE' | 'FRAMEWORK' | 'PROVIDER'
  version: string
  publishes: string[]
  consumes: string[]
  permissions: string[]
}
