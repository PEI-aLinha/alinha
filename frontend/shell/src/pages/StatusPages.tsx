import type { ReactNode } from 'react'
import { isRouteErrorResponse, Link, useRouteError } from 'react-router'
import { ApiError } from '@alinha/plugin-sdk'

/** Full-area message: what happened and what the person can do next (Portuguese, plain language). */
export function StatusPage({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <section aria-labelledby="status-title" className="mx-auto flex max-w-lg flex-col items-start gap-3 py-16">
      <h1 id="status-title" className="text-2xl font-semibold">{title}</h1>
      {children && <p className="text-gray-700">{children}</p>}
      {actions && <div className="mt-2 flex flex-wrap gap-3">{actions}</div>}
    </section>
  )
}

const linkClass = 'rounded-md border px-4 py-2 font-medium hover:bg-gray-50'

export const HomeLink = () => <Link to="/" className={linkClass}>Voltar ao início</Link>

export function LoadingPage({ label = 'A carregar…' }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="flex min-h-[40vh] items-center justify-center text-gray-600">
      {label}
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
