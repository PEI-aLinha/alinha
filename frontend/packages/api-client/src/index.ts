/**
 * Types generated from `contracts/openapi/alinha.yaml`.
 * Change the contract, then run `pnpm gen:api`; CI fails when this package is out of date.
 */
import type { components } from './schema.generated'

export type { components, operations, paths } from './schema.generated'

/** Keycloak realm roles known by the platform (same as the backend RBAC). */
export type Role = components['schemas']['Role']

/** An organization (tenant) the user can work on. `id` is the tenant UUID; `slug` is used in URLs. */
export type OrganizationRefDto = components['schemas']['OrganizationRef']

/**
 * GET /api/me (BFF, issue #14): who is signed in. 401 when there is no session.
 * - `permissions`: "<resource>:<action>" strings, computed by the backend from the roles.
 * - `organizations`: the client user's own organization (exactly one), or the consultant's
 *   assigned organizations (the `tenants` claim).
 */
export type MeDto = components['schemas']['Me']
