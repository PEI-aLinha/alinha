import { useMemo, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError, PluginHostContext, type HostOrganization, type PluginHost } from '@alinha/plugin-sdk'
import { useSession } from './session/SessionGate'
import { login } from './session/login'

/** Header the backend reads to know which organization a consultant is working on (see docs/tenancy.md). */
export const TENANT_HEADER = 'X-Alinha-Tenant'

interface ApiOptions {
  tenantId: string | null
  onUnauthenticated?: () => void
  fetchFn?: typeof fetch
}

/** `host.api`: fetch to /api with the session cookie and the current organization. */
export function createApi({ tenantId, onUnauthenticated = () => login.go(), fetchFn = fetch }: ApiOptions): PluginHost['api'] {
  return async <T,>(path: string, init?: RequestInit): Promise<T> => {
    const normalized = path.startsWith('/') ? path : `/${path}`
    const headers = new Headers(init?.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (tenantId) headers.set(TENANT_HEADER, tenantId)
    else headers.delete(TENANT_HEADER)
    const res = await fetchFn(`/api${normalized}`, { ...init, headers, credentials: 'include' })
    if (res.status === 401) onUnauthenticated()
    if (!res.ok) throw new ApiError(res.status, normalized)
    if (res.status === 204 || res.headers.get('Content-Length') === '0') return undefined as T
    return (await res.json()) as T
  }
}

/**
 * Gives plugins the host for one organization (or none, e.g. on the portfolio). Each organization
 * gets its own query cache, so data from one client is never shown while looking at another.
 */
export function HostProvider({ organization, children }: { organization: HostOrganization | null; children: ReactNode }) {
  const { me, can } = useSession()
  const tenantId = organization?.id ?? null
  const queryClient = useMemo(() => new QueryClient(), [tenantId])
  const host = useMemo<PluginHost>(() => ({
    tenantId,
    organization,
    user: { id: me.user.id, name: me.user.name, roles: me.roles },
    can,
    api: createApi({ tenantId }),
  }), [tenantId, organization, me, can])

  return (
    <QueryClientProvider client={queryClient}>
      <PluginHostContext.Provider value={host}>{children}</PluginHostContext.Provider>
    </QueryClientProvider>
  )
}
