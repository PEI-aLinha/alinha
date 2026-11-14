import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@alinha/plugin-sdk'
import { fetchMe } from './me'

const me = {
  user: { id: 'u1', name: 'Rui Santos' },
  roles: ['responsible'],
  permissions: ['asset:read'],
  organizations: [{ id: 't1', slug: 'acme', name: 'Acme' }],
}
const respond = (status: number, body: unknown = {}) => vi.fn(async () => new Response(JSON.stringify(body), { status }))

describe('fetchMe', () => {
  it('reads /api/me with the session cookie', async () => {
    const fetchFn = respond(200, me)
    await expect(fetchMe(fetchFn)).resolves.toEqual(me)
    expect(fetchFn).toHaveBeenCalledWith('/api/me', expect.objectContaining({ credentials: 'include' }))
  })

  it('throws ApiError(401) without a session', async () => {
    await expect(fetchMe(respond(401))).rejects.toMatchObject({ name: 'ApiError', status: 401 })
  })

  it('throws ApiError with the status on other failures', async () => {
    const error = await fetchMe(respond(503)).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(503)
  })

  it('rejects a response that is not a session', async () => {
    await expect(fetchMe(respond(200, { user: { id: 'u1' } }))).rejects.toThrow('unexpected response')
    await expect(fetchMe(respond(200, { ...me, organizations: [{ id: 't1' }] }))).rejects.toThrow('unexpected response')
  })
})
