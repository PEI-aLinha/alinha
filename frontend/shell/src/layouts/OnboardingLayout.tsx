import { Suspense, useEffect, useState } from 'react'
import { Navigate, Outlet } from 'react-router'
import { HostProvider, reauthenticate } from '../host'
import { LoadingPage, StatusPage } from '../pages/StatusPages'
import { reauthentication } from '../session/login'
import { useSession } from '../session/SessionGate'
import { needsOnboarding } from '../session/permissions'

/**
 * Shown instead of the onboarding page when the account signed in again (after registering) but the token still has
 * no organization, e.g. the identity provider had not caught up yet. Showing the form again would only lead to a
 * second registration being refused.
 */
function SessionNotUpdatedPage() {
  return (
    <StatusPage
      title="Quase pronto"
      actions={(
        <button type="button" className="rounded-md border px-4 py-2 font-medium hover:bg-gray-50" onClick={() => reauthenticate('/')}>
          Entrar novamente
        </button>
      )}
    >
      O registo da sua organização foi enviado, mas a sua sessão ainda não o mostra. Aguarde alguns segundos e entre novamente.
    </StatusPage>
  )
}

/**
 * /onboarding/<path> — full page, no menu and no organization, for a new account setting its organization up.
 * Anyone else goes back to "/", so the onboarding is only shown until the organization is registered.
 */
export function OnboardingLayout() {
  const { me } = useSession()
  const onboarding = needsOnboarding(me.roles, me.organizations)
  // Read once per visit: a later visit (e.g. after a reload) shows the page again.
  const [notUpdated] = useState(() => onboarding && reauthentication.recent())
  useEffect(() => reauthentication.forget(), [])
  if (!onboarding) return <Navigate to="/" replace />
  return (
    <HostProvider organization={null}>
      <div className="flex min-h-screen flex-col">
        <a href="#conteudo" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:rounded-md focus:bg-white focus:px-4 focus:py-2">
          Saltar para o conteúdo
        </a>
        <header className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <span className="text-lg font-semibold">aLinha</span>
          <span className="text-sm text-gray-600">{me.user.name}</span>
        </header>
        <main id="conteudo" className="mx-auto w-full max-w-3xl flex-1 px-4 py-8 sm:px-6">
          {notUpdated ? <SessionNotUpdatedPage /> : <Suspense fallback={<LoadingPage />}><Outlet /></Suspense>}
        </main>
      </div>
    </HostProvider>
  )
}
