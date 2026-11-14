import { useMemo, useState, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { ApiError, PluginHostContext, type HostOrganization, type PluginHost } from '@alinha/plugin-sdk'
import { API_REQUEST, isUnauthenticatedResponse, readJson } from './http'
import { ME_QUERY_KEY, useSession } from './session/SessionGate'

/**
 * Header that tells the backend which organization a call is for. The backend checks it against the token
 * (TenantContext, issue #8): a consultant only gets organizations from their portfolio, a client user only their own.
 */
export const TENANT_HEADER = 'X-Alinha-Tenant'

interface ApiOptions {
  tenantId: string | null
  /** Called on a 401. The shell re-checks the session instead of redirecting, so a broken endpoint cannot loop. */
  onUnauthenticated: () => void
  fetchFn?: typeof fetch
}

/**
 * `host.api`: fetch to /api with the session cookie and the current organization. The shell always decides
 * `credentials` (the session cookie), `redirect` and the tenant header; a plugin's values for them are ignored.
 */
export function createApi({ tenantId, onUnauthenticated, fetchFn = fetch }: ApiOptions): PluginHost['api'] {
  return async <T,>(path: string, init?: RequestInit): Promise<T> => {
    const normalized = path.startsWith('/') ? path : `/${path}`
    const headers = new Headers(init?.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (tenantId) headers.set(TENANT_HEADER, tenantId)
    else headers.delete(TENANT_HEADER)
    const res = await fetchFn(`/api${normalized}`, { ...init, ...API_REQUEST, headers })
    if (isUnauthenticatedResponse(res)) onUnauthenticated()
    return readJson<T>(res, normalized)
  }
}

/** Retry network and server errors up to 3 times; a 4xx (no session, no permission, not found) will not change. */
export const retryUnlessClientError = (failureCount: number, error: unknown) =>
  !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 3

/**
 * Gives plugins the host for one organization (or none, e.g. on the portfolio). Each organization
 * gets its own query cache, so data from one client is never shown while looking at another.
 */
export function HostProvider({ organization, children }: { organization: HostOrganization | null; children: ReactNode }) {
  const { me, can } = useSession()
  // The session lives in the root client (SessionGate); each organization gets its own client below.
  const sessionClient = useQueryClient()
  const tenantId = organization?.id ?? null
  // One client per mount: the layouts key HostProvider by organization, so another organization gets a new one.
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: retryUnlessClientError } } }))
  const host = useMemo<PluginHost>(() => ({
    tenantId,
    organization,
    user: { id: me.user.id, name: me.user.name, roles: me.roles },
    can,
    // A 401 from a plugin call: ask /api/me again. If the session expired, SessionGate sends the person to
    // the login (with its loop guard); if it is still valid, the call just fails and the page shows the error.
    api: createApi({ tenantId, onUnauthenticated: () => void sessionClient.invalidateQueries({ queryKey: ME_QUERY_KEY }) }),
  }), [tenantId, organization, me, can, sessionClient])

  return (
    <QueryClientProvider client={queryClient}>
      <PluginHostContext.Provider value={host}>{children}</PluginHostContext.Provider>
    </QueryClientProvider>
  )
}
