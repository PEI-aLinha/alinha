import { Navigate, Outlet, useLocation, useParams } from 'react-router'
import type { FrontendPlugin } from '@alinha/plugin-sdk'
import { HostProvider } from '../host'
import { ForbiddenPage } from '../pages/StatusPages'
import { useSession } from '../session/SessionGate'
import { isClientUser, roleLabel } from '../session/permissions'
import { AppFrame } from './AppFrame'
import { areaLinks } from './areaLinks'

/** /org/:org — the designated responsible person and analysts, inside their own organization. */
export function ClientLayout({ plugins }: { plugins: readonly FrontendPlugin[] }) {
  const { org = '' } = useParams()
  const { pathname, search, hash } = useLocation()
  const { me, can, isConsultant } = useSession()

  if (isConsultant) {
    // Same page in the consultant area: keep what comes after /org/:org, the query and the hash.
    const rest = pathname.replace(/^\/org\/[^/]+/, '')
    return <Navigate to={`/portfolio/${encodeURIComponent(org)}${rest}${search}${hash}`} replace />
  }
  if (!isClientUser(me.roles)) return <ForbiddenPage />

  const organization = me.organizations.find((o) => o.slug === org)
  if (!organization) {
    return <ForbiddenPage title="Não tem acesso a esta organização">Esta organização não está associada à sua conta.</ForbiddenPage>
  }

  const links = areaLinks(plugins, 'client', can, `/org/${organization.slug}`)

  // key: another organization remounts every plugin page, so no local state carries over (same as ConsultantLayout).
  return (
    <HostProvider key={organization.id} organization={organization}>
      <AppFrame sections={[{ heading: organization.name, links }]} subtitle={roleLabel(me.roles)}>
        <Outlet />
      </AppFrame>
    </HostProvider>
  )
}
