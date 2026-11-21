/**
 * Types generated from `contracts/openapi/alinha.yaml`.
 * Change the contract, then run `pnpm gen:api`; CI fails when this package is out of date.
 */
import type { components, paths } from './schema.generated'

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

/** GET /api/plugins: a plugin manifest, as registered by the backend. */
export type PluginManifestDto = components['schemas']['PluginManifest']

/** GET /api/audit/events: one entry of the organization's change history (`actor` is a `sub` or `system`). */
export type AuditEventDto = components['schemas']['AuditEvent']

/** GET /api/audit/events: one page of history, newest first. */
export type AuditEventPageDto = components['schemas']['AuditEventPage']

/** RFC 9457 Problem Details sent on every error (ADR 0003). `detail` is in Portuguese when it is meant for the user. */
export type ProblemDto = components['schemas']['Problem']

type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'
type JsonContent<R> = R extends { content: { 'application/json': infer T } } ? T : undefined

/**
 * JSON body of the success response (200, else 201, else none) of an operation in the contract.
 * `ApiResponse<'/api/me'>` is MeDto; `ApiResponse<'/api/x', 'post'>` for a POST.
 */
export type ApiResponse<P extends keyof paths, M extends HttpMethod = 'get'> =
  paths[P][M] extends { responses: infer R }
    ? R extends { 200: infer Ok } ? JsonContent<Ok>
      : R extends { 201: infer Created } ? JsonContent<Created>
        : undefined
    : never

/** JSON request body of an operation in the contract, e.g. `ApiRequestBody<'/api/x', 'post'>`. */
export type ApiRequestBody<P extends keyof paths, M extends HttpMethod = 'post'> =
  paths[P][M] extends { requestBody?: { content: { 'application/json': infer B } } } ? B : never
