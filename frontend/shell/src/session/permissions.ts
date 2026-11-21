import type { Role } from '@alinha/api-client'

export type Can = (resource: string, action?: string) => boolean

/** `can(resource, action)` over the backend's "<resource>:<action>" permissions (RNF08). */
export function makeCan(permissions: readonly string[]): Can {
  const granted = new Set(permissions)
  return (resource, action = 'read') => granted.has(`${resource}:${action}`)
}

/** Roles that work inside one client organization (/org/:org). */
export const CLIENT_ROLES: readonly Role[] = ['responsible', 'analyst']

export const isConsultant = (roles: readonly string[]) => roles.includes('consultant')
export const isClientUser = (roles: readonly string[]) => roles.some((r) => (CLIENT_ROLES as readonly string[]).includes(r))

/**
 * A new account that still has to register its organization (ADR 0004): role `registrant` and no organization.
 * Registering removes the role on the server, so this is true only until then.
 */
export const needsOnboarding = (roles: readonly string[], organizations: readonly unknown[]) =>
  roles.includes('registrant') && organizations.length === 0

/** Short role label shown under the user's name (Portuguese, plain language). */
export function roleLabel(roles: readonly string[]): string {
  if (isConsultant(roles)) return 'Consultoria aLinha'
  if (roles.includes('responsible')) return 'Responsável'
  if (roles.includes('analyst')) return 'Analista'
  if (roles.includes('registrant')) return 'Registo'
  return ''
}
