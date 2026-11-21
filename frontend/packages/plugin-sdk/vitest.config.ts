import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      // lcov paths relative to the repo root, so SonarCloud can map them (sonar-project.properties).
      reporter: ['text-summary', 'text', ['lcov', { projectRoot: '../../..' }]],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}'],
      // RNF19: coverage target ≥ 70%.
      thresholds: { lines: 70, statements: 70, functions: 70, branches: 70 },
    },
  },
})
