import { describe, expect, it } from 'vitest'
import { isValidElement } from 'react'
import type { RouteObject } from 'react-router'
import { definePlugin, type FrontendPlugin } from '@alinha/plugin-sdk'
import { makeCan } from '../session/permissions'
import { checkReservedPaths, integrationNav, pluginNav, pluginRoutes, RequirePermission } from './plugins'

const Page = () => null

const a = definePlugin({
  id: 'a',
  name: 'A',
  routes: {
    client: [
      { path: 'open', Component: Page },
      { path: 'risks', handle: { resource: 'risk' }, Component: Page },
      { path: 'edit', handle: { resource: 'risk', action: 'edit' }, element: <Page /> },
      { path: 'group', children: [{ path: 'inner', handle: { resource: 'asset' }, Component: Page }] },
    ],
    consultant: [{ path: 'dashboard', Component: Page }],
  },
  nav: [
    { label: 'Zeta', path: 'z', area: 'client' },
    { label: 'Alfa', path: 'a', area: 'client' },
    { label: 'Primeiro', path: 'p', area: 'client', order: 1 },
    { label: 'Riscos', path: 'risks', area: 'client', resource: 'risk' },
    { label: 'Editar riscos', path: 'edit', area: 'client', resource: 'risk', action: 'edit', order: 200 },
    { label: 'Painel', path: 'dashboard', area: 'consultant' },
  ],
})
const b: FrontendPlugin = { id: 'b', name: 'B', routes: {} }

const guardOf = (route: RouteObject) =>
  isValidElement(route.element) && route.element.type === RequirePermission ? (route.element.props as { resource: string; action?: string }) : null
/** The plugin route itself: the route, or the only child of its guard. */
const inner = (route: RouteObject) => (guardOf(route) ? route.children![0] : route)

describe('pluginRoutes', () => {
  const routes = pluginRoutes([a, b], 'client')

  it('takes only the routes of the area', () => {
    expect(routes.map((r) => inner(r).path)).toEqual(['open', 'risks', 'edit', 'group'])
    expect(pluginRoutes([a, b], 'consultant').map((r) => inner(r).path)).toEqual(['dashboard'])
  })

  it('leaves routes without a resource untouched', () => {
    expect(routes[0]).toBe(a.routes.client![0])
  })

  it('guards routes that ask for a permission from a pathless parent, keeping the route as is', () => {
    expect(guardOf(routes[1])).toMatchObject({ resource: 'risk', action: undefined })
    expect(routes[1].path).toBeUndefined()
    expect(inner(routes[1])).toBe(a.routes.client![1])
    expect(guardOf(routes[2])).toMatchObject({ resource: 'risk', action: 'edit' })
    expect(inner(routes[2]).element).toBeDefined()
  })

  it('guards nested routes too', () => {
    expect(guardOf(routes[3])).toBeNull()
    expect(guardOf(routes[3].children![0])).toMatchObject({ resource: 'asset' })
  })
})

describe('pluginNav', () => {
  it('filters by area and permission, sorted by order then label', () => {
    expect(pluginNav([a, b], 'client', makeCan([])).map((n) => n.label)).toEqual(['Primeiro', 'Alfa', 'Zeta'])
    expect(pluginNav([a, b], 'client', makeCan(['risk:read'])).map((n) => n.label)).toEqual(['Primeiro', 'Alfa', 'Riscos', 'Zeta'])
    expect(pluginNav([a, b], 'consultant', makeCan([])).map((n) => n.label)).toEqual(['Painel'])
  })

  it('uses the same action as the route when the entry names one', () => {
    expect(pluginNav([a], 'client', makeCan(['risk:read'])).map((n) => n.label)).not.toContain('Editar riscos')
    expect(pluginNav([a], 'client', makeCan(['risk:read', 'risk:edit'])).map((n) => n.label)).toContain('Editar riscos')
  })
})

describe('integrationNav', () => {
  const providers = definePlugin({
    id: 'p',
    name: 'P',
    routes: {},
    nav: [
      { label: 'Superfície externa', path: 'integrations/external-surface', area: 'client', order: 71 },
      { label: 'Repositório de código', path: 'integrations/code-repository', area: 'client', order: 70 },
      { label: 'Segredo', path: 'integrations/secret', area: 'client', resource: 'integration' },
      { label: 'Integrações antigas', path: 'integrationsold', area: 'client' },
    ],
  })

  it('takes the entries under integrations/ out of the side menu', () => {
    expect(pluginNav([providers], 'client', makeCan([])).map((n) => n.label)).toEqual(['Integrações antigas'])
  })

  it('lists them for the Integrações page, by order and permission', () => {
    expect(integrationNav([providers], 'client', makeCan([])).map((n) => n.label)).toEqual(['Repositório de código', 'Superfície externa'])
    expect(integrationNav([providers], 'client', makeCan(['integration:read'])).map((n) => n.label)).toContain('Segredo')
  })
})

describe('checkReservedPaths', () => {
  it('accepts provider pages and menu entries under integrations/', () => {
    const provider = definePlugin({
      id: 'p',
      name: 'P',
      routes: { client: [{ path: 'integrations/code-repository', Component: Page }] },
      nav: [{ label: 'Repositório de código', path: 'integrations/code-repository', area: 'client' }],
    })
    expect(() => checkReservedPaths([provider, a])).not.toThrow()
  })

  it.each([
    ['a client route', { routes: { client: [{ path: 'integrations', Component: Page }] } }],
    ['a consultant route with slashes', { routes: { consultant: [{ path: '/integrations/', Component: Page }] } }],
    ['a menu entry', { routes: {}, nav: [{ label: 'Integrações', path: 'integrations', area: 'client' as const }] }],
  ])('refuses %s at exactly integrations, naming the plugin', (_what, parts) => {
    const plugin = definePlugin({ id: 'clash', name: 'Clash', ...parts })
    expect(() => checkReservedPaths([plugin])).toThrow(/Plugin "clash".*'integrations\/<id>'/)
  })
})

