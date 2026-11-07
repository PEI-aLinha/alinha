import type { ReactNode } from 'react'
import { PluginHostContext, type PluginHost } from '@alinha/plugin-sdk'

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: 'include', ...init })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`)
  return res.json() as Promise<T>
}

/**
 * TODO sprint 0: wire to the BFF session (real user, tenant and roles).
 * Migrating the prototype: src/core/session.tsx, tenant.tsx and rbac.ts.
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
