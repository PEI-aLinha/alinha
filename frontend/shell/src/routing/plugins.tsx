import type { ReactNode } from 'react'
import { Outlet, type RouteObject } from 'react-router'
import type { Area, FrontendPlugin, NavArea, NavItem, RouteHandle } from '@alinha/plugin-sdk'
import { ForbiddenPage } from '../pages/StatusPages'
import { useSession } from '../session/SessionGate'
import type { Can } from '../session/permissions'

/** Shows the page only with `<resource>:<action>`; otherwise the "no access" page (RNF08). */
export function RequirePermission({ resource, action, children }: { resource: string; action?: string; children: ReactNode }) {
  const { can } = useSession()
  return can(resource, action) ? children : <ForbiddenPage />
}

/**
 * Guards a plugin route (and its children) whose `handle.resource` asks for a permission. The route itself is
 * left untouched inside a pathless parent, so `element`, `Component` and `lazy` routes are all covered
 * (a `lazy` Component would otherwise replace a guard set on the route's own `element`).
 */
function guard(route: RouteObject): RouteObject {
  const children = route.children?.map(guard)
  const own = (children ? { ...route, children } : route) as RouteObject
  const handle = route.handle as RouteHandle | undefined
  if (!handle?.resource) return own
  return {
    element: <RequirePermission resource={handle.resource} action={handle.action}><Outlet /></RequirePermission>,
    children: [own],
  }
}

/** Every plugin's routes for one area, in plugin order, guarded by permission. */
export function pluginRoutes(plugins: readonly FrontendPlugin[], area: Area): RouteObject[] {
  return plugins.flatMap((p) => p.routes[area] ?? []).map(guard)
}

/** Menu entries of evidence providers live under this path; the shell groups them on its "Integrações" page. */
export const INTEGRATIONS_PATH = 'integrations'

const isIntegration = (n: NavItem) => n.path === INTEGRATIONS_PATH || n.path.startsWith(`${INTEGRATIONS_PATH}/`)

const trimSlashes = (path: string) => path.replace(/^\/+|\/+$/g, '')

/**
 * `integrations` itself is the shell's "Integrações" page, registered before the plugins' routes: a plugin route or
 * menu entry at exactly that path would be silently replaced by it. Providers use `integrations/<id>` instead.
 * A plugin bug, so it stops the app at start-up, naming the plugin.
 */
export function checkReservedPaths(plugins: readonly FrontendPlugin[]): void {
  const areas: NavArea[] = ['client', 'consultant', 'portfolio']
  for (const plugin of plugins) {
    const routes = areas.flatMap((area) => plugin.routes[area] ?? [])
    const clash =
      routes.some((r) => r.path !== undefined && trimSlashes(r.path) === INTEGRATIONS_PATH) ||
      (plugin.nav ?? []).some((n) => trimSlashes(n.path) === INTEGRATIONS_PATH)
    if (clash) {
      throw new Error(
        `Plugin "${plugin.id}": '${INTEGRATIONS_PATH}' is the shell's Integrações page. ` +
          `Use '${INTEGRATIONS_PATH}/<id>' for an evidence provider's page and menu entry.`,
      )
    }
  }
}

/** Every entry for one area that the user may see, by `order` then label. */
function visibleNav(plugins: readonly FrontendPlugin[], area: NavArea, can: Can): NavItem[] {
  return plugins
    .flatMap((p) => p.nav ?? [])
    .filter((n) => n.area === area && (!n.resource || can(n.resource, n.action)))
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100) || a.label.localeCompare(b.label, 'pt'))
}

/** Side menu entries for one area. Integrations are left out: they are reached through the "Integrações" page. */
export function pluginNav(plugins: readonly FrontendPlugin[], area: NavArea, can: Can): NavItem[] {
  return visibleNav(plugins, area, can).filter((n) => !isIntegration(n))
}

/** Entries under `integrations/` (evidence providers) that the user may see, for the "Integrações" page. */
export function integrationNav(plugins: readonly FrontendPlugin[], area: NavArea, can: Can): NavItem[] {
  return visibleNav(plugins, area, can).filter(isIntegration)
}
