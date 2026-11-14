import type { ReactNode } from 'react'
import { Outlet, type RouteObject } from 'react-router'
import type { Area, FrontendPlugin, NavItem, RouteHandle } from '@alinha/plugin-sdk'
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

/** Menu entries for one area that the user may see, by `order` then label. */
export function pluginNav(plugins: readonly FrontendPlugin[], area: Area, can: Can): NavItem[] {
  return plugins
    .flatMap((p) => p.nav ?? [])
    .filter((n) => n.area === area && (!n.resource || can(n.resource, n.action)))
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100) || a.label.localeCompare(b.label, 'pt'))
}
