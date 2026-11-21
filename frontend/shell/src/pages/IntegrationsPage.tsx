import { Link, useParams } from 'react-router'
import { ChevronRight, Plug } from 'lucide-react'
import type { FrontendPlugin, NavArea } from '@alinha/plugin-sdk'
import { EmptyState, PageHeader } from '@alinha/ui'
import { integrationNav } from '../routing/plugins'
import { AREA_BASE } from '../routing/areaBase'
import { useSession } from '../session/SessionGate'

/**
 * /org/:org/integrations (and /portfolio/:org/integrations): one card per evidence provider the user may see.
 * Owned by the shell so the menu keeps a single "Integrações" entry however many providers are installed.
 */
export function IntegrationsPage({ plugins, area }: { plugins: readonly FrontendPlugin[]; area: NavArea }) {
  const { can } = useSession()
  const { org = '' } = useParams()
  const items = integrationNav(plugins, area, can)
  const base = `${AREA_BASE[area]}/${encodeURIComponent(org)}`

  return (
    <>
      <PageHeader
        title="Integrações"
        subtitle="Ligações a sistemas da organização que recolhem provas automaticamente, só com acesso de leitura."
      />
      {items.length === 0 ? (
        <EmptyState icon={<Plug className="size-5" aria-hidden />} title="Ainda não há integrações disponíveis">
          Quando houver integrações para o seu perfil, aparecem aqui.
        </EmptyState>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2">
          {items.map((item) => {
            const Icon = item.icon ?? Plug
            return (
              <li key={item.path}>
                <Link
                  to={`${base}/${item.path}`}
                  className="group flex items-center gap-4 rounded-[14px] border border-line-soft bg-paper px-5 py-[18px] transition-[border-color,box-shadow] hover:border-line hover:shadow-[0_2px_10px_rgb(var(--shadow-rgb)/0.06)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                >
                  <span className="grid size-11 shrink-0 place-items-center rounded-[12px] bg-brand-soft text-brand">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1 text-[0.9375rem] font-bold">{item.label}</span>
                  <ChevronRight className="size-5 text-muted transition-transform group-hover:translate-x-0.5" aria-hidden />
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
