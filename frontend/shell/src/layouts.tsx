import { NavLink, Outlet } from 'react-router'
import { useHost, type Area } from '@alinha/plugin-sdk'
import { plugins } from './plugins.generated'

/** Minimal layout: menu built from each plugin's `nav`. */
export function AreaLayout({ area }: { area: Area }) {
  const { can } = useHost()
  const items = plugins
    .flatMap((p) => p.nav ?? [])
    .filter((n) => n.area === area && (!n.resource || can(n.resource)))
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100))

  return (
    <div className="flex min-h-screen">
      <nav className="w-56 border-r p-4">
        <div className="mb-6 text-lg font-semibold">aLinha</div>
        <ul className="space-y-1">
          {items.map((n) => (
            <li key={n.path}>
              <NavLink to={n.path} className={({ isActive }) => (isActive ? 'font-semibold' : '')}>
                {n.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main className="flex-1 p-8">
        <Outlet />
      </main>
    </div>
  )
}
