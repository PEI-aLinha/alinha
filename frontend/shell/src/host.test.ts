import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError } from '@alinha/plugin-sdk'
import type { MeDto } from '@alinha/api-client'
import { createApi, createReauthenticate, REFRESH_PATH, retryUnlessClientError, TENANT_HEADER } from './host'
import { login, reauthentication } from './session/login'
import { NotJsonError } from './http'

const ok = (body: unknown = {}, init: ResponseInit = {}) => new Response(JSON.stringify(body), { status: 200, ...init })
const api = (tenantId: string | null, fetchFn: typeof fetch, onUnauthenticated = () => {}) =>
  createApi({ tenantId, fetchFn, onUnauthenticated })

describe('createApi', () => {
  it('calls /api with the session cookie, JSON and the organization', async () => {
    const fetchFn = vi.fn(async () => ok({ a: 1 }))
    await expect(api('t-1', fetchFn)('/assets')).resolves.toEqual({ a: 1 })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(url).toBe('/api/assets')
    expect(init.credentials).toBe('include')
    expect(headers.get(TENANT_HEADER)).toBe('t-1')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('sends the CSRF token from the BFF cookie on writes', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123'
    try {
      const fetchFn = vi.fn(async () => ok())
      await api(null, fetchFn)('/x', { method: 'POST' })
      const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
      expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('csrf-123')
    } finally {
      document.cookie = 'XSRF-TOKEN=; Max-Age=0'
    }
  })

  it('adds the leading slash and keeps the caller options', async () => {
    const fetchFn = vi.fn(async () => ok())
    await api(null, fetchFn)('assets', { method: 'POST', headers: { Accept: 'text/csv' } })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/assets')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Accept')).toBe('text/csv')
  })

  it('never lets a plugin choose the organization itself', async () => {
    const fetchFn = vi.fn(async () => ok())
    await api('t-1', fetchFn)('/x', { headers: { [TENANT_HEADER]: 'other' } })
    await api(null, fetchFn)('/x', { headers: { [TENANT_HEADER]: 'other' } })
    const sent = fetchFn.mock.calls.map((c) => new Headers((c as unknown as [string, RequestInit])[1].headers).get(TENANT_HEADER))
    expect(sent).toEqual(['t-1', null])
  })

  it('throws NetworkError when fetch itself fails', async () => {
    const cause = new TypeError('Failed to fetch')
    const error = await api(null, async () => { throw cause })('/x').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(NetworkError)
    expect((error as NetworkError).path).toBe('/x')
    expect((error as NetworkError).cause).toBe(cause)
  })

  it('lets a cancelled request fail as it is, not as a network error', async () => {
    const controller = new AbortController()
    controller.abort()
    const abort = new DOMException('Aborted', 'AbortError')
    const error = await api(null, async () => { throw abort })('/x', { signal: controller.signal }).catch((e: unknown) => e)
    expect(error).toBe(abort)
  })

  it('throws ApiError with the status', async () => {
    const error = await api(null, async () => new Response('', { status: 403 }))('/x').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 403, path: '/x' })
  })

  it('reports a 401 and still fails the call', async () => {
    const onUnauthenticated = vi.fn()
    await expect(api(null, async () => new Response('', { status: 401 }), onUnauthenticated)('/x')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('does not report other failures as a lost session', async () => {
    const onUnauthenticated = vi.fn()
    await api(null, async () => new Response('', { status: 403 }), onUnauthenticated)('/x').catch(() => {})
    expect(onUnauthenticated).not.toHaveBeenCalled()
  })

  it('retries server and network errors, never a 4xx', () => {
    expect(retryUnlessClientError(0, new ApiError(503, '/x'))).toBe(true)
    expect(retryUnlessClientError(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(retryUnlessClientError(3, new ApiError(503, '/x'))).toBe(false)
    for (const status of [400, 401, 403, 404]) expect(retryUnlessClientError(0, new ApiError(status, '/x'))).toBe(false)
    expect(retryUnlessClientError(0, new NotJsonError('/x'))).toBe(false)
  })

  it('returns undefined for empty responses', async () => {
    await expect(api(null, async () => new Response(null, { status: 204 }))('/x')).resolves.toBeUndefined()
    await expect(api(null, async () => new Response('', { status: 200, headers: { 'Content-Length': '0' } }))('/x')).resolves.toBeUndefined()
  })

  it('returns undefined for an empty 200 without Content-Length (chunked)', async () => {
    const chunked = new Response(new ReadableStream({ start: (c) => c.close() }), { status: 200 })
    expect(chunked.headers.get('Content-Length')).toBeNull()
    await expect(api(null, async () => chunked)('/x')).resolves.toBeUndefined()
  })

  it('rejects HTML with a clear error instead of a SyntaxError', async () => {
    await expect(api(null, async () => new Response('<html></html>', { status: 200 }))('/x')).rejects.toThrow('did not return JSON')
  })

  it('does not follow redirects, and treats one as a lost session', async () => {
    const onUnauthenticated = vi.fn()
    const fetchFn = vi.fn(async () => ({ type: 'opaqueredirect', status: 0, ok: false }) as Response)
    await expect(api(null, fetchFn, onUnauthenticated)('/x')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthenticated).toHaveBeenCalledTimes(1)
    expect((fetchFn.mock.calls[0] as unknown as [string, RequestInit])[1].redirect).toBe('manual')
  })

  it('always sends the session cookie, whatever the plugin passes', async () => {
    const fetchFn = vi.fn(async () => ok())
    await api(null, fetchFn)('/x', { credentials: 'omit' })
    expect((fetchFn.mock.calls[0] as unknown as [string, RequestInit])[1].credentials).toBe('include')
  })

  it('keeps the Problem Details body of an error', async () => {
    const body = { detail: 'Há dados por corrigir.', code: 'VALIDATION_FAILED', errors: [{ field: 'nif', message: 'NIF inválido' }] }
    const error = await api(null, async () => new Response(JSON.stringify(body), {
      status: 422, headers: { 'Content-Type': 'application/problem+json' },
    }))('/x').catch((e: unknown) => e)
    // The server's text is in `problem.detail`; `message` stays technical, for logs.
    expect(error).toMatchObject({ status: 422, message: 'HTTP 422 on /x', problem: body })
  })

  it('ignores error bodies that are not Problem Details', async () => {
    const html = await api(null, async () => new Response('<h1>502</h1>', { status: 502, headers: { 'Content-Type': 'text/html' } }))('/x').catch((e: unknown) => e)
    expect((html as ApiError).problem).toBeUndefined()
    const broken = await api(null, async () => new Response('{', { status: 500, headers: { 'Content-Type': 'application/json' } }))('/x').catch((e: unknown) => e)
    expect((broken as ApiError).problem).toBeUndefined()
  })

  it('ignores a plain JSON error body with none of the Problem fields', async () => {
    const spring = { timestamp: '2026-11-19T10:00:00Z', status: 500, error: 'Internal Server Error', path: '/api/x' }
    const error = await api(null, async () => new Response(JSON.stringify(spring), {
      status: 500, headers: { 'Content-Type': 'application/json' },
    }))('/x').catch((e: unknown) => e)
    expect((error as ApiError).problem).toBeUndefined()
  })

  it('keeps only well-typed Problem fields', async () => {
    const body = { detail: 7, code: 'X', errors: [{ field: 'a', message: 'm' }, { field: 1 }, 'x'] }
    const error = await api(null, async () => new Response(JSON.stringify(body), {
      status: 400, headers: { 'Content-Type': 'application/problem+json' },
    }))('/x').catch((e: unknown) => e)
    expect((error as ApiError).problem).toEqual({ title: undefined, detail: undefined, code: 'X', errors: [{ field: 'a', message: 'm' }] })
  })
  it('treats an empty list of field errors as absent', async () => {
    const plain = await api(null, async () => new Response(JSON.stringify({ errors: [] }), {
      status: 400, headers: { 'Content-Type': 'application/json' },
    }))('/x').catch((e: unknown) => e)
    expect((plain as ApiError).problem).toBeUndefined()

    const malformed = await api(null, async () => new Response(JSON.stringify({ code: 'X', errors: [{ field: 1 }] }), {
      status: 400, headers: { 'Content-Type': 'application/problem+json' },
    }))('/x').catch((e: unknown) => e)
    expect((malformed as ApiError).problem?.errors).toBeUndefined()
  })
})

describe('reauthenticate', () => {
  const withOrganization: MeDto = {
    user: { id: 'u1', name: 'Rui' }, roles: ['responsible'], permissions: [],
    organizations: [{ id: '00000000-0000-4000-8000-00000000000a', slug: 'acme', name: 'Acme' }],
  }
  const stillRegistering: MeDto = { ...withOrganization, roles: ['registrant'], organizations: [] }

  const setup = (response: () => Response | Promise<Response>, me: MeDto = withOrganization) => {
    const fetchFn = vi.fn(async () => response())
    const reloadSession = vi.fn(async () => me)
    const navigate = vi.fn()
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    const reauthenticate = createReauthenticate({ reloadSession, navigate, fetchFn })
    return { fetchFn, reloadSession, navigate, go, reauthenticate }
  }

  afterEach(() => {
    vi.restoreAllMocks()
    reauthentication.forget()
  })

  it('refreshes the token with a POST to the BFF carrying the CSRF token', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123'
    try {
      const { fetchFn, reauthenticate } = setup(() => new Response(null, { status: 204 }))
      await reauthenticate('/')
      const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
      expect(url).toBe(REFRESH_PATH)
      expect(init.method).toBe('POST')
      expect(init.credentials).toBe('include')
      expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('csrf-123')
    } finally {
      document.cookie = 'XSRF-TOKEN=; Max-Age=0'
    }
  })

  it('reloads the session and goes to the given page, without signing in again', async () => {
    const { reloadSession, navigate, go, reauthenticate } = setup(() => new Response(null, { status: 204 }))
    await reauthenticate('/org/acme')
    expect(reloadSession).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledWith('/org/acme')
    expect(go).not.toHaveBeenCalled()
    expect(reauthentication.recent()).toBe(false)
  })

  it('stays on the page when no return address is given', async () => {
    const { reloadSession, navigate, reauthenticate } = setup(() => new Response(null, { status: 204 }))
    await reauthenticate()
    expect(reloadSession).toHaveBeenCalledOnce()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('remembers the refresh when the new session still has no organization', async () => {
    const { reauthenticate } = setup(() => new Response(null, { status: 204 }), stillRegistering)
    await reauthenticate('/')
    expect(reauthentication.recent()).toBe(true)
  })

  it('signs in again only when the session can no longer be refreshed', async () => {
    const { reloadSession, go, reauthenticate } = setup(() => new Response(null, { status: 401 }))
    await reauthenticate('/org/acme')
    expect(go).toHaveBeenCalledWith('/org/acme')
    expect(reloadSession).not.toHaveBeenCalled()
    expect(reauthentication.recent()).toBe(true)
  })

  it('signs in again and comes back to the current page when no return address is given', async () => {
    const { go, reauthenticate } = setup(() => new Response(null, { status: 401 }))
    await reauthenticate()
    expect(go).toHaveBeenCalledWith(undefined)
  })

  it('rejects on any other failure, so the page can show it, and never signs in again', async () => {
    const { go, reloadSession, reauthenticate } = setup(() => new Response(null, { status: 403 }))
    await expect(reauthenticate('/')).rejects.toMatchObject({ status: 403, path: REFRESH_PATH })
    expect(go).not.toHaveBeenCalled()
    expect(reloadSession).not.toHaveBeenCalled()
  })

  it('rejects with a NetworkError when the BFF cannot be reached', async () => {
    const { reauthenticate } = setup(() => { throw new TypeError('Failed to fetch') })
    await expect(reauthenticate('/')).rejects.toBeInstanceOf(NetworkError)
  })
})
