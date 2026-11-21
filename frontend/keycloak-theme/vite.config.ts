/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'
import { keycloakify } from 'keycloakify/vite-plugin'

// Keycloak login theme. `pnpm build-keycloak-theme` writes dist_keycloak/alinha-keycloak-theme.jar,
// which deploy/docker-compose.dev.yml mounts into Keycloak's providers folder.
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    keycloakify({
      themeName: 'alinha',
      accountThemeImplementation: 'none',
      keycloakVersionTargets: { '22-to-25': false, 'all-other-versions': 'alinha-keycloak-theme.jar' },
    }),
  ],
  // 5173 is the shell's.
  server: { port: 5174 },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary'],
      // Logic only: the pages are checked against a running Keycloak (see README).
      include: ['src/login/passwordRules.ts', 'src/login/text.ts'],
      thresholds: { lines: 70, statements: 70, functions: 70, branches: 70 },
    },
  },
})
