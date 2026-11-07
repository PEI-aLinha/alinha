import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // Os plugins vivem fora desta pasta (plugins/*/*/frontend): garantir
    // uma única cópia de React/Router para o shell e para todos os plugins.
    dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query'],
  },
  server: {
    proxy: {
      // Em produção é o Nginx → BFF. Em dev aponta para o BFF (ou para a app).
      '/api': 'http://localhost:8080',
    },
  },
})
