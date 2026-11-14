import { ApiError } from '@alinha/plugin-sdk'

/**
 * Options for every call to /api. Redirects are not followed: a redirect means the BFF wants a login
 * (e.g. Spring Security answering 302 instead of 401), and following it would only fetch the login page's HTML.
 */
export const API_REQUEST: RequestInit = { credentials: 'include', redirect: 'manual' }

/** True when the response says there is no session: a 401, or a redirect (to the login). */
export const isUnauthenticatedResponse = (res: Response) => res.status === 401 || res.type === 'opaqueredirect'

/**
 * Reads a /api response: ApiError for anything that is not 2xx (401 for a redirect), undefined for an empty body
 * (204, Content-Length 0 or chunked), parsed JSON otherwise, and a clear error when the body is not JSON.
 */
export async function readJson<T>(res: Response, path: string): Promise<T> {
  if (isUnauthenticatedResponse(res)) throw new ApiError(401, path)
  if (!res.ok) throw new ApiError(res.status, path)
  const text = await res.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    throw new Error(`/api${path} did not return JSON`)
  }
}
