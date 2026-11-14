import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Outlet, useNavigate } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import type { MeDto } from '@alinha/api-client'
import { ApiError } from '@alinha/plugin-sdk'
import { ErrorPage, LoadingPage, LoginFailedPage } from '../pages/StatusPages'
import { login } from './login'
import { fetchMe } from './me'
import { isConsultant, makeCan, type Can } from './permissions'

export interface Session {
  me: MeDto
  can: Can
  isConsultant: boolean
}

const SessionContext = createContext<Session | null>(null)

export function useSession(): Session {
  const session = useContext(SessionContext)
  if (!session) throw new Error('useSession() called outside SessionGate')
  return session
}

export const ME_QUERY_KEY = ['session', 'me'] as const

const isUnauthenticated = (error: unknown) => error instanceof ApiError && error.status === 401

/** Sends the browser to the login page once, unless it has just been there (no redirect loop). */
function GoToLogin() {
  const [loopDetected] = useState(() => login.recentlyAttempted())
  useEffect(() => {
    if (!loopDetected) login.go()
  }, [loopDetected])
  if (loopDetected) return <LoginFailedPage onRetry={() => { login.reset(); login.go() }} />
  return <LoadingPage label="A abrir a página de entrada…" />
}

function SessionReady({ me, children }: { me: MeDto; children: ReactNode }) {
  const navigate = useNavigate()
  // Once per session: go back to the page the person was opening before the login, then forget it,
  // so later navigation is never pulled back there. (Child effects run first, so this wins over "/" → home.)
  useEffect(() => {
    const returnTo = login.returnPath()
    login.succeeded()
    if (returnTo) navigate(returnTo, { replace: true })
  }, [navigate])
  const session = useMemo<Session>(() => ({ me, can: makeCan(me.permissions), isConsultant: isConsultant(me.roles) }), [me])
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
}

/** Root route element: loads the session from /api/me before showing any page. */
export function SessionGate({ children = <Outlet /> }: { children?: ReactNode }) {
  const me = useQuery({ queryKey: ME_QUERY_KEY, queryFn: () => fetchMe(), retry: false, staleTime: Infinity })
  if (me.isPending) return <LoadingPage label="A preparar a sua sessão…" />
  if (me.isError) {
    if (isUnauthenticated(me.error)) return <GoToLogin />
    return <ErrorPage onRetry={() => me.refetch()} />
  }
  return <SessionReady me={me.data}>{children}</SessionReady>
}
