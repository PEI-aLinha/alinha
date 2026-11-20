import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { AppProviders } from './AppProviders'
import { router } from './router'
import './index.css'

// Session-level queries (/api/me). Each organization gets its own client (see HostProvider).
const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
