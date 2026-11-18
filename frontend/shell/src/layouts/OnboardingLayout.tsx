import { Suspense } from 'react'
import { Navigate, Outlet } from 'react-router'
import { HostProvider } from '../host'
import { LoadingPage } from '../pages/StatusPages'
import { useSession } from '../session/SessionGate'
import { needsOnboarding } from '../session/permissions'

/**
 * /onboarding/<path> — full page, no menu and no organization, for a new account setting its organization up.
 * Anyone else goes back to "/", so the onboarding is only shown until the organization is registered.
 */
export function OnboardingLayout() {
  const { me } = useSession()
  if (!needsOnboarding(me.roles, me.organizations)) return <Navigate to="/" replace />
  return (
    <HostProvider organization={null}>
      <div className="flex min-h-screen flex-col">
        <a href="#conteudo" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:rounded-md focus:bg-white focus:px-4 focus:py-2">
          Saltar para o conteúdo
        </a>
        <header className="flex items-center justify-between border-b px-6 py-4">
          <span className="text-lg font-semibold">aLinha</span>
          <span className="text-sm text-gray-600">{me.user.name}</span>
        </header>
        <main id="conteudo" className="mx-auto w-full max-w-3xl flex-1 px-4 py-8 sm:px-6">
          <Suspense fallback={<LoadingPage />}><Outlet /></Suspense>
        </main>
      </div>
    </HostProvider>
  )
}
