import { describe, expect, it, vi } from 'vitest'
import type { Plugin, ViteDevServer } from 'vite'
import { devMe, devSession } from './devSession'

type Middleware = (req: { method?: string; url?: string }, res: FakeRes, next: () => void) => void
interface FakeRes { statusCode: number; headers: Record<string, string>; body?: string; setHeader(k: string, v: string): void; end(b?: string): void }

function serve(plugin: Plugin) {
  let middleware: Middleware | undefined
  const server = {
    config: { logger: { info: vi.fn() } },
    middlewares: { use: (m: Middleware) => { middleware = m } },
  } as unknown as ViteDevServer
  ;(plugin.configureServer as (s: ViteDevServer) => void)(server)
  return (method: string, url: string) => {
    const res: FakeRes = { statusCode: 200, headers: {}, setHeader(k, v) { this.headers[k] = v }, end(b) { this.body = b } }
    const next = vi.fn()
    middleware!({ method, url }, res, next)
    return { res, next }
  }
}

describe('devSession (pnpm dev only)', () => {
  it('is off without VITE_DEV_ROLE and refuses unknown roles', () => {
    expect(devSession(undefined)).toBeNull()
    expect(() => devSession('admin')).toThrow('VITE_DEV_ROLE')
  })

  it('only runs on the dev server, never in a build', () => {
    expect(devSession('consultant')?.apply).toBe('serve')
  })

  it('answers GET /api/me with the chosen role', () => {
    const request = serve(devSession('analyst')!)
    const { res, next } = request('GET', '/api/me?x=1')
    expect(next).not.toHaveBeenCalled()
    expect(JSON.parse(res.body!)).toEqual(devMe('analyst'))
  })

  it('answers 401 for anonymous', () => {
    expect(serve(devSession('anonymous')!)('GET', '/api/me').res.statusCode).toBe(401)
  })

  it('sends the login URL back home and leaves every other request alone', () => {
    const request = serve(devSession('responsible')!)
    const login = request('GET', '/oauth2/authorization/keycloak').res
    expect(login.statusCode).toBe(302)
    expect(login.headers.Location).toBe('/')
    expect(request('GET', '/api/assets').next).toHaveBeenCalled()
    expect(request('POST', '/api/me').next).toHaveBeenCalled()
  })

  it('gives the consultant several organizations and a client user one', () => {
    expect(devMe('consultant').organizations.length).toBeGreaterThan(1)
    expect(devMe('responsible').organizations).toHaveLength(1)
  })

  it('gives each role its own permissions, in "<resource>:<action>" form', () => {
    const [responsible, analyst, consultant] = (['responsible', 'analyst', 'consultant'] as const).map((r) => devMe(r).permissions)
    expect(analyst.length).toBeLessThan(responsible.length)
    expect(responsible).toContain('asset:delete')
    expect(analyst).not.toContain('asset:delete')
    expect(consultant).toContain('roadmap:validate')
    expect(responsible).not.toContain('roadmap:validate')
    for (const p of [...responsible, ...analyst, ...consultant]) expect(p).toMatch(/^[a-z_]+:[a-z_]+$/)
  })
})
