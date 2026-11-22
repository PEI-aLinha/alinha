import { Suspense, useState, useSyncExternalStore } from 'react'
import { Navigate, Outlet } from 'react-router'
import { errorMessage, useHost } from '@alinha/plugin-sdk'
import { Avatar, buttonClass, initialsOf, Logo } from '@alinha/ui'
import { AppearanceMenu } from '../appearance/AppearanceMenu'
import { HostProvider } from '../host'
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
  const { reauthenticate } = useHost()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const retry = async () => {
    setPending(true)
    setError(null)
    try {
      await reauthenticate('/')
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setPending(false)
    }
  }
  return (
    <StatusPage
      title="Quase pronto"
      actions={(
        <button type="button" className={buttonClass({ variant: 'secondary' })} disabled={pending} onClick={() => void retry()}>
          {pending ? 'A verificar…' : 'Verificar novamente'}
        </button>
      )}
    >
      O registo da sua organização foi enviado, mas a sua sessão ainda não o mostra. Aguarde alguns segundos e verifique novamente.
      {error && <span role="alert" className="mt-2 block">{error}</span>}
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
  const reauthenticated = useSyncExternalStore(reauthentication.subscribe, () => reauthentication.recent())
  const notUpdated = onboarding && reauthenticated
  // Kept across reloads until the session has an organization (SessionGate forgets it then) or the window ends:
  // showing the form again would only lead to a second registration being refused.
  if (!onboarding) return <Navigate to="/" replace />
  return (
    <HostProvider organization={null}>
      <div className="flex min-h-dvh flex-col bg-paper-soft">
        <a href="#conteudo" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded-[10px] focus:bg-brand focus:px-4 focus:py-2 focus:text-white">
          Saltar para o conteúdo
        </a>
        <header className="sticky top-0 z-30 flex h-16 items-center justify-between border-b border-line-soft bg-paper/95 px-8 backdrop-blur max-md:h-14 max-md:px-5">
          <Logo className="h-8 max-md:h-7" />
          <div className="flex items-center gap-3">
            <AppearanceMenu compact />
            <span className="text-[0.8125rem] font-bold max-sm:hidden">{me.user.name}</span>
            <Avatar initials={initialsOf(me.user.name)} size={28} />
          </div>
        </header>
        <main id="conteudo" tabIndex={-1} className="mx-auto w-full max-w-3xl flex-1 px-5 pb-16 pt-10 outline-none sm:px-8">
          {notUpdated ? <SessionNotUpdatedPage /> : <Suspense fallback={<LoadingPage />}><Outlet /></Suspense>}
        </main>
      </div>
    </HostProvider>
  )
}
