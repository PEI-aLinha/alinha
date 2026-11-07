import type { ReactNode } from 'react'
import { PluginHostContext, type PluginHost } from '@alinha/plugin-sdk'

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: 'include', ...init })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} em ${path}`)
  return res.json() as Promise<T>
}

/**
 * TODO sprint 0: ligar à sessão do BFF (utilizador, tenant e roles reais).
 * Migração do protótipo: src/core/session.tsx, tenant.tsx e rbac.ts.
 */
export function HostProvider({ children }: { children: ReactNode }) {
  const host: PluginHost = {
    tenantId: null,
    user: null,
    can: () => true,
    api,
  }
  return <PluginHostContext.Provider value={host}>{children}</PluginHostContext.Provider>
}
