import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { KcPage } from './kc.gen'
import './index.css'

// Before the first render, so a dark system does not flash a white page while the page loads.
document.documentElement.dataset.theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'

// `pnpm dev` has no Keycloak: render a mocked page, chosen with ?page= (default login.ftl).
// The mock is imported only in dev, so it is not in the theme bundle.
if (import.meta.env.DEV && !window.kcContext) {
  const { getKcContextMock } = await import('./login/mock')
  const pageId = (new URLSearchParams(location.search).get('page') ?? 'login.ftl') as Parameters<typeof getKcContextMock>[0]['pageId']
  window.kcContext = getKcContextMock({ pageId, overrides: {} })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>{window.kcContext ? <KcPage kcContext={window.kcContext} /> : <h1>No Keycloak context</h1>}</StrictMode>,
)
