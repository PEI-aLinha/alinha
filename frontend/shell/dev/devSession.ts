/**
 * DEV ONLY — fake /api/me for `pnpm dev` until the BFF exists (issue #14).
 * Enabled with VITE_DEV_ROLE=consultant|responsible|analyst|anonymous (anonymous answers 401).
 * The plugin has `apply: 'serve'`, so it never reaches a production build.
 */
import type { Plugin } from 'vite'
import type { MeDto, OrganizationRefDto, Role } from '@alinha/api-client'

export const DEV_ROLES = ['consultant', 'responsible', 'analyst', 'anonymous'] as const
export type DevRole = (typeof DEV_ROLES)[number]

const ORGS: OrganizationRefDto[] = [
  { id: '0b8f3f6e-6c1a-4c55-9a39-3d1f6f0a0001', slug: 'componentes-agueda', name: 'Componentes Águeda, Lda.' },
  { id: '0b8f3f6e-6c1a-4c55-9a39-3d1f6f0a0002', slug: 'padaria-central', name: 'Padaria Central, Lda.' },
]

const PEOPLE: Record<Role, MeDto['user']> = {
  consultant: { id: 'dev-consultant', name: 'Marta Ferreira' },
  responsible: { id: 'dev-responsible', name: 'Rui Santos' },
  analyst: { id: 'dev-analyst', name: 'Pedro Almeida' },
}

export function isDevRole(value: string): value is DevRole {
  return (DEV_ROLES as readonly string[]).includes(value)
}

export function devMe(role: Role): MeDto {
  return {
    user: PEOPLE[role],
    roles: [role],
    permissions: ['plugins:read'],
    organizations: role === 'consultant' ? ORGS : [ORGS[0]],
  }
}

/** Vite plugin answering GET /api/me (and the login URL) for the chosen role; null when not enabled. */
export function devSession(role: string | undefined): Plugin | null {
  if (!role) return null
  if (!isDevRole(role)) throw new Error(`VITE_DEV_ROLE must be one of ${DEV_ROLES.join(', ')} (got "${role}")`)
  return {
    name: 'alinha-dev-session',
    apply: 'serve',
    configureServer(server) {
      server.config.logger.info(`  aLinha: fake session as "${role}" (VITE_DEV_ROLE) — dev only`)
      server.middlewares.use((req, res, next) => {
        const path = req.url?.split('?')[0]
        if (req.method === 'GET' && path === '/api/me') {
          res.setHeader('Content-Type', 'application/json')
          if (role === 'anonymous') {
            res.statusCode = 401
            res.end('{}')
          } else {
            res.end(JSON.stringify(devMe(role)))
          }
          return
        }
        if (path === '/oauth2/authorization/keycloak') {
          res.statusCode = 302
          res.setHeader('Location', '/')
          res.end()
          return
        }
        next()
      })
    },
  }
}
