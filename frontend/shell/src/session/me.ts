import type { MeDto } from '@alinha/api-client'
import { ApiError } from '@alinha/plugin-sdk'

const isStringArray = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string')

function isMe(v: unknown): v is MeDto {
  const me = v as MeDto | null
  return !!me && typeof me.user?.id === 'string' && typeof me.user.name === 'string'
    && isStringArray(me.roles) && isStringArray(me.permissions)
    && Array.isArray(me.organizations)
    && me.organizations.every((o) => typeof o?.id === 'string' && typeof o.slug === 'string' && typeof o.name === 'string')
}

/** GET /api/me. Throws ApiError(401) when there is no session. */
export async function fetchMe(fetchFn: typeof fetch = fetch): Promise<MeDto> {
  const res = await fetchFn('/api/me', { credentials: 'include', headers: { Accept: 'application/json' } })
  if (!res.ok) throw new ApiError(res.status, '/me')
  const body: unknown = await res.json()
  if (!isMe(body)) throw new Error('/api/me returned an unexpected response')
  return body
}
