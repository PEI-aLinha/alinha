import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, definePlugin, useHost } from '@alinha/plugin-sdk'
import { login } from './session/login'
import { ACME, BETA, json, meFor, renderApp } from './test-utils'

function TenantProbe() {
  const host = useHost()
  const probe = useQuery({ queryKey: ['probe'], queryFn: () => host.api<{ value: string }>('/probe') })
  return <p>Dados de {host.organization?.name}: {probe.data?.value ?? 'a carregar'}</p>
}

/** Two calls at once, like a page with several queries. */
function TwoCalls() {
  const host = useHost()
  const a = useQuery({ queryKey: ['a'], queryFn: () => host.api('/a') })
  const b = useQuery({ queryKey: ['b'], queryFn: () => host.api('/b') })
  return <p>{a.isError && b.isError ? 'Pedidos recusados' : 'A pedir'}</p>
}

/** A page with local state, like a half-filled form. */
function Draft() {
  const [text, setText] = useState('')
  return <input aria-label="Rascunho" value={text} onChange={(e) => setText(e.target.value)} />
}

const Boom = () => { throw new Error('boom') }
const Denied = () => { throw new ApiError(403, '/x') }

const testPlugin = definePlugin({
  id: 'test',
  name: 'Test',
  routes: {
    client: [
      { path: 'ativos', handle: { title: 'Ativos' }, Component: () => <h1>Página de ativos</h1> },
      { path: 'riscos', handle: { title: 'Riscos', resource: 'risk' }, Component: () => <h1>Página de riscos</h1> },
      { path: 'falha', Component: Boom },
      { path: 'negado', Component: Denied },
      { path: 'sonda', Component: TenantProbe },
      { path: 'dois', Component: TwoCalls },
      { path: 'preguicoso', handle: { resource: 'risk' }, lazy: async () => ({ Component: () => <h1>Página carregada à parte</h1> }) },
    ],
    consultant: [
      { path: 'painel', Component: () => <h1>Painel do cliente</h1> },
      { path: 'sonda', Component: TenantProbe },
      { path: 'rascunho', Component: Draft },
    ],
  },
  nav: [
    { label: 'Riscos', path: 'riscos', area: 'client', resource: 'risk', order: 2 },
    { label: 'Ativos', path: 'ativos', area: 'client', order: 1 },
    { label: 'Painel', path: 'painel', area: 'consultant' },
  ],
})
const plugins = [testPlugin]

const nav = () => within(screen.getByRole('navigation', { name: 'Navegação principal' }))

beforeEach(() => {
  sessionStorage.clear()
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('session bootstrap', () => {
  it('shows a loading message while /api/me answers', () => {
    renderApp({ url: '/', plugins, me: () => new Promise(() => {}) })
    expect(screen.getByRole('status')).toHaveTextContent('A preparar a sua sessão…')
  })

  it('sends the browser to the login page when there is no session', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    renderApp({ url: '/org/acme/ativos', plugins, me: 401 })
    expect(await screen.findByText('A abrir a página de entrada…')).toBeInTheDocument()
    expect(go).toHaveBeenCalledTimes(1)
  })

  it('shows an error instead of looping when the login did not create a session', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    vi.spyOn(login, 'recentlyAttempted').mockReturnValue(true)
    renderApp({ url: '/', plugins, me: 401 })
    expect(await screen.findByRole('heading', { name: 'Não foi possível iniciar sessão' })).toBeInTheDocument()
    expect(go).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Tentar outra vez' }))
    expect(go).toHaveBeenCalledTimes(1)
  })

  it('offers a retry when /api/me fails for another reason', async () => {
    let fail = true
    renderApp({ url: '/', plugins, me: async () => (fail ? json({}, 503) : json(meFor('responsible'))) })
    expect(await screen.findByRole('heading', { name: 'Algo correu mal' })).toBeInTheDocument()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Tentar outra vez' }))
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
  })

  it('goes back to the page saved before the login', async () => {
    sessionStorage.setItem('alinha.return-to', '/org/acme/sonda')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/sonda'))
    expect(sessionStorage.getItem('alinha.return-to')).toBeNull()
  })

  it('lets the person move on after going back to the saved page', async () => {
    sessionStorage.setItem('alinha.return-to', '/org/acme/sonda')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/sonda'))
    fireEvent.click(nav().getByRole('link', { name: 'Ativos' }))
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(router.state.location.pathname).toBe('/org/acme/ativos')
  })

  it('goes back to a saved page with a query string, once', async () => {
    sessionStorage.setItem('alinha.return-to', '/org/acme/sonda?filtro=abertos')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.search).toBe('?filtro=abertos'))
    await router.navigate('/org/acme/ativos')
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(router.state.location.pathname).toBe('/org/acme/ativos')
  })

  it('explains when the account has no organization yet', async () => {
    renderApp({ url: '/', plugins, me: meFor(null, { organizations: [] }) })
    expect(await screen.findByRole('heading', { name: 'A sua conta ainda não está pronta' })).toBeInTheDocument()
  })
})

describe('client area (/org/:org)', () => {
  it('opens the user organization and its first page', async () => {
    const { router } = renderApp({ url: '/', plugins })
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/org/acme/ativos')
    expect(screen.getByText('Rui Santos')).toBeInTheDocument()
    expect(screen.getByText('Responsável')).toBeInTheDocument()
  })

  it('shows only the menu entries the role may see, in order', async () => {
    renderApp({ url: '/org/acme/ativos', plugins, me: meFor('responsible', { permissions: ['risk:read'] }) })
    await screen.findByRole('heading', { name: 'Página de ativos' })
    expect(nav().getAllByRole('link').map((l) => l.textContent)).toEqual(['Ativos', 'Riscos'])
    expect(nav().getByRole('link', { name: 'Riscos' })).toHaveAttribute('href', '/org/acme/riscos')
  })

  it('hides entries and blocks pages without the permission', async () => {
    renderApp({ url: '/org/acme/riscos', plugins, me: meFor('analyst') })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Riscos' })).not.toBeInTheDocument()
    expect(screen.getByText('Analista')).toBeInTheDocument()
  })

  it('guards a lazy plugin route and still loads it with the permission', async () => {
    renderApp({ url: '/org/acme/preguicoso', plugins, me: meFor('responsible', { permissions: ['risk:read'] }) })
    expect(await screen.findByRole('heading', { name: 'Página carregada à parte' })).toBeInTheDocument()
  })

  it('refuses a lazy plugin route without the permission', async () => {
    renderApp({ url: '/org/acme/preguicoso', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Página carregada à parte' })).not.toBeInTheDocument()
  })

  it('refuses another organization', async () => {
    renderApp({ url: '/org/beta/ativos', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta organização' })).toBeInTheDocument()
  })

  it('sends a consultant to the same organization in the consultant area', async () => {
    const { router } = renderApp({ url: '/org/acme', plugins, me: meFor('consultant') })
    await waitFor(() => expect(router.state.location.pathname).toBe('/carteira/acme/painel'))
  })

  it('refuses a user without a client role', async () => {
    renderApp({ url: '/org/acme', plugins, me: meFor(null) })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
  })

  it('sends the organization id to the API on every call', async () => {
    const { calls } = renderApp({ url: '/org/acme/sonda', plugins, api: async () => json({ value: 'ok' }) })
    expect(await screen.findByText('Dados de Acme, Lda.: ok')).toBeInTheDocument()
    const probe = calls.find((c) => c.url === '/api/probe')!
    expect(new Headers(probe.init.headers).get('X-Alinha-Tenant')).toBe(ACME.id)
    expect(probe.init.credentials).toBe('include')
  })

  it('says when a role has no pages yet', async () => {
    renderApp({ url: '/org/acme', plugins: [] })
    expect(await screen.findByRole('heading', { name: 'Ainda não há páginas disponíveis' })).toBeInTheDocument()
  })
})

describe('consultant area (/carteira)', () => {
  it('opens the portfolio with the assigned organizations', async () => {
    const { router } = renderApp({ url: '/', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Carteira de clientes' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/carteira')
    const main = within(screen.getByRole('main'))
    expect(main.getByRole('link', { name: 'Acme, Lda.' })).toHaveAttribute('href', '/carteira/acme')
    expect(main.getByRole('link', { name: 'Beta, S.A.' })).toHaveAttribute('href', '/carteira/beta')
    expect(screen.getByText('Consultoria aLinha')).toBeInTheDocument()
  })

  it('opens a client from the portfolio, with its menu', async () => {
    const { router } = renderApp({ url: '/carteira', plugins, me: meFor('consultant') })
    fireEvent.click(await screen.findByRole('link', { name: 'Acme, Lda.' }))
    expect(await screen.findByRole('heading', { name: 'Painel do cliente' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/carteira/acme/painel')
    expect(nav().getByRole('link', { name: 'Painel' })).toHaveAttribute('href', '/carteira/acme/painel')
    expect(nav().getByRole('link', { name: 'Carteira de clientes' })).toHaveAttribute('href', '/carteira')
  })

  it('switches organization and never shows the previous client data', async () => {
    let answerBeta: (r: Response) => void = () => {}
    const { router, calls } = renderApp({
      url: '/carteira/acme/sonda',
      plugins,
      me: meFor('consultant'),
      api: (_path, init) => new Headers(init.headers).get('X-Alinha-Tenant') === ACME.id
        ? json({ value: 'dados A' })
        : new Promise<Response>((resolve) => { answerBeta = resolve }),
    })
    expect(await screen.findByText('Dados de Acme, Lda.: dados A')).toBeInTheDocument()
    const switcher = screen.getByRole('combobox', { name: 'Cliente' })
    expect(switcher).toHaveValue('acme')

    fireEvent.change(switcher, { target: { value: 'beta' } })
    await waitFor(() => expect(router.state.location.pathname).toBe('/carteira/beta/painel'))
    await router.navigate('/carteira/beta/sonda')
    // While Beta's answer is pending, Acme's cached data must not appear.
    expect(await screen.findByText('Dados de Beta, S.A.: a carregar')).toBeInTheDocument()
    expect(screen.queryByText(/dados A/)).not.toBeInTheDocument()
    answerBeta(json({ value: 'dados B' }))
    expect(await screen.findByText('Dados de Beta, S.A.: dados B')).toBeInTheDocument()
    expect(calls.filter((c) => c.url === '/api/probe').map((c) => new Headers(c.init.headers).get('X-Alinha-Tenant')))
      .toEqual([ACME.id, BETA.id])
  })

  it('starts plugin pages from scratch on another organization (no leftover local state)', async () => {
    const { router } = renderApp({ url: '/carteira/acme/rascunho', plugins, me: meFor('consultant') })
    fireEvent.change(await screen.findByRole('textbox', { name: 'Rascunho' }), { target: { value: 'notas da Acme' } })
    expect(screen.getByRole('textbox', { name: 'Rascunho' })).toHaveValue('notas da Acme')
    await router.navigate('/carteira/beta/rascunho')
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('beta'))
    expect(screen.getByRole('textbox', { name: 'Rascunho' })).toHaveValue('')
  })

  it('does not send a tenant on the portfolio', async () => {
    renderApp({ url: '/carteira', plugins, me: meFor('consultant') })
    await screen.findByRole('heading', { name: 'Carteira de clientes' })
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('')
  })

  it('refuses an organization outside the portfolio', async () => {
    renderApp({ url: '/carteira/outra', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta organização' })).toBeInTheDocument()
  })

  it('sends a client user away from the consultant area', async () => {
    const { router } = renderApp({ url: '/carteira/acme', plugins })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/ativos'))
  })

  it('explains an empty portfolio', async () => {
    renderApp({ url: '/carteira', plugins, me: meFor('consultant', { organizations: [] }) })
    expect(await screen.findByText(/Ainda não tem clientes atribuídos/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})

describe('a 401 on a plugin call', () => {
  const meCalls = (calls: { url: string }[]) => calls.filter((c) => c.url === '/api/me').length

  it('does not redirect while the session is still valid (no login loop)', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    const { calls } = renderApp({ url: '/org/acme/dois', plugins, api: async () => json({}, 401) })
    expect(await screen.findByText('Pedidos recusados')).toBeInTheDocument()
    await waitFor(() => expect(meCalls(calls)).toBeGreaterThan(1))
    expect(go).not.toHaveBeenCalled()
    expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument()
  })

  it('sends the person to the login once when the session has expired', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    let sessionAlive = true
    renderApp({
      url: '/org/acme/dois',
      plugins,
      me: async () => (sessionAlive ? json(meFor('responsible')) : json({}, 401)),
      api: async () => { sessionAlive = false; return json({}, 401) },
    })
    expect(await screen.findByText('A abrir a página de entrada…')).toBeInTheDocument()
    expect(go).toHaveBeenCalledTimes(1)
  })
})

describe('error pages', () => {
  it('shows "page not found" for an unknown address', async () => {
    renderApp({ url: '/nao-existe', plugins })
    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
  })

  it('keeps the menu for an unknown page inside an organization', async () => {
    renderApp({ url: '/org/acme/nao-existe', plugins })
    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
    expect(nav().getByRole('link', { name: 'Ativos' })).toBeInTheDocument()
  })

  it('shows the error page when a plugin page breaks', async () => {
    renderApp({ url: '/org/acme/falha', plugins })
    expect(await screen.findByRole('heading', { name: 'Algo correu mal' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar ao início' })).toHaveAttribute('href', '/')
  })

  it('shows the no-access page when a plugin page gets a 403', async () => {
    renderApp({ url: '/org/acme/negado', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
  })
})
