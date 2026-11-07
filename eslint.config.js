import js from '@eslint/js'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['**/dist/**', '**/build/**', '**/node_modules/**', '**/*.generated.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // Architecture rule on the frontend: a plugin may only import the SDK,
    // the design system and the API client — never another plugin or the shell.
    files: ['plugins/**/frontend/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [
          { group: ['@alinha/plugin-*', '!@alinha/plugin-sdk'], message: 'Plugins do not import other plugins. Use the API («api» contracts) or events.' },
          { group: ['**/frontend/shell/**', '@alinha/shell'], message: 'Plugins do not import the shell. Use @alinha/plugin-sdk.' },
          { group: ['../../../*'], message: 'Do not reach outside the plugin folder.' },
        ],
      }],
    },
  },
)
