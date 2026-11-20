import { describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError } from '@alinha/plugin-sdk'
import { createApi, reauthenticate, retryUnlessClientError, TENANT_HEADER } from './host'
import { login } from './session/login'
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
})

describe('reauthenticate', () => {
  it('signs in again and comes back to the given page', () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    reauthenticate('/org/acme')
    expect(go).toHaveBeenCalledWith('/org/acme')
    go.mockRestore()
  })
})
