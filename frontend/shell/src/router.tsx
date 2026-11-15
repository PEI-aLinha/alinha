import { createBrowserRouter } from 'react-router'
import { plugins } from './plugins.generated'
import { createRoutes } from './routing/routes'

export const router = createBrowserRouter(createRoutes(plugins))
