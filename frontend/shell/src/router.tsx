import { createBrowserRouter, Navigate } from 'react-router'
import { plugins } from './plugins.generated'
import { AreaLayout } from './layouts'

const routesFor = (area: 'client' | 'consultant') =>
  plugins.flatMap((p) => p.routes[area] ?? [])

/**
 * As rotas vêm dos plugins: nenhum plugin é referido à mão aqui.
 * (As rotas de registo/onboarding do protótipo pertencem ao plugin Organization.)
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
