import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@alinha/plugin-sdk'
import { createApi, TENANT_HEADER } from './host'

const ok = (body: unknown = {}, init: ResponseInit = {}) => new Response(JSON.stringify(body), { status: 200, ...init })

describe('createApi', () => {
  it('calls /api with the session cookie, JSON and the organization', async () => {
    const fetchFn = vi.fn(async () => ok({ a: 1 }))
    const api = createApi({ tenantId: 't-1', fetchFn })
    await expect(api('/assets')).resolves.toEqual({ a: 1 })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(url).toBe('/api/assets')
    expect(init.credentials).toBe('include')
    expect(headers.get(TENANT_HEADER)).toBe('t-1')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('adds the leading slash and keeps the caller options', async () => {
    const fetchFn = vi.fn(async () => ok())
    await createApi({ tenantId: null, fetchFn })('assets', { method: 'POST', headers: { Accept: 'text/csv' } })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/assets')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Accept')).toBe('text/csv')
  })

  it('never lets a plugin choose the organization itself', async () => {
    const fetchFn = vi.fn(async () => ok())
    await createApi({ tenantId: 't-1', fetchFn })('/x', { headers: { [TENANT_HEADER]: 'other' } })
    await createApi({ tenantId: null, fetchFn })('/x', { headers: { [TENANT_HEADER]: 'other' } })
    const sent = fetchFn.mock.calls.map((c) => new Headers((c as unknown as [string, RequestInit])[1].headers).get(TENANT_HEADER))
    expect(sent).toEqual(['t-1', null])
  })

  it('throws ApiError with the status', async () => {
    const api = createApi({ tenantId: null, fetchFn: async () => new Response('', { status: 403 }) })
    const error = await api('/x').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 403, path: '/x' })
  })

  it('sends the user to the login on 401', async () => {
    const onUnauthenticated = vi.fn()
    const api = createApi({ tenantId: null, onUnauthenticated, fetchFn: async () => new Response('', { status: 401 }) })
    await expect(api('/x')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('returns undefined for empty responses', async () => {
    await expect(createApi({ tenantId: null, fetchFn: async () => new Response(null, { status: 204 }) })('/x')).resolves.toBeUndefined()
    await expect(createApi({ tenantId: null, fetchFn: async () => new Response('', { status: 200, headers: { 'Content-Length': '0' } }) })('/x')).resolves.toBeUndefined()
  })
})
