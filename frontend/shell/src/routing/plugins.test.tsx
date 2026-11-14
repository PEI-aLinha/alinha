import { describe, expect, it } from 'vitest'
import { isValidElement } from 'react'
import { definePlugin, type FrontendPlugin } from '@alinha/plugin-sdk'
import { makeCan } from '../session/permissions'
import { pluginNav, pluginRoutes, RequirePermission } from './plugins'

const Page = () => null

const a = definePlugin({
  id: 'a',
  name: 'A',
  routes: {
    client: [
      { path: 'livre', Component: Page },
      { path: 'riscos', handle: { resource: 'risk' }, Component: Page },
      { path: 'editar', handle: { resource: 'risk', action: 'edit' }, element: <Page /> },
      { path: 'grupo', children: [{ path: 'interno', handle: { resource: 'asset' }, Component: Page }] },
    ],
    consultant: [{ path: 'painel', Component: Page }],
  },
  nav: [
    { label: 'Zeta', path: 'z', area: 'client' },
    { label: 'Alfa', path: 'a', area: 'client' },
    { label: 'Primeiro', path: 'p', area: 'client', order: 1 },
    { label: 'Riscos', path: 'riscos', area: 'client', resource: 'risk' },
    { label: 'Painel', path: 'painel', area: 'consultant' },
  ],
})
const b: FrontendPlugin = { id: 'b', name: 'B', routes: {} }

const guardOf = (route: { element?: unknown }) =>
  isValidElement(route.element) && route.element.type === RequirePermission ? (route.element.props as { resource: string; action?: string }) : null

describe('pluginRoutes', () => {
  const routes = pluginRoutes([a, b], 'client')

  it('takes only the routes of the area', () => {
    expect(routes.map((r) => r.path)).toEqual(['livre', 'riscos', 'editar', 'grupo'])
    expect(pluginRoutes([a, b], 'consultant').map((r) => r.path)).toEqual(['painel'])
  })

  it('leaves routes without a resource untouched', () => {
    expect(routes[0]).toBe(a.routes.client![0])
  })

  it('guards routes that ask for a permission, with read by default', () => {
    expect(guardOf(routes[1])).toMatchObject({ resource: 'risk', action: undefined })
    expect(routes[1].Component).toBeUndefined()
    expect(guardOf(routes[2])).toMatchObject({ resource: 'risk', action: 'edit' })
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
})
