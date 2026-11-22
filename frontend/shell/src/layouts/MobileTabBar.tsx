import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { Circle, Ellipsis } from 'lucide-react'
import { Drawer, cn } from '@alinha/ui'
import { isLinkActive, type FrameLink } from './frameLinks'

/** More entries than this and the last slot becomes "Mais" (the tab bar keeps every menu entry reachable). */
const MAX_TABS = 5

const tabClass = (active: boolean) =>
  cn('flex flex-1 flex-col items-center gap-0.5 py-1 text-[0.6875rem]', active ? 'font-bold text-brand' : 'font-semibold text-ink-soft')

function TabFace({ icon: Icon = Circle, label, active }: { icon?: FrameLink['icon']; label: string; active: boolean }) {
  return (
    <>
      <span className={cn('grid h-7 w-12 place-items-center rounded-full transition-colors', active && 'bg-brand-soft')}>
        <Icon className="size-[18px]" strokeWidth={active ? 2.25 : 1.75} aria-hidden />
      </span>
      {label}
    </>
  )
}

/** Bottom navigation on small screens (the side navigation is hidden there). */
export function MobileTabBar({ links }: { links: FrameLink[] }) {
  const { pathname } = useLocation()
  const [moreOpen, setMoreOpen] = useState(false)
  if (links.length === 0) return null
  const overflow = links.length > MAX_TABS
  const visible = overflow ? links.slice(0, MAX_TABS - 1) : links
  const more = overflow ? links.slice(MAX_TABS - 1) : []
  const moreActive = more.some((l) => isLinkActive(l, pathname))

  return (
    <nav aria-label="Navegação principal (telemóvel)" className="fixed inset-x-0 bottom-0 z-30 flex border-t border-line-soft bg-paper/95 pb-[max(env(safe-area-inset-bottom),6px)] pt-1.5 backdrop-blur md:hidden">
      {visible.map((link) => {
        const active = isLinkActive(link, pathname)
        return (
          <Link key={link.to} to={link.to} aria-current={active ? 'page' : undefined} className={tabClass(active)}>
            <TabFace icon={link.icon} label={link.shortLabel ?? link.label} active={active} />
          </Link>
        )
      })}
      {overflow && (
        <>
          <button type="button" className={tabClass(moreActive)} aria-haspopup="dialog" aria-expanded={moreOpen} onClick={() => setMoreOpen(true)}>
            <TabFace icon={Ellipsis} label="Mais" active={moreActive} />
          </button>
          <Drawer open={moreOpen} onOpenChange={setMoreOpen} title="Mais">
            <ul className="flex flex-col gap-1">
              {more.map((link) => {
                const active = isLinkActive(link, pathname)
                const Icon = link.icon ?? Circle
                return (
                  <li key={link.to}>
                    <Link
                      to={link.to}
                      onClick={() => setMoreOpen(false)}
                      aria-current={active ? 'page' : undefined}
                      className={cn('flex h-12 items-center gap-3 rounded-[12px] px-3 text-[0.9375rem]', active ? 'bg-brand-soft font-bold text-brand' : 'font-semibold text-ink hover:bg-paper-soft')}
                    >
                      <Icon className="size-5" aria-hidden />
                      {link.label}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </Drawer>
        </>
      )}
    </nav>
  )
}
