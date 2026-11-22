import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ToastProvider } from '@alinha/ui'
import { AppearanceProvider } from './appearance/AppearanceProvider'

/**
 * App-wide providers, shared by main.tsx and the tests: theme and colour vision, session queries and the toasts
 * plugins show with useToast().
 */
export function AppProviders({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  return (
    <AppearanceProvider>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </AppearanceProvider>
  )
}
