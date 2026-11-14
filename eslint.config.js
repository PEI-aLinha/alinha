import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginBoundaries from './eslint-rules/plugin-boundaries.js'

export default tseslint.config(
  { ignores: ['**/dist/**', '**/build/**', '**/node_modules/**', '**/*.generated.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // Build scripts, local ESLint rules and their tests run in Node.
    files: ['scripts/**/*.{js,mjs}', 'eslint-rules/**/*.js'],
    languageOptions: { globals: { console: 'readonly', process: 'readonly' } },
  },
  {
    // Architecture rule on the frontend: a plugin may only import the SDK,
    // the design system and the API client — never another plugin or the shell.
    // See eslint-rules/plugin-boundaries.js (tests: eslint-rules/plugin-boundaries.test.js).
    files: ['plugins/**/frontend/**/*.{js,mjs,ts,tsx}'],
    plugins: { alinha: { rules: { 'plugin-boundaries': pluginBoundaries } } },
    rules: { 'alinha/plugin-boundaries': 'error' },
  },
)
