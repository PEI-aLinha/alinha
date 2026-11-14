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
    ],
    consultant: [
      { path: 'painel', Component: () => <h1>Painel do cliente</h1> },
      { path: 'sonda', Component: TenantProbe },
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
