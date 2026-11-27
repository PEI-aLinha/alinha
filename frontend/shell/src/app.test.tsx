import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { useQuery } from '@tanstack/react-query'
import { ApiError, definePlugin, useHost } from '@alinha/plugin-sdk'
import { useToast } from '@alinha/ui'
import { login } from './session/login'
import { ACME, BETA, json, meFor, renderApp } from './test-utils'

function TenantProbe() {
  const host = useHost()
  const probe = useQuery({ queryKey: ['probe'], queryFn: () => host.api<{ value: string }>('/probe') })
  return <p>Dados de {host.organization?.name}: {probe.data?.value ?? 'a carregar'}</p>
}

/** Lists the organizations the host offers, like a page across the portfolio. */
function OrganizationsProbe() {
  const host = useHost()
  return <ul aria-label="Organizações do host">{host.organizations?.map((o) => <li key={o.id}>{o.name} ({o.slug})</li>)}</ul>
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

/** A plugin page that confirms an action with a toast. */
function SaveButton() {
  const toast = useToast()
  return <button onClick={() => toast.show('Ativo guardado')}>Guardar</button>
}

const testPlugin = definePlugin({
  id: 'test',
  name: 'Test',
  routes: {
    client: [
      { path: 'assets', handle: { title: 'Ativos' }, Component: () => <h1>Página de ativos</h1> },
      { path: 'risks', handle: { title: 'Riscos', resource: 'risk' }, Component: () => <h1>Página de riscos</h1> },
      { path: 'boom', Component: Boom },
      { path: 'denied', Component: Denied },
      { path: 'probe', Component: TenantProbe },
      { path: 'two-calls', Component: TwoCalls },
      { path: 'save', Component: SaveButton },
      { path: 'lazy', handle: { resource: 'risk' }, lazy: async () => ({ Component: () => <h1>Página carregada à parte</h1> }) },
      { path: 'organizations', Component: OrganizationsProbe },
    ],
    onboarding: [
      { path: 'registration', Component: TenantProbe },
    ],
    portfolio: [
      { path: 'overview', handle: { title: 'Visão geral' }, Component: TenantProbe },
      { path: 'reports', handle: { resource: 'portfolio_report' }, Component: () => <h1>Relatórios da carteira</h1> },
    ],
    consultant: [
      { path: 'dashboard', Component: () => <h1>Painel do cliente</h1> },
      { path: 'probe', Component: TenantProbe },
      { path: 'draft', Component: Draft },
      { path: 'organizations', Component: OrganizationsProbe },
    ],
  },
  nav: [
    { label: 'Riscos', path: 'risks', area: 'client', resource: 'risk', order: 2 },
    { label: 'Ativos', path: 'assets', area: 'client', order: 1 },
    { label: 'Painel', path: 'dashboard', area: 'consultant' },
    { label: 'Visão geral', path: 'overview', area: 'portfolio' },
    { label: 'Relatórios', path: 'reports', area: 'portfolio', resource: 'portfolio_report' },
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
    // The toast region is also a (still empty) status region, so find this one by its text.
    expect(screen.getByText('A preparar a sua sessão…')).toHaveAttribute('role', 'status')
  })

  it('sends the browser to the login page when there is no session', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    renderApp({ url: '/org/acme/assets', plugins, me: 401 })
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
    sessionStorage.setItem('alinha.return-to', '/org/acme/probe')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/probe'))
    expect(sessionStorage.getItem('alinha.return-to')).toBeNull()
  })

  it('lets the person move on after going back to the saved page', async () => {
    sessionStorage.setItem('alinha.return-to', '/org/acme/probe')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/probe'))
    // The URL changes before the layout renders: wait for the menu, not just the address.
    const menu = await screen.findByRole('navigation', { name: 'Navegação principal' })
    fireEvent.click(within(menu).getByRole('link', { name: 'Ativos' }))
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(router.state.location.pathname).toBe('/org/acme/assets')
  })

  it('goes back to a saved page with a query string, once', async () => {
    sessionStorage.setItem('alinha.return-to', '/org/acme/probe?filter=open')
    const { router } = renderApp({ url: '/', plugins, api: async () => json({ value: 'x' }) })
    await waitFor(() => expect(router.state.location.search).toBe('?filter=open'))
    await router.navigate('/org/acme/assets')
    expect(await screen.findByRole('heading', { name: 'Página de ativos' })).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(router.state.location.pathname).toBe('/org/acme/assets')
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
    expect(router.state.location.pathname).toBe('/org/acme/assets')
    expect(screen.getByText('Rui Santos')).toBeInTheDocument()
    expect(screen.getByText('Responsável')).toBeInTheDocument()
  })

  it('shows only the menu entries the role may see, in order', async () => {
    renderApp({ url: '/org/acme/assets', plugins, me: meFor('responsible', { permissions: ['risk:read'] }) })
    await screen.findByRole('heading', { name: 'Página de ativos' })
    expect(nav().getAllByRole('link').map((l) => l.textContent)).toEqual(['Ativos', 'Riscos'])
    expect(nav().getByRole('link', { name: 'Riscos' })).toHaveAttribute('href', '/org/acme/risks')
  })

  it('hides entries and blocks pages without the permission', async () => {
    renderApp({ url: '/org/acme/risks', plugins, me: meFor('analyst') })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Riscos' })).not.toBeInTheDocument()
    expect(screen.getByText('Analista')).toBeInTheDocument()
  })

  it('guards a lazy plugin route and still loads it with the permission', async () => {
    renderApp({ url: '/org/acme/lazy', plugins, me: meFor('responsible', { permissions: ['risk:read'] }) })
    expect(await screen.findByRole('heading', { name: 'Página carregada à parte' })).toBeInTheDocument()
  })

  it('refuses a lazy plugin route without the permission', async () => {
    renderApp({ url: '/org/acme/lazy', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Página carregada à parte' })).not.toBeInTheDocument()
  })

  it('refuses another organization', async () => {
    renderApp({ url: '/org/beta/assets', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta organização' })).toBeInTheDocument()
  })

  it('sends a consultant to the same organization in the consultant area', async () => {
    const { router } = renderApp({ url: '/org/acme', plugins, me: meFor('consultant') })
    await waitFor(() => expect(router.state.location.pathname).toBe('/portfolio/acme/dashboard'))
  })

  it('keeps the rest of the address when sending a consultant to the consultant area', async () => {
    const { router } = renderApp({ url: '/org/acme/dashboard?view=month#top', plugins, me: meFor('consultant') })
    await waitFor(() => expect(router.state.location.pathname).toBe('/portfolio/acme/dashboard'))
    expect(router.state.location.search).toBe('?view=month')
    expect(router.state.location.hash).toBe('#top')
  })

  it('refuses a user without a client role', async () => {
    renderApp({ url: '/org/acme', plugins, me: meFor(null) })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
  })

  it('sends the organization id to the API on every call', async () => {
    const { calls } = renderApp({ url: '/org/acme/probe', plugins, api: async () => json({ value: 'ok' }) })
    expect(await screen.findByText('Dados de Acme, Lda.: ok')).toBeInTheDocument()
    const probe = calls.find((c) => c.url === '/api/probe')!
    expect(new Headers(probe.init.headers).get('X-Alinha-Tenant')).toBe(ACME.id)
    expect(probe.init.credentials).toBe('include')
  })

  it('says when a role has no pages yet', async () => {
    renderApp({ url: '/org/acme', plugins: [] })
    expect(await screen.findByRole('heading', { name: 'Ainda não há páginas disponíveis' })).toBeInTheDocument()
  })

  it('opens the Integrações page when the user only sees evidence providers', async () => {
    const provider = definePlugin({
      id: 'provider',
      name: 'Provider',
      routes: { client: [{ path: 'integrations/code-repository', Component: () => <h1>Repositório</h1> }] },
      nav: [{ label: 'Repositório de código', path: 'integrations/code-repository', area: 'client' }],
    })
    const { router } = renderApp({ url: '/org/acme', plugins: [provider] })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/integrations'))
    expect(await screen.findByRole('link', { name: 'Repositório de código' })).toBeInTheDocument()
  })
})

describe('consultant area (/portfolio)', () => {
  it('opens the portfolio with the assigned organizations', async () => {
    const { router } = renderApp({ url: '/', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Carteira de clientes' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/portfolio')
    const main = within(screen.getByRole('main'))
    expect(main.getByRole('link', { name: 'Acme, Lda.' })).toHaveAttribute('href', '/portfolio/acme')
    expect(main.getByRole('link', { name: 'Beta, S.A.' })).toHaveAttribute('href', '/portfolio/beta')
    expect(screen.getByText('Consultoria aLinha')).toBeInTheDocument()
  })

  it('opens a client from the portfolio, with its menu', async () => {
    const { router } = renderApp({ url: '/portfolio', plugins, me: meFor('consultant') })
    fireEvent.click(await screen.findByRole('link', { name: 'Acme, Lda.' }))
    expect(await screen.findByRole('heading', { name: 'Painel do cliente' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/portfolio/acme/dashboard')
    expect(nav().getByRole('link', { name: 'Painel' })).toHaveAttribute('href', '/portfolio/acme/dashboard')
    expect(nav().getByRole('link', { name: 'Carteira de clientes' })).toHaveAttribute('href', '/portfolio')
  })

  it('switches organization and never shows the previous client data', async () => {
    let answerBeta: (r: Response) => void = () => {}
    const { router, calls } = renderApp({
      url: '/portfolio/acme/probe',
      plugins,
      me: meFor('consultant'),
      api: (path, init) => {
        if (new Headers(init.headers).get('X-Alinha-Tenant') === ACME.id) return json({ value: 'dados A' })
        // Hold only Beta's probe: another Beta call made later must not take its place in answerBeta.
        if (path !== '/api/probe') return json({})
        return new Promise<Response>((resolve) => { answerBeta = resolve })
      },
    })
    expect(await screen.findByText('Dados de Acme, Lda.: dados A')).toBeInTheDocument()
    const switcher = screen.getByRole('combobox', { name: 'Cliente' })
    expect(switcher).toHaveValue('acme')

    fireEvent.change(switcher, { target: { value: 'beta' } })
    await waitFor(() => expect(router.state.location.pathname).toBe('/portfolio/beta/dashboard'))
    await router.navigate('/portfolio/beta/probe')
    // While Beta's answer is pending, Acme's cached data must not appear.
    expect(await screen.findByText('Dados de Beta, S.A.: a carregar')).toBeInTheDocument()
    expect(screen.queryByText(/dados A/)).not.toBeInTheDocument()
    answerBeta(json({ value: 'dados B' }))
    expect(await screen.findByText('Dados de Beta, S.A.: dados B')).toBeInTheDocument()
    expect(calls.filter((c) => c.url === '/api/probe').map((c) => new Headers(c.init.headers).get('X-Alinha-Tenant')))
      .toEqual([ACME.id, BETA.id])
  })

  it('starts plugin pages from scratch on another organization (no leftover local state)', async () => {
    const { router } = renderApp({ url: '/portfolio/acme/draft', plugins, me: meFor('consultant') })
    fireEvent.change(await screen.findByRole('textbox', { name: 'Rascunho' }), { target: { value: 'notas da Acme' } })
    expect(screen.getByRole('textbox', { name: 'Rascunho' })).toHaveValue('notas da Acme')
    await router.navigate('/portfolio/beta/draft')
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('beta'))
    expect(screen.getByRole('textbox', { name: 'Rascunho' })).toHaveValue('')
  })

  it('does not send a tenant on the portfolio', async () => {
    renderApp({ url: '/portfolio', plugins, me: meFor('consultant') })
    await screen.findByRole('heading', { name: 'Carteira de clientes' })
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('')
  })

  it('gives plugins every organization of the portfolio, for names and links', async () => {
    renderApp({ url: '/portfolio/acme/organizations', plugins, me: meFor('consultant') })
    const list = await screen.findByRole('list', { name: 'Organizações do host' })
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Acme, Lda. (acme)', 'Beta, S.A. (beta)'])
  })

  it('gives a client user only their own organization, never other clients', async () => {
    renderApp({ url: '/org/acme/organizations', plugins, me: meFor('responsible') })
    const list = await screen.findByRole('list', { name: 'Organizações do host' })
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Acme, Lda. (acme)'])
  })

  it('opens a plugin page across the portfolio, with no organization and no tenant header', async () => {
    const { calls } = renderApp({ url: '/portfolio', plugins, me: meFor('consultant'), api: () => json({ value: 'todos' }) })
    await screen.findByRole('heading', { name: 'Carteira de clientes' })
    expect(nav().getByRole('link', { name: 'Visão geral' })).toHaveAttribute('href', '/portfolio/overview')
    expect(nav().queryByRole('link', { name: 'Relatórios' })).not.toBeInTheDocument() // no portfolio_report:read

    fireEvent.click(nav().getByRole('link', { name: 'Visão geral' }))

    expect(await screen.findByText('Dados de : todos')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('')
    const probe = calls.find((c) => c.url === '/api/probe')
    expect(new Headers(probe?.init.headers).has('X-Alinha-Tenant')).toBe(false)
  })

  // ADR 0006: with portfolio pages registered (`overview`, `reports`), a client slug still opens that client and the
  // bare /portfolio still lists the clients. React Router must rank the fixed segment above `:org`, never instead of it.
  it('still opens any client at /portfolio/<slug> next to the portfolio pages', async () => {
    const { router } = renderApp({ url: '/portfolio/beta', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Painel do cliente' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/portfolio/beta/dashboard')
    expect(screen.getByRole('combobox', { name: 'Cliente' })).toHaveValue('beta')
  })

  it('still lists the clients at /portfolio next to the portfolio pages', async () => {
    renderApp({ url: '/portfolio', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Carteira de clientes' })).toBeInTheDocument()
    const main = within(screen.getByRole('main'))
    expect(main.getByRole('link', { name: 'Beta, S.A.' })).toHaveAttribute('href', '/portfolio/beta')
    expect(screen.queryByText(/Dados de/)).not.toBeInTheDocument() // not the overview page
  })

  it('keeps the portfolio menu inside a client, next to that client\'s pages', async () => {
    renderApp({ url: '/portfolio/acme/dashboard', plugins, me: meFor('consultant') })
    await screen.findByRole('heading', { name: 'Painel do cliente' })
    expect(nav().getByRole('link', { name: 'Visão geral' })).toHaveAttribute('href', '/portfolio/overview')
    expect(nav().getByRole('link', { name: 'Painel' })).toHaveAttribute('href', '/portfolio/acme/dashboard')
  })

  it('guards a portfolio page with its permission', async () => {
    renderApp({ url: '/portfolio/reports', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
  })

  it('opens a guarded portfolio page with the permission', async () => {
    renderApp({ url: '/portfolio/reports', plugins, me: meFor('consultant', { permissions: ['portfolio_report:read'] }) })
    expect(await screen.findByRole('heading', { name: 'Relatórios da carteira' })).toBeInTheDocument()
  })

  it('refuses an organization outside the portfolio', async () => {
    renderApp({ url: '/portfolio/outra', plugins, me: meFor('consultant') })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta organização' })).toBeInTheDocument()
  })

  it('sends a client user away from the consultant area', async () => {
    const { router } = renderApp({ url: '/portfolio/acme', plugins })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/assets'))
  })

  it('explains an empty portfolio', async () => {
    renderApp({ url: '/portfolio', plugins, me: meFor('consultant', { organizations: [] }) })
    expect(await screen.findByText(/Ainda não tem clientes atribuídos/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})

describe('a 401 on a plugin call', () => {
  const meCalls = (calls: { url: string }[]) => calls.filter((c) => c.url === '/api/me').length

  it('does not redirect while the session is still valid (no login loop)', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    const { calls } = renderApp({ url: '/org/acme/two-calls', plugins, api: async () => json({}, 401) })
    expect(await screen.findByText('Pedidos recusados')).toBeInTheDocument()
    await waitFor(() => expect(meCalls(calls)).toBeGreaterThan(1))
    expect(go).not.toHaveBeenCalled()
    expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument()
  })

  it('sends the person to the login once when the session has expired', async () => {
    const go = vi.spyOn(login, 'go').mockImplementation(() => {})
    let sessionAlive = true
    renderApp({
      url: '/org/acme/two-calls',
      plugins,
      me: async () => (sessionAlive ? json(meFor('responsible')) : json({}, 401)),
      api: async () => { sessionAlive = false; return json({}, 401) },
    })
    expect(await screen.findByText('A abrir a página de entrada…')).toBeInTheDocument()
    expect(go).toHaveBeenCalledTimes(1)
  })

  it('keeps the page when re-checking the session fails for another reason', async () => {
    let meAnswers = 0
    const { calls } = renderApp({
      url: '/org/acme/two-calls',
      plugins,
      me: async () => (meAnswers++ === 0 ? json(meFor('responsible')) : json({}, 503)),
      api: async () => json({}, 401),
    })
    expect(await screen.findByText('Pedidos recusados')).toBeInTheDocument()
    await waitFor(() => expect(meCalls(calls)).toBeGreaterThan(1))
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('heading', { name: 'Algo correu mal' })).not.toBeInTheDocument()
    expect(screen.getByText('Pedidos recusados')).toBeInTheDocument()
  })
})

describe('session answers that are not JSON', () => {
  it('shows the error page when /api/me answers 200 with HTML', async () => {
    renderApp({ url: '/', plugins, me: async () => new Response('<!doctype html><title>Keycloak</title>', { status: 200, headers: { 'Content-Type': 'text/html' } }) })
    expect(await screen.findByRole('heading', { name: 'Algo correu mal' })).toBeInTheDocument()
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
    renderApp({ url: '/org/acme/boom', plugins })
    expect(await screen.findByRole('heading', { name: 'Algo correu mal' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar ao início' })).toHaveAttribute('href', '/')
  })

  it('shows the no-access page when a plugin page gets a 403', async () => {
    renderApp({ url: '/org/acme/denied', plugins })
    expect(await screen.findByRole('heading', { name: 'Não tem acesso a esta página' })).toBeInTheDocument()
  })
})

describe('onboarding (/onboarding)', () => {
  const registrant = () => meFor('registrant', { organizations: [], permissions: ['organization:register'] })

  it('sends a new account without an organization to the first onboarding page, with no tenant', async () => {
    const { router, calls } = renderApp({ url: '/', plugins, me: registrant(), api: () => json({ value: 'ok' }) })
    expect(await screen.findByText('Dados de : ok')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/onboarding/registration')
    expect(screen.queryByRole('navigation', { name: 'Navegação principal' })).not.toBeInTheDocument()
    const probe = calls.find((c) => c.url === '/api/probe')
    expect(new Headers(probe?.init.headers).has('X-Alinha-Tenant')).toBe(false)
  })

  it('keeps the "not ready" page when no plugin has an onboarding page', async () => {
    renderApp({ url: '/', plugins: [], me: registrant() })
    expect(await screen.findByRole('heading', { name: 'A sua conta ainda não está pronta' })).toBeInTheDocument()
  })

  it('sends anyone who already has an organization back home', async () => {
    const { router } = renderApp({ url: '/onboarding/registration', plugins })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/assets'))
  })

  it('does not show the form again when the account signed in again but has no organization yet', async () => {
    window.sessionStorage.setItem('alinha.reauthenticated-at', String(Date.now()))
    renderApp({ url: '/onboarding/registration', plugins, me: registrant(), api: () => json({ value: 'ok' }) })
    expect(await screen.findByRole('heading', { name: 'Quase pronto' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verificar novamente' })).toBeInTheDocument()
    expect(screen.queryByText('Dados de : ok')).not.toBeInTheDocument()
    // Kept until the session has an organization, so a reload does not bring the form back.
    expect(window.sessionStorage.getItem('alinha.reauthenticated-at')).not.toBeNull()
    window.sessionStorage.removeItem('alinha.reauthenticated-at')
  })

  it('refreshes the session from "Quase pronto" and goes home once it has the organization', async () => {
    window.sessionStorage.setItem('alinha.reauthenticated-at', String(Date.now()))
    let session = registrant()
    const { router, calls } = renderApp({
      url: '/onboarding/registration',
      plugins,
      me: async () => json(session),
      api: (path) => {
        if (path !== '/auth/refresh') return json({ value: 'ok' })
        session = meFor('responsible')
        return new Response(null, { status: 204 })
      },
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Verificar novamente' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/assets'))
    expect(calls.find((c) => c.url === '/auth/refresh')?.init.method).toBe('POST')
    expect(window.sessionStorage.getItem('alinha.reauthenticated-at')).toBeNull()
  })

  it('sends the onboarding root home, and from there to the first page', async () => {
    const { router } = renderApp({ url: '/onboarding', plugins, me: registrant(), api: () => json({ value: 'ok' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/registration'))
  })
})

describe('toasts', () => {
  it('a plugin page can confirm an action with useToast()', async () => {
    renderApp({ url: '/org/acme/save', plugins })
    fireEvent.click(await screen.findByRole('button', { name: 'Guardar' }))
    expect(screen.getByText('Ativo guardado').closest('[role="status"]')).not.toBeNull()
  })
})
