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

export interface NavItem {
  /** Visible text — in Portuguese (UI requirement). */
  label: string
  /** Path relative to the area in English (e.g. 'assets' → /org/:org/assets). */
  path: string
  area: Area
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

/** A failed call to /api. `status` is the HTTP status (403: no permission, 404: not found, ...). */
export class ApiError extends Error {
  readonly status: number
  readonly path: string

  constructor(status: number, path: string, message?: string) {
    super(message ?? `HTTP ${status} on ${path}`)
    this.name = 'ApiError'
    this.status = status
    this.path = path
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
}

export const PluginHostContext = createContext<PluginHost | null>(null)

export function useHost(): PluginHost {
  const host = useContext(PluginHostContext)
  if (!host) throw new Error('useHost() called outside PluginHostContext — the shell did not mount the host.')
  return host
}
