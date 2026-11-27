import { useId } from 'react'
import { Link, Navigate, Outlet, useNavigate, useParams } from 'react-router'
import { Briefcase, Building2, ChevronRight } from 'lucide-react'
import type { OrganizationRefDto } from '@alinha/api-client'
import type { FrontendPlugin } from '@alinha/plugin-sdk'
import { HostProvider } from '../host'
import { ForbiddenPage } from '../pages/StatusPages'
import { controlClass, EmptyState, PageHeader } from '@alinha/ui'
import { useSession } from '../session/SessionGate'
import { roleLabel } from '../session/permissions'
import { AppFrame, type FrameLink, type FrameSection } from './AppFrame'
import { areaLinks } from './areaLinks'

const PORTFOLIO: FrameLink = { to: '/portfolio', label: 'Carteira de clientes', shortLabel: 'Clientes', icon: Briefcase, end: true }

/** Organization switcher: changing it opens that client's area (/portfolio/:org). */
export function OrgSwitcher({ organizations, current }: { organizations: readonly OrganizationRefDto[]; current?: string }) {
  const navigate = useNavigate()
  const id = useId()
  if (organizations.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="eyebrow px-1 text-[0.75rem] text-muted">Cliente</label>
      <select
        id={id}
        className={controlClass(false, 'sm')}
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
  const portfolioLinks = areaLinks(plugins, 'portfolio', can, '/portfolio')
  const sections: FrameSection[] = [{ links: [PORTFOLIO, ...portfolioLinks] }]
  if (organization) {
    const links = areaLinks(plugins, 'consultant', can, `/portfolio/${organization.slug}`)
    sections.push({ heading: organization.name, links })
  }

  const content = org && !organization
    ? <ForbiddenPage title="Não tem acesso a esta organização">Esta organização não faz parte da sua carteira de clientes.</ForbiddenPage>
    : <Outlet />

  // key: another organization remounts every plugin page, so no local state (e.g. a form draft) carries over.
  return (
    <HostProvider key={organization?.id ?? 'portfolio'} organization={organization ?? null}>
      <AppFrame sections={sections} subtitle={roleLabel(me.roles)} tools={<OrgSwitcher organizations={me.organizations} current={organization?.slug} />}>
        {content}
      </AppFrame>
    </HostProvider>
  )
}

/** /portfolio: the consultant's client organizations. */
export function PortfolioPage() {
  const { me } = useSession()
  const count = me.organizations.length
  return (
    <>
      <PageHeader
        title="Carteira de clientes"
        subtitle={count === 0 ? undefined : count === 1 ? '1 organização acompanhada' : `${count} organizações acompanhadas`}
      />
      {count === 0 ? (
        <EmptyState icon={<Briefcase className="size-5" aria-hidden />} title="Ainda não tem clientes atribuídos">
          Quando lhe atribuírem uma organização, ela aparece aqui.
        </EmptyState>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2">
          {me.organizations.map((o) => (
            <li key={o.id}>
              <Link
                to={`/portfolio/${encodeURIComponent(o.slug)}`}
                className="group flex items-center gap-4 rounded-[14px] border border-line-soft bg-paper px-5 py-[18px] transition-[border-color,box-shadow] hover:border-line hover:shadow-[0_2px_10px_rgb(var(--shadow-rgb)/0.06)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
              >
                <span className="grid size-11 shrink-0 place-items-center rounded-[12px] bg-brand-soft text-brand">
                  <Building2 className="size-5" aria-hidden />
                </span>
                <span className="min-w-0 flex-1 truncate text-[0.9375rem] font-bold">{o.name}</span>
                <ChevronRight className="size-5 text-muted transition-transform group-hover:translate-x-0.5" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
