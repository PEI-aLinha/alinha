/**
 * Contract between the frontend shell and the plugins.
 * The frontend counterpart of the backend plugin-api.
 */
import { createContext, useContext, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'

/** Application area where a plugin's routes are mounted. */
export type Area =
  | 'client'      // /org/:org/<path>        — designated responsible person and analysts
  | 'consultant'  // /portfolio/:org/<path>  — consultant (service provider), for one client organization
  | 'onboarding'  // /onboarding/<path>      — full page, no organization yet (a `registrant` setting one up)

/** Areas with a menu. The onboarding is a full page with no menu, so it takes no menu entries. */
export type NavArea = Exclude<Area, 'onboarding'>

export interface NavItem {
  /** Visible text — in Portuguese (UI requirement). */
  label: string
  /** Path relative to the area in English (e.g. 'assets' → /org/:org/assets). */
  path: string
  area: NavArea
  icon?: ComponentType<{ className?: string }>
  /** RBAC resource required to see the entry (e.g. 'asset'); checked as `<resource>:<action>`. */
  resource?: string
  /** Defaults to 'read'. Use the same action as the route's `handle.action`, so menu and page agree. */
  action?: string
  /** Menu order (lower first). */
  order?: number
}

/**
 * What the shell reads from a plugin route's `handle`.
 * With `resource`, the shell shows its "no access" page unless the user has `<resource>:<action>`.
 */
export interface RouteHandle {
  /** Page title, in Portuguese. */
  title?: string
  resource?: string
  /** Defaults to 'read'. */
  action?: string
}

export interface FrontendPlugin {
  /** Same as the id in plugin.yaml and in the backend. */
  id: string
  name: string
  routes: Partial<Record<Area, RouteObject[]>>
  nav?: NavItem[]
}

/** Lets TypeScript validate the object each plugin exports. */
export function definePlugin(plugin: FrontendPlugin): FrontendPlugin {
  return plugin
}

/** The organization (tenant) the current page works on. */
export interface HostOrganization {
  /** Tenant UUID. */
  id: string
  /** Used in URLs (/org/:org, /portfolio/:org). */
  slug: string
  name: string
}

/** One invalid field of a request: `field` is the JSON path (e.g. `address.postalCode`), `message` is for the user. */
export interface FieldProblem {
  field: string
  message: string
}

/**
 * RFC 9457 Problem Details as the backend sends them (ADR 0003). The platform's own errors carry a Portuguese
 * `detail` that is safe to show, but only trust it for the `code`s and statuses a page handles: a framework or
 * gateway error can carry English or technical text. `detail` may also be empty.
 */
export interface Problem {
  title?: string
  detail?: string
  /** Stable machine code, e.g. `VALIDATION_FAILED`, `NIF_ALREADY_REGISTERED`. */
  code?: string
  errors?: FieldProblem[]
}

/**
 * A failed call to /api. `status` is the HTTP status (403: no permission, 404: not found, ...).
 * `problem` is the response's Problem Details body, when it had one; read `problem.detail` to show the server's
 * message. `message` is always `HTTP <status> on <path>`, for logs, never for the user.
 */
export class ApiError extends Error {
  readonly status: number
  readonly path: string
  readonly problem?: Problem

  constructor(status: number, path: string, message?: string, problem?: Problem) {
    super(message ?? `HTTP ${status} on ${path}`)
    this.name = 'ApiError'
    this.status = status
    this.path = path
    this.problem = problem
  }
}

/** What the shell provides to plugins (session, tenant, permissions). */
export interface PluginHost {
  /** Tenant UUID of the current organization; null outside an organization (e.g. the portfolio). */
  tenantId: string | null
  organization: HostOrganization | null
  user: { id: string; name: string; roles: string[] } | null
  /** Whether the user has `<resource>:<action>` (action defaults to 'read'). */
  can: (resource: string, action?: string) => boolean
  /**
   * fetch to /api with the BFF session (HttpOnly cookie) and the current organization.
   * Throws [ApiError] when the response is not 2xx. On a 401 the shell checks the session again (`/api/me`):
   * only if that also answers 401 does the user go to the login page; otherwise this call just fails.
   */
  api: <T>(path: string, init?: RequestInit) => Promise<T>
  /**
   * Signs in again to get a fresh token, then comes back to `returnTo` (a same-origin path, default the current page).
   * Use it when the backend changed the account's roles or organization (e.g. right after registering an
   * organization, ADR 0004): until then `/api/me` still shows the old session. Leaves the page.
   */
  reauthenticate: (returnTo?: string) => void
}

export const PluginHostContext = createContext<PluginHost | null>(null)

export function useHost(): PluginHost {
  const host = useContext(PluginHostContext)
  if (!host) throw new Error('useHost() called outside PluginHostContext — the shell did not mount the host.')
  return host
}

export { useApiQuery, useApiMutation, type ApiMethod, type UseApiMutationOptions } from './query'
export { errorMessage, fieldErrors, ERROR_MESSAGES, NetworkError } from './errors'
export {
  formatDate,
  formatDateTime,
  formatRelativeTime,
  formatNumber,
  formatPercent,
  formatCurrency,
  LOCALE,
  TIME_ZONE,
  EMPTY_VALUE,
  type DateInput,
} from './format'
