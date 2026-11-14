import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
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
  const { pathname } = useLocation()
  // After the login, go back to the page the person was trying to open.
  const [returnTo] = useState(() => login.returnPath())
  useEffect(() => login.succeeded(), [])
  const session = useMemo<Session>(() => ({ me, can: makeCan(me.permissions), isConsultant: isConsultant(me.roles) }), [me])
  return (
    <SessionContext.Provider value={session}>
      {returnTo && returnTo !== pathname ? <Navigate to={returnTo} replace /> : children}
    </SessionContext.Provider>
  )
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
