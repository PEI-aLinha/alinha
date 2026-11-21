import { Navigate, useParams, type RouteObject } from 'react-router'
import type { FrontendPlugin, NavArea } from '@alinha/plugin-sdk'
import { ClientLayout } from '../layouts/ClientLayout'
import { ConsultantLayout, PortfolioPage } from '../layouts/ConsultantLayout'
import { OnboardingLayout } from '../layouts/OnboardingLayout'
import { IntegrationsPage } from '../pages/IntegrationsPage'
import { ForbiddenPage, NotFoundPage, RouteErrorPage, StatusPage } from '../pages/StatusPages'
import { SessionGate, useSession } from '../session/SessionGate'
import { isClientUser, needsOnboarding } from '../session/permissions'
import { AREA_BASE } from './areaBase'
import { INTEGRATIONS_PATH, checkReservedPaths, integrationNav, pluginNav, pluginRoutes } from './plugins'

export { AREA_BASE }

const isStaticPath = (path: string) => !path.split('/').some((s) => s.startsWith(':') || s.includes('*'))

const joinPath = (prefix: string, path: string) => (prefix ? `${prefix}/${path}` : path)

/**
 * The first route with a static path that shows a page, looking inside pathless layout routes and joining nested
 * paths. A route with children only shows a page of its own through an index child; otherwise the address goes to
 * its first static child. Routes with a dynamic segment (`:step`) or a splat are skipped: there is no value to put
 * in the address.
 */
function firstStaticPath(routes: readonly RouteObject[], prefix = ''): string | undefined {
  for (const route of routes) {
    if (route.index) continue
    if (!route.path) {
      const nested = firstStaticPath(route.children ?? [], prefix)
      if (nested) return nested
      continue
    }
    if (!isStaticPath(route.path)) continue
    const path = joinPath(prefix, route.path)
    const children = route.children ?? []
    if (children.length === 0 || children.some((child) => child.index)) return path
    const nested = firstStaticPath(children, path)
    if (nested) return nested
  }
  return undefined
}

/** Path of the first plugin onboarding page (e.g. 'organization'), if a plugin has one. */
export function firstOnboardingPath(plugins: readonly FrontendPlugin[]): string | undefined {
  return firstStaticPath(plugins.flatMap((p) => p.routes.onboarding ?? []))
}

/**
 * "/": the consultant goes to the portfolio, a client user to their organization, and a new account that has
 * not registered its organization yet to the onboarding.
 */
export function HomeRedirect({ plugins }: { plugins: readonly FrontendPlugin[] }) {
  const { me, isConsultant } = useSession()
  if (isConsultant) return <Navigate to="/portfolio" replace />
  const [own] = me.organizations
  if (isClientUser(me.roles) && own) return <Navigate to={`/org/${encodeURIComponent(own.slug)}`} replace />
  const onboarding = firstOnboardingPath(plugins)
  if (needsOnboarding(me.roles, me.organizations) && onboarding) return <Navigate to={`${AREA_BASE.onboarding}/${onboarding}`} replace />
  return (
    <ForbiddenPage title="A sua conta ainda não está pronta">
      A sua conta ainda não está associada a nenhuma organização. Fale com o responsável da sua organização ou com o suporte.
    </ForbiddenPage>
  )
}

/**
 * Landing page of an organization: the first menu entry, else the Integrações page when the user only sees evidence
 * providers (they are not in pluginNav), else a short note.
 */
function AreaHome({ plugins, area }: { plugins: readonly FrontendPlugin[]; area: NavArea }) {
  const { can } = useSession()
  const { org = '' } = useParams()
  const base = `${AREA_BASE[area]}/${encodeURIComponent(org)}`
  const [first] = pluginNav(plugins, area, can)
  if (first) return <Navigate to={`${base}/${first.path}`} replace />
  if (integrationNav(plugins, area, can).length > 0) return <Navigate to={`${base}/${INTEGRATIONS_PATH}`} replace />
  return <StatusPage title="Ainda não há páginas disponíveis">Quando houver páginas para o seu perfil, aparecem no menu.</StatusPage>
}

const areaChildren = (plugins: readonly FrontendPlugin[], area: NavArea): RouteObject[] => [
  { index: true, element: <AreaHome plugins={plugins} area={area} /> },
  { path: INTEGRATIONS_PATH, element: <IntegrationsPage plugins={plugins} area={area} /> },
  ...pluginRoutes(plugins, area),
  { path: '*', element: <NotFoundPage /> },
]

/**
 * The whole route tree. Routes come from the plugins: no plugin is referenced by hand here.
 *   /org/:org/<path>        client area (plugins' `client` routes)
 *   /portfolio              consultant portfolio (client list)
 *   /portfolio/:org/<path>  consultant area for one client (plugins' `consultant` routes)
 *   /onboarding/<path>      new account registering its organization (plugins' `onboarding` routes)
 */
export function createRoutes(plugins: readonly FrontendPlugin[]): RouteObject[] {
  checkReservedPaths(plugins)
  return [
    {
      path: '/',
      element: <SessionGate />,
      errorElement: <RouteErrorPage />,
      children: [
        { index: true, element: <HomeRedirect plugins={plugins} /> },
        {
          path: 'org/:org',
          element: <ClientLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: areaChildren(plugins, 'client'),
        },
        {
          path: 'portfolio',
          element: <ConsultantLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: [{ index: true, element: <PortfolioPage /> }],
        },
        {
          path: 'portfolio/:org',
          element: <ConsultantLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: areaChildren(plugins, 'consultant'),
        },
        {
          path: 'onboarding',
          element: <OnboardingLayout />,
          errorElement: <RouteErrorPage />,
          children: [
            { index: true, element: <Navigate to="/" replace /> },
            ...pluginRoutes(plugins, 'onboarding'),
            { path: '*', element: <NotFoundPage /> },
          ],
        },
        { path: '*', element: <NotFoundPage /> },
      ],
    },
  ]
}
