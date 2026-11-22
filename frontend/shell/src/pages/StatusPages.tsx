import type { ReactNode } from 'react'
import { isRouteErrorResponse, Link, useRouteError } from 'react-router'
import { ApiError } from '@alinha/plugin-sdk'
import { buttonClass, Skeleton } from '@alinha/ui'

/** Full-area message: what happened and what the person can do next (Portuguese, plain language). */
export function StatusPage({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <section aria-labelledby="status-title" className="mx-auto flex w-full max-w-lg flex-col items-start gap-3 px-5 py-16">
      <h1 id="status-title" className="title-tight text-[1.875rem] font-extrabold">{title}</h1>
      {children && <p className="text-[0.9375rem] text-ink-soft">{children}</p>}
      {actions && <div className="mt-3 flex flex-wrap gap-3">{actions}</div>}
    </section>
  )
}

const linkClass = buttonClass({ variant: 'secondary' })

export const HomeLink = () => <Link to="/" className={linkClass}>Voltar ao início</Link>

export function LoadingPage({ label = 'A carregar…' }: { label?: string }) {
  return (
    <div className="mx-auto flex w-full max-w-[1152px] flex-col gap-4 px-5 py-11">
      <p role="status" aria-live="polite" className="text-[0.875rem] text-ink-soft">{label}</p>
      <Skeleton className="h-8 w-2/5" />
      <Skeleton className="h-4 w-3/5" />
      <Skeleton className="mt-4 h-32 w-full" />
    </div>
  )
}

export function ForbiddenPage({ title = 'Não tem acesso a esta página', children }: { title?: string; children?: ReactNode }) {
  return (
    <StatusPage title={title} actions={<HomeLink />}>
      {children ?? 'O aLinha mostra a cada pessoa só o que precisa para o seu trabalho. Se acha que devia ter acesso, fale com o responsável da sua organização.'}
    </StatusPage>
  )
}

export function NotFoundPage() {
  return (
    <StatusPage title="Página não encontrada" actions={<HomeLink />}>
      O endereço pode estar errado ou a página pode ter mudado de sítio.
    </StatusPage>
  )
}

export function ErrorPage({ onRetry }: { onRetry?: () => void }) {
  return (
    <StatusPage
      title="Algo correu mal"
      actions={<>
        <button type="button" className={linkClass} onClick={onRetry ?? (() => window.location.reload())}>Tentar outra vez</button>
        <HomeLink />
      </>}
    >
      Não foi possível mostrar esta página. Tente outra vez. Se o problema continuar, contacte o suporte.
    </StatusPage>
  )
}

export function LoginFailedPage({ onRetry }: { onRetry: () => void }) {
  return (
    <StatusPage
      title="Não foi possível iniciar sessão"
      actions={<button type="button" className={linkClass} onClick={onRetry}>Tentar outra vez</button>}
    >
      Abrimos a página de entrada, mas a sessão não ficou ativa. Tente outra vez daqui a pouco. Se o problema continuar, contacte o suporte.
    </StatusPage>
  )
}

/** `errorElement` of the routes: 404 and 403 get their own page, anything else the generic error. */
export function RouteErrorPage() {
  const error = useRouteError()
  const status = isRouteErrorResponse(error) ? error.status : error instanceof ApiError ? error.status : undefined
  if (status === 404) return <NotFoundPage />
  if (status === 403) return <ForbiddenPage />
  return <ErrorPage />
}
