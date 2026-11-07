import js from '@eslint/js'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['**/dist/**', '**/build/**', '**/node_modules/**', '**/*.generated.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // Regra da arquitetura no frontend: um plugin só pode importar o SDK,
    // o design system e o cliente da API — nunca outro plugin nem o shell.
    files: ['plugins/**/frontend/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [
          { group: ['@alinha/plugin-*'], message: 'Plugins não importam outros plugins. Usa a API (contratos «api») ou eventos.' },
          { group: ['**/frontend/shell/**', '@alinha/shell'], message: 'Plugins não importam o shell. Usa @alinha/plugin-sdk.' },
          { group: ['../../../*'], message: 'Não saias da pasta do plugin.' },
        ],
      }],
    },
  },
)
