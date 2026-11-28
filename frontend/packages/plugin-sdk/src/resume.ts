/**
 * Coming back from an outside page that can only return to one fixed address (e.g. a GitHub App's Callback URL).
 * Before leaving, the plugin remembers its own page; the outside page returns to `/resume?<its parameters>` and the
 * shell sends the person back to that page with the same query string. Kept in sessionStorage (this tab only), for
 * [RESUME_MAX_AGE_MS], and used once. Only pages inside an organization are accepted, so it is never an open redirect.
 */
export const RESUME_PATH = '/resume'
export const RESUME_MAX_AGE_MS = 30 * 60 * 1000
const KEY = 'alinha:resume'
const ALLOWED = /^\/(org|portfolio)\/[A-Za-z0-9._~%-]+(\/[A-Za-z0-9._~%-]+)*$/

/**
 * A page inside an organization: allowed characters only, and no segment that is (or decodes to) `.` or `..`, or
 * hides a `/` or a backslash behind percent-encoding, so a router that decodes the path cannot leave the organization.
 */
function isOrganizationPage(path: string): boolean {
  if (!ALLOWED.test(path)) return false
  return path.split('/').slice(1).every((segment) => {
    let decoded: string
    try {
      decoded = decodeURIComponent(segment)
    } catch {
      return false // malformed percent-encoding
    }
    return decoded !== '.' && decoded !== '..' && !/[/\\]/.test(decoded)
  })
}

interface Stored {
  path: string
  at: number
}

/** Remembers [path] (e.g. `/org/acme/integrations/code-repository`, no query) as the page to come back to. */
export function rememberReturnPath(path: string, now: number = Date.now()): void {
  if (!isOrganizationPage(path)) throw new Error(`Not a page inside an organization: ${path}`)
  sessionStorage.setItem(KEY, JSON.stringify({ path, at: now } satisfies Stored))
}

/** The remembered page, once: null when there is none, it expired or it is not a page inside an organization. */
export function takeReturnPath(now: number = Date.now()): string | null {
  const raw = sessionStorage.getItem(KEY)
  sessionStorage.removeItem(KEY)
  if (!raw) return null
  try {
    const { path, at } = JSON.parse(raw) as Partial<Stored>
    const fresh = typeof at === 'number' && now - at >= 0 && now - at <= RESUME_MAX_AGE_MS
    return typeof path === 'string' && fresh && isOrganizationPage(path) ? path : null
  } catch {
    return null
  }
}
