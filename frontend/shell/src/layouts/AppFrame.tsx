import { Suspense, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { Avatar, Logo, cn, initialsOf } from '@alinha/ui'
import { AppearanceMenu } from '../appearance/AppearanceMenu'
import { LoadingPage } from '../pages/StatusPages'
import { useSession } from '../session/SessionGate'
import { isLinkActive, type FrameLink, type FrameSection } from './frameLinks'
import { MobileTabBar } from './MobileTabBar'

export type { FrameLink, FrameSection } from './frameLinks'

interface AppFrameProps {
  sections: FrameSection[]
  /** Under the person's name: their organization or their role. */
  subtitle: string
  /** Extra controls under the logo (e.g. the consultant's client switcher). Desktop only: on mobile the tab bar reaches the same places. */
  tools?: ReactNode
  children: ReactNode
}

/**
 * Side navigation (232 px) and centred content on desktop; top bar and tab bar on mobile.
 * Layout and spacing from the MS3 prototype (frontend-prototype/src/app/AppShell.tsx).
 */
export function AppFrame({ sections, subtitle, tools, children }: AppFrameProps) {
  const { me } = useSession()
  const { pathname } = useLocation()
  const initials = initialsOf(me.user.name)

  return (
    <div className="min-h-dvh bg-paper md:flex">
      <a href="#conteudo" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded-[10px] focus:bg-brand focus:px-4 focus:py-2 focus:text-white">
        Saltar para o conteúdo
      </a>

      <aside aria-label="Barra lateral" className="sticky top-0 hidden h-dvh w-[232px] shrink-0 flex-col border-r border-line-soft bg-paper-soft px-5 pb-7 pt-8 md:flex">
        <div className="flex items-center justify-between pl-1">
          <Link to="/" aria-label="aLinha, página inicial" className="rounded-[8px] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-brand">
            <Logo />
          </Link>
          <AppearanceMenu />
        </div>
        {tools && <div className="mt-6">{tools}</div>}
        <nav aria-label="Navegação principal" className="mt-9 flex flex-1 flex-col gap-5 overflow-y-auto">
          {sections.map((section, i) => (
            <div key={section.heading ?? i} className="flex flex-col gap-1">
              {section.heading && <p className="eyebrow mb-1 truncate px-3 text-[0.75rem] text-muted">{section.heading}</p>}
              <ul className="flex flex-col gap-1">
                {section.links.map((link) => <li key={link.to}><SideLink link={link} active={isLinkActive(link, pathname)} /></li>)}
              </ul>
            </div>
          ))}
        </nav>
        <div className="flex items-center gap-2.5 border-t border-line-soft pt-4">
          <Avatar initials={initials} size={36} />
          <div className="min-w-0">
            <p className="truncate text-[0.8125rem] font-bold">{me.user.name}</p>
            <p className="truncate text-[0.75rem] text-muted">{subtitle}</p>
          </div>
        </div>
      </aside>

      <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-line-soft bg-paper/95 px-5 backdrop-blur md:hidden">
        <Link to="/" aria-label="aLinha, página inicial"><Logo className="h-7" /></Link>
        <div className="flex items-center gap-1">
          <AppearanceMenu account={{ initials, name: me.user.name, subtitle }} />
        </div>
      </header>

      <main id="conteudo" tabIndex={-1} className="min-w-0 flex-1 px-14 pb-16 pt-11 outline-none max-lg:px-8 max-md:px-5 max-md:pb-28 max-md:pt-6">
        <div className="mx-auto flex max-w-[1152px] flex-col gap-7">
          <Suspense fallback={<LoadingPage />}>{children}</Suspense>
        </div>
      </main>

      <MobileTabBar links={sections.flatMap((s) => s.links)} />
    </div>
  )
}

function SideLink({ link, active }: { link: FrameLink; active: boolean }) {
  const Icon = link.icon
  return (
    <Link
      to={link.to}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex h-10 items-center gap-2.5 rounded-[10px] px-3 text-[0.875rem] transition-colors duration-150',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand',
        active ? 'bg-brand-soft font-bold text-brand' : 'font-semibold text-ink-soft hover:bg-paper-deep/60 hover:text-ink',
      )}
    >
      {Icon && <Icon className="size-[18px] shrink-0" aria-hidden />}
      <span className="truncate">{link.label}</span>
    </Link>
  )
}
