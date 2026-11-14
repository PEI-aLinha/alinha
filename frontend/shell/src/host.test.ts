import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@alinha/plugin-sdk'
import { createApi, retryUnlessClientError, TENANT_HEADER } from './host'

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
})
