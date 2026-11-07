import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
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
})
