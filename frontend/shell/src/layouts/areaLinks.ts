import { Plug } from 'lucide-react'
import type { FrontendPlugin, NavArea, NavItem } from '@alinha/plugin-sdk'
import { INTEGRATIONS_PATH, integrationNav, pluginNav } from '../routing/plugins'
import type { Can } from '../session/permissions'
import type { FrameLink } from './frameLinks'

/** Menu position of the shell's own "Integrações" entry (plugins use `order` on the same scale). */
const INTEGRATIONS_ORDER = 70

const DEFAULT_ORDER = 100

/**
 * Side menu of an organization area, under `base` (e.g. /org/acme): the plugins' entries plus one "Integrações"
 * entry when the user can see at least one evidence provider.
 */
export function areaLinks(plugins: readonly FrontendPlugin[], area: NavArea, can: Can, base: string): FrameLink[] {
  const entries: Pick<NavItem, 'label' | 'path' | 'icon' | 'order'>[] = [...pluginNav(plugins, area, can)]
  if (integrationNav(plugins, area, can).length > 0) {
    const at = entries.findIndex((n) => (n.order ?? DEFAULT_ORDER) > INTEGRATIONS_ORDER)
    const hub = { label: 'Integrações', path: INTEGRATIONS_PATH, icon: Plug, order: INTEGRATIONS_ORDER }
    entries.splice(at === -1 ? entries.length : at, 0, hub)
  }
  return entries.map((n) => ({ to: `${base}/${n.path}`, label: n.label, icon: n.icon }))
}
