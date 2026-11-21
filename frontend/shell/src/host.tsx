import { useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import type { MeDto } from '@alinha/api-client'
import { ApiError, NetworkError, PluginHostContext, type HostOrganization, type PluginHost } from '@alinha/plugin-sdk'
import { API_REQUEST, isUnauthenticatedResponse, NotJsonError, readJson, withCsrf } from './http'
import { isSafeReturnPath, login, reauthentication } from './session/login'
import { ME_QUERY_KEY, useSession } from './session/SessionGate'
import { needsOnboarding } from './session/permissions'

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
    withCsrf(headers)
    let res: Response
    try {
      res = await fetchFn(`/api${normalized}`, { ...init, ...API_REQUEST, headers })
    } catch (error) {
      // A cancelled request (TanStack Query aborts on unmount) is not a network failure: let it through as is.
      if (init?.signal?.aborted) throw error
      throw new NetworkError(normalized, { cause: error })
    }
    if (isUnauthenticatedResponse(res)) onUnauthenticated()
    return readJson<T>(res, normalized)
  }
}

/**
 * Retry network and server errors up to 3 times. A 4xx (no session, no permission, not found) or a body that is
 * not JSON will not change, so those fail at once.
 */
export const retryUnlessClientError = (failureCount: number, error: unknown) =>
  !(error instanceof ApiError && error.status >= 400 && error.status < 500) && !(error instanceof NotJsonError) && failureCount < 3

/** BFF endpoint that swaps the session's access token for a fresh one, using the stored refresh token (#108). */
export const REFRESH_PATH = '/auth/refresh'

interface ReauthenticateOptions {
  /** Asks /api/me again and resolves with the new session once it is back. */
  reloadSession: () => Promise<MeDto | undefined>
  navigate: (to: string) => void
  fetchFn?: typeof fetch
}

/**
 * `host.reauthenticate`: asks the BFF for a fresh token (new roles, new organizations) without leaving the page,
 * then reloads `/api/me` so the layouts re-route on their own. Only when the BFF answers 401 (the refresh token or
 * the Keycloak SSO session expired) does the person sign in again, which leaves the page.
 */
export function createReauthenticate({ reloadSession, navigate, fetchFn = fetch }: ReauthenticateOptions): PluginHost['reauthenticate'] {
  return async (returnTo?: string) => {
    let res: Response
    try {
      res = await fetchFn(REFRESH_PATH, { ...API_REQUEST, method: 'POST', headers: withCsrf(new Headers()) })
    } catch (error) {
      throw new NetworkError(REFRESH_PATH, { cause: error })
    }
    if (isUnauthenticatedResponse(res)) {
      reauthentication.started()
      login.go(returnTo)
      return
    }
    if (!res.ok) throw new ApiError(res.status, REFRESH_PATH)
    const me = await reloadSession()
    // The token is fresh but still has no organization (e.g. the identity provider has not caught up yet):
    // the onboarding shows "Quase pronto" instead of the form, so the organization is not registered twice.
    if (me && needsOnboarding(me.roles, me.organizations)) reauthentication.started()
    if (returnTo !== undefined && isSafeReturnPath(returnTo)) navigate(returnTo)
  }
}

/**
 * Gives plugins the host for one organization (or none, e.g. on the portfolio). Each organization
 * gets its own query cache, so data from one client is never shown while looking at another.
 */
export function HostProvider({ organization, children }: { organization: HostOrganization | null; children: ReactNode }) {
  const { me, can } = useSession()
  // The session lives in the root client (SessionGate); each organization gets its own client below.
  const sessionClient = useQueryClient()
  const navigate = useNavigate()
  const tenantId = organization?.id ?? null
  // One client per mount: the layouts key HostProvider by organization, so another organization gets a new one.
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: retryUnlessClientError } } }))
  const host = useMemo<PluginHost>(() => ({
    tenantId,
    organization,
    organizations: me.organizations,
    user: { id: me.user.id, name: me.user.name, roles: me.roles },
    can,
    // A 401 from a plugin call: ask /api/me again. If the session expired, SessionGate sends the person to
    // the login (with its loop guard); if it is still valid, the call just fails and the page shows the error.
    api: createApi({ tenantId, onUnauthenticated: () => void sessionClient.invalidateQueries({ queryKey: ME_QUERY_KEY }) }),
    reauthenticate: createReauthenticate({
      reloadSession: async () => {
        await sessionClient.invalidateQueries({ queryKey: ME_QUERY_KEY })
        return sessionClient.getQueryData<MeDto>(ME_QUERY_KEY)
      },
      navigate,
    }),
  }), [tenantId, organization, me, can, sessionClient, navigate])

  return (
    <QueryClientProvider client={queryClient}>
      <PluginHostContext.Provider value={host}>{children}</PluginHostContext.Provider>
    </QueryClientProvider>
  )
}
