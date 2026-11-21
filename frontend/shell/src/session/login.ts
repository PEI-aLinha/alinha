/**
 * Sends the browser to the BFF login (OIDC with Keycloak, issue #14) and remembers where to come back.
 * A second 401 right after a login attempt shows an error instead of redirecting again, so a broken
 * session never turns into a redirect loop.
 */
export const LOGIN_PATH = '/oauth2/authorization/keycloak'

const ATTEMPT_KEY = 'alinha.login-attempt'
const RETURN_KEY = 'alinha.return-to'
const ATTEMPT_WINDOW_MS = 60_000

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

/** Only same-origin paths ("/x", never "//host" or "https://...") are kept as a return address. */
export const isSafeReturnPath = (path: string | null): path is string =>
  !!path && path.startsWith('/') && !path.startsWith('//') && !path.startsWith('/\\')

export const login = {
  /** True when the last login attempt was less than a minute ago. */
  recentlyAttempted(now: number = Date.now()): boolean {
    const at = Number(storage()?.getItem(ATTEMPT_KEY))
    return Number.isFinite(at) && at > 0 && now - at < ATTEMPT_WINDOW_MS
  },

  go(returnTo: string = window.location.pathname + window.location.search + window.location.hash): void {
    const s = storage()
    s?.setItem(ATTEMPT_KEY, String(Date.now()))
    // Home or an unsafe address: drop any address an earlier attempt saved, so it cannot win after this login.
    if (isSafeReturnPath(returnTo) && returnTo !== '/') s?.setItem(RETURN_KEY, returnTo)
    else s?.removeItem(RETURN_KEY)
    window.location.assign(LOGIN_PATH)
  },

  /** Address saved before the login, if any. Read-only, so it is safe to call during render. */
  returnPath(): string | null {
    const returnTo = storage()?.getItem(RETURN_KEY) ?? null
    return isSafeReturnPath(returnTo) ? returnTo : null
  },

  /** Called once the session is active: forgets the attempt and the saved address. */
  succeeded(): void {
    const s = storage()
    s?.removeItem(ATTEMPT_KEY)
    s?.removeItem(RETURN_KEY)
  },

  reset(): void {
    storage()?.removeItem(ATTEMPT_KEY)
  },
}

const REAUTH_KEY = 'alinha.reauthenticated-at'
/** How long after a re-authentication an unchanged session still counts as "not updated yet". */
const REAUTH_WINDOW_MS = 5 * 60_000

const reauthListeners = new Set<() => void>()

/**
 * Remembers that the page asked for a fresh token (`host.reauthenticate`) and the session still had no organization,
 * so the onboarding can tell "the new organization is not in the token yet" from a first visit. Components follow it
 * with `useSyncExternalStore(reauthentication.subscribe, reauthentication.recent)`.
 */
export const reauthentication = {
  started(now: number = Date.now()): void {
    storage()?.setItem(REAUTH_KEY, String(now))
    reauthListeners.forEach((listener) => listener())
  },

  /** True when a re-authentication started in the last few minutes. Read-only, so it is safe to call during render. */
  recent(now: number = Date.now()): boolean {
    const at = Number(storage()?.getItem(REAUTH_KEY))
    return Number.isFinite(at) && at > 0 && now - at < REAUTH_WINDOW_MS
  },

  forget(): void {
    storage()?.removeItem(REAUTH_KEY)
    reauthListeners.forEach((listener) => listener())
  },

  subscribe(listener: () => void): () => void {
    reauthListeners.add(listener)
    return () => reauthListeners.delete(listener)
  },
}
