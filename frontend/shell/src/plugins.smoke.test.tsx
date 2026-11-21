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
    expect(await navLabels()).toEqual([
      'Ativos e riscos', 'Avaliação', 'Notificações', 'Organização', 'Painel', 'Repositório de código', 'Superfície externa',
    ])
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/ping'))).toBe(true))
    const ping = calls.find((c) => c.url.endsWith('/ping'))!
    expect(new Headers(ping.init.headers).get('X-Alinha-Tenant')).toBe(ACME.id)
  })

  it('mounts the consultant menu for a client organization', async () => {
    const { router } = renderApp({ url: '/portfolio/acme', plugins, me: meFor('consultant'), api: async () => json({ status: 'UP' }) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/portfolio/acme/dashboard'))
    expect(await navLabels()).toEqual(['Carteira de clientes', 'Painel'])
  })
})
