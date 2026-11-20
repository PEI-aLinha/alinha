import { ApiError, type Problem } from '@alinha/plugin-sdk'

/**
 * Options for every call to /api. Redirects are not followed: a redirect means the BFF wants a login
 * (e.g. Spring Security answering 302 instead of 401), and following it would only fetch the login page's HTML.
 */
export const API_REQUEST: RequestInit = { credentials: 'include', redirect: 'manual' }

/**
 * Spring's CSRF cookie is double-submit: the BFF sets `XSRF-TOKEN`, the browser must echo it in
 * `X-XSRF-TOKEN`. The cookie is readable on purpose; the session cookie stays HttpOnly.
 */
export function withCsrf(headers: Headers): Headers {
  if (typeof document === 'undefined') return headers
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  if (match) headers.set('X-XSRF-TOKEN', decodeURIComponent(match[1]))
  return headers
}

/** True when the response says there is no session: a 401, or a redirect (to the login). */
export const isUnauthenticatedResponse = (res: Response) => res.status === 401 || res.type === 'opaqueredirect'

/** The response was 2xx but its body is not JSON (e.g. an HTML page from a proxy). Asking again will not help. */
export class NotJsonError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`/api${path} did not return JSON`)
    this.path = path
    this.name = 'NotJsonError'
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const optionalString = (v: unknown) => (typeof v === 'string' ? v : undefined)

/**
 * The Problem Details body of an error response (ADR 0003), or undefined when there is none or it is not one.
 * Only the known fields are kept, and only with the expected types: the body is untrusted input. A plain JSON body
 * (e.g. Spring's default `{timestamp,status,error,path}` or a gateway error) counts only if it has one of them.
 */
export async function readProblem(res: Response): Promise<Problem | undefined> {
  const type = res.headers.get('Content-Type') ?? ''
  if (!/^application\/(problem\+)?json\b/i.test(type)) return undefined
  let body: unknown
  try {
    body = JSON.parse(await res.text())
  } catch {
    return undefined
  }
  if (!isRecord(body)) return undefined
  const errors = Array.isArray(body.errors)
    ? body.errors.filter(isRecord).flatMap((e) =>
        typeof e.field === 'string' && typeof e.message === 'string' ? [{ field: e.field, message: e.message }] : [])
    : undefined
  const problem: Problem = { title: optionalString(body.title), detail: optionalString(body.detail), code: optionalString(body.code), errors }
  const isProblemType = /^application\/problem\+json\b/i.test(type)
  const hasKnownField = Object.values(problem).some((v) => v !== undefined)
  return isProblemType || hasKnownField ? problem : undefined
}

/**
 * Reads a /api response: ApiError for anything that is not 2xx (401 for a redirect, with the Problem Details body
 * when there is one), undefined for an empty body (204, Content-Length 0 or chunked), parsed JSON otherwise, and a
 * clear error when the body is not JSON.
 */
export async function readJson<T>(res: Response, path: string): Promise<T> {
  if (isUnauthenticatedResponse(res)) throw new ApiError(401, path)
  if (!res.ok) throw new ApiError(res.status, path, undefined, await readProblem(res))
  const text = await res.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    throw new NotJsonError(path)
  }
}
