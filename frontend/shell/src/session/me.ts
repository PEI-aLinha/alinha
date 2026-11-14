import type { MeDto } from '@alinha/api-client'
import { API_REQUEST, readJson } from '../http'

const isStringArray = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string')

function isMe(v: unknown): v is MeDto {
  const me = v as MeDto | null
  return !!me && typeof me.user?.id === 'string' && typeof me.user.name === 'string'
    && isStringArray(me.roles) && isStringArray(me.permissions)
    && Array.isArray(me.organizations)
    && me.organizations.every((o) => typeof o?.id === 'string' && typeof o.slug === 'string' && typeof o.name === 'string')
}

/** GET /api/me. Throws ApiError(401) when there is no session (a 401, or a redirect to the login). */
export async function fetchMe(fetchFn: typeof fetch = fetch): Promise<MeDto> {
  const res = await fetchFn('/api/me', { ...API_REQUEST, headers: { Accept: 'application/json' } })
  const body = await readJson<unknown>(res, '/me')
  if (!isMe(body)) throw new Error('/api/me returned an unexpected response')
  return body
}
