import { render } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import type { MeDto, OrganizationRefDto, Role } from '@alinha/api-client'
import type { FrontendPlugin } from '@alinha/plugin-sdk'
import { AppProviders } from './AppProviders'
import { createRoutes } from './routing/routes'

export const ACME: OrganizationRefDto = { id: '00000000-0000-4000-8000-00000000000a', slug: 'acme', name: 'Acme, Lda.' }
export const BETA: OrganizationRefDto = { id: '00000000-0000-4000-8000-00000000000b', slug: 'beta', name: 'Beta, S.A.' }

export function meFor(role: Role | null, overrides: Partial<MeDto> = {}): MeDto {
  return {
    user: { id: 'u1', name: role === 'consultant' ? 'Marta Ferreira' : 'Rui Santos' },
    roles: role ? [role] : [],
    permissions: [],
    organizations: role === 'consultant' ? [ACME, BETA] : [ACME],
    ...overrides,
  }
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

interface RenderAppOptions {
  url: string
  plugins?: FrontendPlugin[]
  /** Response to GET /api/me: a session, an HTTP status, or a function (e.g. a pending promise). */
  me?: MeDto | number | (() => Promise<Response>)
  /** Responses to any other /api call. */
  api?: (path: string, init: RequestInit) => Response | Promise<Response>
}

export interface ApiCall { url: string; init: RequestInit }

/** Renders the whole shell (session, layouts, plugin routes) at `url`, with a fake backend. */
export function renderApp({ url, plugins = [], me = meFor('responsible'), api }: RenderAppOptions) {
  const calls: ApiCall[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    calls.push({ url: path, init })
    if (path === '/api/me') {
      if (typeof me === 'function') return me()
      return typeof me === 'number' ? json({}, me) : json(me)
    }
    return api ? api(path, init) : json({}, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  const router = createMemoryRouter(createRoutes(plugins), { initialEntries: [url] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { ...view, router, calls, fetchMock, queryClient }
}
