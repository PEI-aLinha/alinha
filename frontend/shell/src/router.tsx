import { createBrowserRouter, Navigate } from 'react-router'
import { plugins } from './plugins.generated'
import { AreaLayout } from './layouts'

const routesFor = (area: 'client' | 'consultant') =>
  plugins.flatMap((p) => p.routes[area] ?? [])

/**
 * Routes come from the plugins: no plugin is referenced by hand here.
 * (The prototype's registration/onboarding routes belong to the Organization plugin.)
 */
export const router = createBrowserRouter([
  { index: true, element: <Navigate to="/carteira" replace /> },
  {
    path: 'org/:org',
    element: <AreaLayout area="client" />,
    children: routesFor('client'),
  },
  {
    path: 'carteira',
    element: <AreaLayout area="consultant" />,
    children: routesFor('consultant'),
  },
])
