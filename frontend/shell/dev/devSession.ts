/**
 * DEV ONLY — fake /api/me for `pnpm dev` until the BFF exists (issue #14).
 * Enabled with VITE_DEV_ROLE=consultant|responsible|analyst|registrant|anonymous (anonymous answers 401).
 * `registrant` is a new account that has not registered its organization yet (ADR 0004): no organizations.
 * The plugin has `apply: 'serve'`, so it never reaches a production build.
 */
import type { Plugin } from 'vite'
import type { MeDto, OrganizationRefDto } from '@alinha/api-client'

export const DEV_ROLES = ['consultant', 'responsible', 'analyst', 'registrant', 'anonymous'] as const
export type DevRole = (typeof DEV_ROLES)[number]

/** The BFF has more roles (`registrant`); the dev fake only needs the ones with a full UI. */
type DevPersona = Exclude<DevRole, 'anonymous'>

const ORGS: OrganizationRefDto[] = [
  { id: '0b8f3f6e-6c1a-4c55-9a39-3d1f6f0a0001', slug: 'componentes-agueda', name: 'Componentes Águeda, Lda.' },
  { id: '0b8f3f6e-6c1a-4c55-9a39-3d1f6f0a0002', slug: 'padaria-central', name: 'Padaria Central, Lda.' },
]

const PEOPLE: Record<DevPersona, MeDto['user']> = {
  consultant: { id: 'dev-consultant', name: 'Marta Ferreira' },
  responsible: { id: 'dev-responsible', name: 'Rui Santos' },
  analyst: { id: 'dev-analyst', name: 'Pedro Almeida' },
  registrant: { id: 'dev-registrant', name: 'Nova Conta' },
}

/**
 * PLACEHOLDER permissions per role, taken from the frontend prototype's RBAC matrix (its `view` is `read` here).
 * No plugin.yaml declares permissions yet; once they do, copy each role's set from the backend RBAC (#9).
 */
export const DEV_PERMISSIONS: Record<DevPersona, readonly string[]> = {
  responsible: [
    'plugins:read', 'scope:read', 'scope:edit', 'framing:read', 'framing:edit',
    'asset:read', 'asset:create', 'asset:edit', 'asset:delete', 'asset:import', 'asset:bulk_edit',
    'critical_service:read', 'critical_service:create', 'critical_service:edit', 'critical_service:delete',
    'asset_task:read', 'asset_task:assign', 'asset_comment:read', 'asset_export:read', 'roadmap:read', 'history:read',
    'audit:read', 'member:read', 'member:manage',
  ],
  analyst: ['plugins:read', 'asset:read', 'asset:create', 'asset:edit', 'critical_service:read', 'asset_task:read'],
  consultant: [
    'plugins:read', 'scope:read', 'framing:read', 'asset:read', 'critical_service:read',
    'asset_comment:read', 'asset_comment:comment', 'asset_export:read',
    'risk:read', 'risk:create', 'risk:edit', 'risk:link', 'roadmap:read', 'roadmap:validate', 'roadmap:create',
    'history:read', 'portfolio:read', 'audit:read',
  ],
  registrant: ['plugins:read', 'organization:register', 'invitation:accept'],
}

export function isDevRole(value: string): value is DevRole {
  return (DEV_ROLES as readonly string[]).includes(value)
}

export function devMe(role: DevPersona): MeDto {
  return {
    user: PEOPLE[role],
    roles: [role],
    permissions: [...DEV_PERMISSIONS[role]],
    organizations: role === 'consultant' ? ORGS : role === 'registrant' ? [] : [ORGS[0]],
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
