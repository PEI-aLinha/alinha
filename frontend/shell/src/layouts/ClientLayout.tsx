import { Navigate, Outlet, useParams } from 'react-router'
import type { FrontendPlugin } from '@alinha/plugin-sdk'
import { HostProvider } from '../host'
import { ForbiddenPage } from '../pages/StatusPages'
import { pluginNav } from '../routing/plugins'
import { useSession } from '../session/SessionGate'
import { isClientUser } from '../session/permissions'
import { AppFrame } from './AppFrame'

/** /org/:org — the designated responsible person and analysts, inside their own organization. */
export function ClientLayout({ plugins }: { plugins: readonly FrontendPlugin[] }) {
  const { org = '' } = useParams()
  const { me, can, isConsultant } = useSession()

  if (isConsultant) return <Navigate to={`/carteira/${encodeURIComponent(org)}`} replace />
  if (!isClientUser(me.roles)) return <ForbiddenPage />

  const organization = me.organizations.find((o) => o.slug === org)
  if (!organization) {
    return <ForbiddenPage title="Não tem acesso a esta organização">Esta organização não está associada à sua conta.</ForbiddenPage>
  }

  const base = `/org/${organization.slug}`
  const links = pluginNav(plugins, 'client', can).map((n) => ({ to: `${base}/${n.path}`, label: n.label }))
  // key: another organization remounts every plugin page, so no local state carries over (same as ConsultantLayout).
  return (
    <HostProvider key={organization.id} organization={organization}>
      <AppFrame sections={[{ heading: organization.name, links }]}>
        <Outlet />
      </AppFrame>
    </HostProvider>
  )
}
