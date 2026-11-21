import { useId } from 'react'
import { Link, Navigate, Outlet, useNavigate, useParams } from 'react-router'
import type { OrganizationRefDto } from '@alinha/api-client'
import type { FrontendPlugin } from '@alinha/plugin-sdk'
import { HostProvider } from '../host'
import { ForbiddenPage, StatusPage } from '../pages/StatusPages'
import { pluginNav } from '../routing/plugins'
import { useSession } from '../session/SessionGate'
import { AppFrame, type FrameSection } from './AppFrame'

const PORTFOLIO = { to: '/portfolio', label: 'Carteira de clientes', end: true }

/** Organization switcher: changing it opens that client's area (/portfolio/:org). */
export function OrgSwitcher({ organizations, current }: { organizations: readonly OrganizationRefDto[]; current?: string }) {
  const navigate = useNavigate()
  const id = useId()
  if (organizations.length === 0) return null
  return (
    <div className="flex flex-col gap-1 text-sm">
      <label htmlFor={id} className="font-semibold">Cliente</label>
      <select
        id={id}
        className="rounded-md border border-line bg-paper px-2 py-1.5"
        value={current ?? ''}
        onChange={(e) => e.target.value && navigate(`/portfolio/${encodeURIComponent(e.target.value)}`)}
      >
        <option value="" disabled>Escolher cliente</option>
        {organizations.map((o) => <option key={o.id} value={o.slug}>{o.name}</option>)}
      </select>
    </div>
  )
}

/**
 * /portfolio, /portfolio/<path> and /portfolio/:org — the consultant: the client list and the plugins' pages across
 * all clients (no organization), or one client's pages with the organization switcher.
 */
export function ConsultantLayout({ plugins }: { plugins: readonly FrontendPlugin[] }) {
  const { org } = useParams()
  const { me, can, isConsultant } = useSession()
  if (!isConsultant) return <Navigate to="/" replace />

  const organization = org ? me.organizations.find((o) => o.slug === org) : undefined
  const portfolioLinks = pluginNav(plugins, 'portfolio', can).map((n) => ({ to: `/portfolio/${n.path}`, label: n.label }))
  const sections: FrameSection[] = [{ links: [PORTFOLIO, ...portfolioLinks] }]
  if (organization) {
    const base = `/portfolio/${organization.slug}`
    const links = pluginNav(plugins, 'consultant', can).map((n) => ({ to: `${base}/${n.path}`, label: n.label }))
    sections.push({ heading: organization.name, links })
  }

  const content = org && !organization
    ? <ForbiddenPage title="Não tem acesso a esta organização">Esta organização não faz parte da sua carteira de clientes.</ForbiddenPage>
    : <Outlet />

  // key: another organization remounts every plugin page, so no local state (e.g. a form draft) carries over.
  return (
    <HostProvider key={organization?.id ?? 'portfolio'} organization={organization ?? null}>
      <AppFrame sections={sections} tools={<OrgSwitcher organizations={me.organizations} current={organization?.slug} />}>
        {content}
      </AppFrame>
    </HostProvider>
  )
}

/** /portfolio: the consultant's client organizations. */
export function PortfolioPage() {
  const { me } = useSession()
  if (me.organizations.length === 0) {
    return <StatusPage title="Carteira de clientes">Ainda não tem clientes atribuídos. Quando lhe atribuírem uma organização, ela aparece aqui.</StatusPage>
  }
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">Carteira de clientes</h1>
      <ul className="flex flex-col gap-2">
        {me.organizations.map((o) => (
          <li key={o.id}>
            <Link to={`/portfolio/${encodeURIComponent(o.slug)}`} className="block rounded-md border border-line-soft px-4 py-3 hover:bg-paper-soft">{o.name}</Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
