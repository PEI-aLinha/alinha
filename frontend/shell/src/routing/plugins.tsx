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

/** Wraps a plugin route (and its children) whose `handle.resource` asks for a permission. */
function guard(route: RouteObject): RouteObject {
  const children = route.children?.map(guard)
  const handle = route.handle as RouteHandle | undefined
  if (!handle?.resource) return (children ? { ...route, children } : route) as RouteObject
  const { Component, element, ...rest } = route
  const content = element ?? (Component ? <Component /> : <Outlet />)
  return {
    ...rest,
    ...(children ? { children } : {}),
    element: <RequirePermission resource={handle.resource} action={handle.action}>{content}</RequirePermission>,
  } as RouteObject
}

/** Every plugin's routes for one area, in plugin order, guarded by permission. */
export function pluginRoutes(plugins: readonly FrontendPlugin[], area: Area): RouteObject[] {
  return plugins.flatMap((p) => p.routes[area] ?? []).map(guard)
}

/** Menu entries for one area that the user may see, by `order` then label. */
export function pluginNav(plugins: readonly FrontendPlugin[], area: Area, can: Can): NavItem[] {
  return plugins
    .flatMap((p) => p.nav ?? [])
    .filter((n) => n.area === area && (!n.resource || can(n.resource)))
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100) || a.label.localeCompare(b.label, 'pt'))
}
