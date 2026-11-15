/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, loadEnv } from 'vite'
import { devSession } from './dev/devSession'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss(), devSession(loadEnv(mode, process.cwd(), 'VITE_').VITE_DEV_ROLE)],
  resolve: {
    // Plugins live outside this folder (plugins/*/*/frontend): make sure the
    // shell and every plugin share a single copy of React/Router.
    dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query'],
  },
  server: {
    proxy: {
      // In production this is Nginx → BFF. In dev, point it at the BFF (or the app).
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}', 'dev/**/*.test.ts'],
    setupFiles: ['./src/test-setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'text'],
      include: ['src/**/*.{ts,tsx}', 'dev/**/*.ts'],
      exclude: ['src/**/*.test.{ts,tsx}', 'dev/**/*.test.ts', 'src/test-setup.ts', 'src/main.tsx', 'src/router.tsx', 'src/plugins.generated.ts'],
      // RNF19: coverage target ≥ 70%.
      thresholds: { lines: 70, statements: 70, functions: 70, branches: 70 },
    },
  },
}))
