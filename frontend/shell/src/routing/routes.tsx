import { Navigate, useParams, type RouteObject } from 'react-router'
import type { Area, FrontendPlugin } from '@alinha/plugin-sdk'
import { ClientLayout } from '../layouts/ClientLayout'
import { ConsultantLayout, PortfolioPage } from '../layouts/ConsultantLayout'
import { ForbiddenPage, NotFoundPage, RouteErrorPage, StatusPage } from '../pages/StatusPages'
import { SessionGate, useSession } from '../session/SessionGate'
import { isClientUser } from '../session/permissions'
import { pluginNav, pluginRoutes } from './plugins'

/** "/": the consultant goes to the portfolio, a client user to their organization. */
export function HomeRedirect() {
  const { me, isConsultant } = useSession()
  if (isConsultant) return <Navigate to="/carteira" replace />
  const [own] = me.organizations
  if (isClientUser(me.roles) && own) return <Navigate to={`/org/${encodeURIComponent(own.slug)}`} replace />
  return (
    <ForbiddenPage title="A sua conta ainda não está pronta">
      A sua conta ainda não está associada a nenhuma organização. Fale com o responsável da sua organização ou com o suporte.
    </ForbiddenPage>
  )
}

/** Landing page of an organization: the first menu entry, or a short note when there is none. */
function AreaHome({ plugins, area }: { plugins: readonly FrontendPlugin[]; area: Area }) {
  const { can } = useSession()
  const { org = '' } = useParams()
  const [first] = pluginNav(plugins, area, can)
  if (first) return <Navigate to={`${area === 'client' ? '/org' : '/carteira'}/${encodeURIComponent(org)}/${first.path}`} replace />
  return <StatusPage title="Ainda não há páginas disponíveis">Quando houver páginas para o seu perfil, aparecem no menu.</StatusPage>
}

const areaChildren = (plugins: readonly FrontendPlugin[], area: Area): RouteObject[] => [
  { index: true, element: <AreaHome plugins={plugins} area={area} /> },
  ...pluginRoutes(plugins, area),
  { path: '*', element: <NotFoundPage /> },
]

/**
 * The whole route tree. Routes come from the plugins: no plugin is referenced by hand here.
 *   /org/:org/<path>       client area (plugins' `client` routes)
 *   /carteira              consultant portfolio
 *   /carteira/:org/<path>  consultant area for one client (plugins' `consultant` routes)
 */
export function createRoutes(plugins: readonly FrontendPlugin[]): RouteObject[] {
  return [
    {
      path: '/',
      element: <SessionGate />,
      errorElement: <RouteErrorPage />,
      children: [
        { index: true, element: <HomeRedirect /> },
        {
          path: 'org/:org',
          element: <ClientLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: areaChildren(plugins, 'client'),
        },
        {
          path: 'carteira',
          element: <ConsultantLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: [{ index: true, element: <PortfolioPage /> }],
        },
        {
          path: 'carteira/:org',
          element: <ConsultantLayout plugins={plugins} />,
          errorElement: <RouteErrorPage />,
          children: areaChildren(plugins, 'consultant'),
        },
        { path: '*', element: <NotFoundPage /> },
      ],
    },
  ]
}
