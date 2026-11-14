// Run with: pnpm test
import { after, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { ESLint, RuleTester } from 'eslint'
import tseslint from 'typescript-eslint'
import rule from './plugin-boundaries.js'

const root = resolve(import.meta.dirname, '..')
const plugin = (file) => resolve(root, 'plugins/feature/assessment/frontend', file)

RuleTester.describe = describe
RuleTester.it = it
RuleTester.afterAll = after

const tester = new RuleTester({
  languageOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module' },
})

const err = (messageId) => ({ errors: [{ messageId }] })

tester.run('plugin-boundaries', rule, {
  valid: [
    { filename: plugin('src/index.ts'), code: "import { definePlugin } from '@alinha/plugin-sdk'" },
    { filename: plugin('src/index.ts'), code: "import { PageHeader } from '@alinha/ui'" },
    { filename: plugin('src/index.ts'), code: "import * as api from '@alinha/api-client'" },
    { filename: plugin('src/index.ts'), code: "import { Button } from '@alinha/ui/button'" },
    { filename: plugin('src/index.ts'), code: "import { useState } from 'react'" },
    { filename: plugin('src/index.ts'), code: "import { Link } from 'react-router'" },
    { filename: plugin('src/index.ts'), code: "import { Page } from './pages/Page.tsx'" },
    // Deeply nested files may still import their own plugin's code.
    { filename: plugin('src/pages/a/b/Page.tsx'), code: "import { use } from '../../../hooks/use.ts'" },
    { filename: plugin('src/pages/a/Page.tsx'), code: "import x from '../../../src/index.ts'" },
    { filename: plugin('src/routes.ts'), code: "const Page = () => import('./pages/Page.tsx')" },
    { filename: plugin('src/routes.ts'), code: 'const Page = () => import(`./pages/Page.tsx`)' },
    // Files outside plugins are not restricted by this rule.
    { filename: resolve(root, 'frontend/shell/src/main.tsx'), code: "import x from '@alinha/shell'" },
    { filename: resolve(root, 'frontend/shell/src/main.tsx'), code: "import x from '../../../plugins/feature/assessment/frontend/src/index.ts'" },
  ],
  invalid: [
    { filename: plugin('src/index.ts'), code: "import x from '@alinha/plugin-risk-assets'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x from '@alinha/plugin-risk-assets/src/index.ts'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x from '@alinha/plugin-sdk-fake'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x from '@alinha/shell'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import type { X } from '@alinha/plugin-risk-assets'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "export { default } from '@alinha/plugin-risk-assets'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "export * from '@alinha/shell'", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "const m = () => import('@alinha/plugin-risk-assets')", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "const m = require('@alinha/plugin-risk-assets')", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x = require('@alinha/plugin-risk-assets')", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "type T = import('@alinha/plugin-risk-assets').Thing", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../risk-assets/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/pages/a/b/Page.tsx'), code: "import x from '../../../../../../risk-assets/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../../provider/code-repository/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../../../frontend/shell/src/host.tsx'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../backend/src/main/kotlin/x.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '/etc/passwd'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: 'const m = (name: string) => import(name)', ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: 'const m = (name: string) => import(`./${name}`)', ...err('dynamic') },
    // Vite: import.meta.glob and new URL(..., import.meta.url) reach files too.
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('../../../risk-assets/frontend/src/**/*.tsx')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob(['./pages/*.tsx', '../../../../frontend/shell/src/*.tsx'])", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('/src/**/*.tsx')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: 'const pages = (p: string) => import.meta.glob(p)', ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const url = new URL('../../../risk-assets/frontend/logo.svg', import.meta.url)", ...err('outside') },
    { filename: plugin('src/index.ts'), code: 'const url = (p: string) => new URL(p, import.meta.url)', ...err('dynamic') },
    // The repo root comes from an option (eslint.config.js passes its own folder), not from ESLint's cwd.
    {
      filename: '/elsewhere/repo/plugins/feature/x/frontend/src/a.ts',
      options: [{ root: '/elsewhere/repo' }],
      code: "import y from '@alinha/plugin-y'",
      ...err('platformPackage'),
    },
  ],
})

tester.run('plugin-boundaries (Vite paths that stay inside)', rule, {
  valid: [
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('./pages/**/*.tsx')" },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob(['./pages/*.tsx', '!./pages/_*.tsx'])" },
    { filename: plugin('src/index.ts'), code: "const url = new URL('./assets/logo.svg', import.meta.url)" },
    { filename: plugin('src/index.ts'), code: "const url = new URL('https://example.org/x')" },
  ],
  invalid: [],
})

describe('eslint.config.js wiring', () => {
  const eslint = new ESLint({ cwd: root })
  const ruleIds = async (filePath, code) => {
    const [result] = await eslint.lintText(`${code}\nexport {}\n`, { filePath: resolve(root, filePath) })
    return result.messages.map((m) => m.ruleId)
  }

  for (const kind of ['feature/assessment', 'provider/code-repository']) {
    it(`applies the rule to plugins/${kind} (.ts and .tsx)`, async () => {
      for (const ext of ['ts', 'tsx']) {
        const ids = await ruleIds(`plugins/${kind}/frontend/src/pages/Probe.${ext}`, "import x from '@alinha/plugin-organization'")
        assert.ok(ids.includes('alinha/plugin-boundaries'), `not applied to .${ext}: ${ids}`)
      }
    })
  }

  it('allows the platform packages in a plugin', async () => {
    const ids = await ruleIds('plugins/feature/assessment/frontend/src/Probe.ts',
      "import '@alinha/plugin-sdk'\nimport '@alinha/ui'\nimport '@alinha/api-client'")
    assert.deepEqual(ids.filter((id) => id === 'alinha/plugin-boundaries'), [])
  })

  it('does not restrict the shell', async () => {
    const ids = await ruleIds('frontend/shell/src/Probe.ts', "import x from '../../../plugins/feature/assessment/frontend/src/index.ts'")
    assert.ok(!ids.includes('alinha/plugin-boundaries'))
  })

  it('still applies when ESLint runs from inside a plugin (e.g. an IDE with one plugin open)', async () => {
    const pluginDir = resolve(root, 'plugins/feature/assessment/frontend')
    // ESLint finds the repo's eslint.config.js by looking up from the file, as an IDE does.
    const fromPlugin = new ESLint({ cwd: pluginDir })
    const [result] = await fromPlugin.lintText("import x from '@alinha/plugin-organization'\nexport default x\n", {
      filePath: resolve(pluginDir, 'src/pages/Probe.ts'),
    })
    assert.ok(result.messages.some((m) => m.ruleId === 'alinha/plugin-boundaries'), JSON.stringify(result.messages))
  })
})
