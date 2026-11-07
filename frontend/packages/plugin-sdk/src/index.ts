/**
 * Contrato entre o shell do frontend e os plugins.
 * Equivalente, no frontend, à plugin-api do backend.
 */
import { createContext, useContext, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'

/** Área da aplicação onde as rotas do plugin são montadas. */
export type Area =
  | 'client'      // /org/:org/...   — responsável da PME e analistas
  | 'consultant'  // /carteira/...   — consultora (service provider)

export interface NavItem {
  label: string
  /** Caminho relativo à área (ex.: 'ativos' → /org/:org/ativos). */
  path: string
  area: Area
  icon?: ComponentType<{ className?: string }>
  /** Recurso RBAC necessário para ver a entrada (ex.: 'asset'). */
  resource?: string
  /** Ordem no menu (menor primeiro). */
  order?: number
}

export interface FrontendPlugin {
  /** Igual ao id do plugin.yaml e do backend. */
  id: string
  name: string
  routes: Partial<Record<Area, RouteObject[]>>
  nav?: NavItem[]
}

/** Ajuda o TypeScript a validar o objeto exportado por cada plugin. */
export function definePlugin(plugin: FrontendPlugin): FrontendPlugin {
  return plugin
}

/** O que o shell disponibiliza aos plugins (sessão, tenant, permissões). */
export interface PluginHost {
  tenantId: string | null
  user: { id: string; name: string; roles: string[] } | null
  can: (resource: string, action?: string) => boolean
  /** fetch para /api com sessão do BFF (cookie HttpOnly). */
  api: <T>(path: string, init?: RequestInit) => Promise<T>
}

export const PluginHostContext = createContext<PluginHost | null>(null)

export function useHost(): PluginHost {
  const host = useContext(PluginHostContext)
  if (!host) throw new Error('useHost() fora do PluginHostContext — o shell não montou o host.')
  return host
}
