/**
 * Contract between the frontend shell and the plugins.
 * The frontend counterpart of the backend plugin-api.
 */
import { createContext, useContext, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'

/** Application area where a plugin's routes are mounted. */
export type Area =
  | 'client'      // /org/:org/...   — designated responsible person and analysts
  | 'consultant'  // /carteira/...   — consultant (service provider)

export interface NavItem {
  /** Visible text — in Portuguese (UI requirement). */
  label: string
  /** Path relative to the area (e.g. 'ativos' → /org/:org/ativos). */
  path: string
  area: Area
  icon?: ComponentType<{ className?: string }>
  /** RBAC resource required to see the entry (e.g. 'asset'). */
  resource?: string
  /** Menu order (lower first). */
  order?: number
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

/** What the shell provides to plugins (session, tenant, permissions). */
export interface PluginHost {
  tenantId: string | null
  user: { id: string; name: string; roles: string[] } | null
  can: (resource: string, action?: string) => boolean
  /** fetch to /api with the BFF session (HttpOnly cookie). */
  api: <T>(path: string, init?: RequestInit) => Promise<T>
}

export const PluginHostContext = createContext<PluginHost | null>(null)

export function useHost(): PluginHost {
  const host = useContext(PluginHostContext)
  if (!host) throw new Error('useHost() called outside PluginHostContext — the shell did not mount the host.')
  return host
}
