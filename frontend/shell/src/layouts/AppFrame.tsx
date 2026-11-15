import { Suspense, type ReactNode } from 'react'
import { Link, NavLink } from 'react-router'
import { LoadingPage } from '../pages/StatusPages'
import { useSession } from '../session/SessionGate'
import { roleLabel } from '../session/permissions'

export interface FrameLink {
  to: string
  label: string
  /** Active only on this exact path (e.g. the portfolio, which is the parent of every client page). */
  end?: boolean
}

export interface FrameSection {
  heading?: string
  links: FrameLink[]
}

/** Side navigation + main content, shared by the client and consultant areas. */
export function AppFrame({ sections, tools, children }: { sections: FrameSection[]; tools?: ReactNode; children: ReactNode }) {
  const { me } = useSession()
  return (
    <div className="flex min-h-screen">
      <a href="#conteudo" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:rounded-md focus:bg-white focus:px-4 focus:py-2">
        Saltar para o conteúdo
      </a>
      <aside className="flex w-60 shrink-0 flex-col gap-6 border-r p-4">
        <Link to="/" className="text-lg font-semibold">aLinha</Link>
        {tools}
        <nav aria-label="Navegação principal" className="flex flex-1 flex-col gap-5">
          {sections.map((section, i) => (
            <div key={section.heading ?? i} className="flex flex-col gap-1">
              {section.heading && <p className="px-2 text-xs font-semibold uppercase text-gray-500">{section.heading}</p>}
              <ul className="flex flex-col gap-1">
                {section.links.map((link) => (
                  <li key={link.to}>
                    <NavLink
                      to={link.to}
                      end={link.end}
                      className={({ isActive }) => `block rounded-md px-2 py-1.5 ${isActive ? 'bg-gray-100 font-semibold' : 'hover:bg-gray-50'}`}
                    >
                      {link.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <div className="border-t pt-4 text-sm">
          <p className="font-semibold">{me.user.name}</p>
          <p className="text-gray-600">{roleLabel(me.roles)}</p>
        </div>
      </aside>
      <main id="conteudo" className="min-w-0 flex-1 p-8">
        <Suspense fallback={<LoadingPage />}>{children}</Suspense>
      </main>
    </div>
  )
}
