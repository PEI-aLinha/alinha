// The real plugins (plugins.generated.ts, from `pnpm gen:plugins`) mounted in the shell.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import { plugins } from './plugins.generated'
import { ACME, json, meFor, renderApp } from './test-utils'

afterEach(() => vi.unstubAllGlobals())

// The URL changes before the layout renders: wait for the menu, not just the address.
const navLabels = async () =>
  within(await screen.findByRole('navigation', { name: 'Navegação principal' }))
    .getAllByRole('link')
    .map((l) => l.textContent)

describe('the 7 plugins in the shell', () => {
  it('registers every plugin', () => {
    expect(plugins.map((p) => p.id).sort()).toEqual([
      'assessment', 'code-repository', 'dashboard-reports', 'external-surface', 'notifications', 'organization', 'risk-assets',
    ])
  })

  it('mounts the client menu and pages, and calls the API with the organization', async () => {
    const { router, calls } = renderApp({ url: '/org/acme', plugins, api: async () => json({ plugin: 'x', status: 'UP' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/org/acme/assets'))
    // Evidence providers (integrations/*) are grouped under the shell's own "Integrações" entry.
    expect(await navLabels()).toEqual([
      'Integrações', 'Ativos e riscos', 'Avaliação', 'Notificações', 'Organização', 'Painel',
    ])
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/ping'))).toBe(true))
    const ping = calls.find((c) => c.url.endsWith('/ping'))!
    expect(new Headers(ping.init.headers).get('X-Alinha-Tenant')).toBe(ACME.id)
  })

  it('lists the evidence providers on the Integrações page', async () => {
    renderApp({ url: '/org/acme/integrations', plugins, api: async () => json({ status: 'UP' }) })
    expect(await screen.findByRole('heading', { level: 1, name: 'Integrações' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Repositório de código' })).toHaveAttribute('href', '/org/acme/integrations/code-repository')
    expect(screen.getByRole('link', { name: 'Superfície externa' })).toHaveAttribute('href', '/org/acme/integrations/external-surface')
  })

  it('mounts the consultant menu for a client organization', async () => {
    const { router } = renderApp({ url: '/portfolio/acme', plugins, me: meFor('consultant'), api: async () => json({ status: 'UP' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/portfolio/acme/dashboard'))
    expect(await navLabels()).toEqual(['Carteira de clientes', 'Painel'])
  })
})
