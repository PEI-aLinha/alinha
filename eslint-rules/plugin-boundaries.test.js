// Run with: pnpm test
import { after, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { ESLint, RuleTester } from 'eslint'
import tseslint from 'typescript-eslint'
import rule, { installedPackageOf } from './plugin-boundaries.js'

const root = resolve(import.meta.dirname, '..')
const plugin = (file) => resolve(root, 'plugins/feature/assessment/frontend', file)
// A plugin whose package.json declares path dependencies and @types packages (eslint-rules/__fixtures__).
const fixtures = resolve(import.meta.dirname, '__fixtures__/repo')
const fixture = (file) => resolve(fixtures, 'plugins/feature/linked/frontend', file)

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
    // Packages declared in the plugin's package.json (dependencies, peer, dev), with or without a subpath.
    { filename: plugin('src/index.ts'), code: "import { useQuery } from '@tanstack/react-query'" },
    { filename: plugin('src/index.ts'), code: "import { jsx } from 'react/jsx-runtime'" },
    { filename: plugin('src/index.ts'), code: "import type { FC } from 'react'" },
    // Node builtins (config files such as vitest.config.ts).
    { filename: plugin('vitest.config.ts'), code: "import { resolve } from 'node:path'" },
    { filename: plugin('vitest.config.ts'), code: "import { resolve } from 'path'" },
    { filename: plugin('src/index.ts'), code: "import { Page } from './pages/Page.tsx'" },
    // Deeply nested files may still import their own plugin's code.
    { filename: plugin('src/pages/a/b/Page.tsx'), code: "import { use } from '../../../hooks/use.ts'" },
    { filename: plugin('src/pages/a/Page.tsx'), code: "import x from '../../../src/index.ts'" },
    { filename: plugin('src/routes.ts'), code: "const Page = () => import('./pages/Page.tsx')" },
    { filename: plugin('src/routes.ts'), code: 'const Page = () => import(`./pages/Page.tsx`)' },
    { filename: plugin('src/index.ts'), code: "const p = require.resolve('./pages/Page.tsx')" },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst req = createRequire(import.meta.url)\nconst x = req('./pages/Page.tsx')\nconst p = req.resolve('react')",
    },
    // A function that merely has a method called resolve is not require.
    { filename: plugin('src/index.ts'), code: 'const p = (n: string) => Promise.resolve(n)' },
    // Other properties of require do not resolve paths.
    { filename: plugin('src/index.ts'), code: 'const isMain = require.main === module' },
    { filename: plugin('src/index.ts'), code: "const hasRequire = typeof require === 'function'" },
    // Vite query suffixes on packages.
    { filename: plugin('src/index.ts'), code: "import css from '@alinha/ui?inline'" },
    { filename: plugin('src/index.ts'), code: "import url from 'react?url'" },
    // Types from @types/<name> are imported as <name>.
    ...["import type { Program } from 'estree'", "import type { PluginObj } from '@babel/core'", "import { useState } from 'react'"].map((code) => ({
      filename: fixture('src/a.ts'),
      options: [{ root: fixtures }],
      code,
    })),
    // workspace:* on a platform package, a workspace alias of a platform package, and an npm: alias of a registry package.
    ...["import { definePlugin } from '@alinha/plugin-sdk'", "import { definePlugin } from 'sdk'", "import debounce from 'lodash/debounce'"].map(
      (code) => ({ filename: fixture('src/a.ts'), options: [{ root: fixtures }], code }),
    ),
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
    // require.resolve and createRequire resolve paths like require.
    { filename: plugin('src/index.ts'), code: "const p = require.resolve('../../../risk-assets/frontend/src/index.ts')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const p = require.resolve('@alinha/shell')", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: 'const p = (n: string) => require.resolve(n)', ...err('dynamic') },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst req = createRequire(import.meta.url)\nconst x = req('../../../risk-assets/frontend/src/index.ts')",
      ...err('outside'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst req = createRequire(import.meta.url)\nconst p = req.resolve('~/x.ts')",
      ...err('undeclared'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import module from 'node:module'\nconst req = module.createRequire('../../../../frontend/shell/src/main.tsx')",
      ...err('outside'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst req = (f: string) => createRequire(f)",
      // Both the dynamic base and the returned require, which escapes the check.
      errors: [{ messageId: 'dynamic' }, { messageId: 'dynamic' }],
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst x = createRequire(import.meta.url)('../../../risk-assets/frontend/src/index.ts')",
      ...err('outside'),
    },
    // Calls without arguments are reported, not a crash.
    { filename: plugin('src/index.ts'), code: 'const p = require.resolve()', ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "import { createRequire } from 'node:module'\nconst req = createRequire()", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: 'const pages = import.meta.glob()', ...err('dynamic') },
    // require and createRequire cannot be aliased away from the check.
    { filename: plugin('src/index.ts'), code: "const r = require\nr('../../../risk-assets/frontend/src/index.ts')", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const { resolve } = require\nresolve('../../../risk-assets/frontend/src/index.ts')", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const r = require.resolve\nr('../../../risk-assets/frontend/src/index.ts')", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const p = require['resolve']('../../../risk-assets/frontend/src/index.ts')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const p = (k: string) => require[k]('../../../risk-assets/frontend/src/index.ts')", ...err('dynamic') },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire as cr } from 'node:module'\nconst req = cr(import.meta.url)\nconst p = req.resolve('../../../risk-assets/frontend/src/index.ts')",
      ...err('outside'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nlet req\nreq = createRequire(import.meta.url)\nreq('../../../risk-assets/frontend/src/index.ts')",
      ...err('outside'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nfunction f() { return req('../../../risk-assets/frontend/src/index.ts') }\nconst req = createRequire(import.meta.url)",
      ...err('outside'),
    },
    {
      filename: plugin('src/index.ts'),
      code: "import { createRequire } from 'node:module'\nconst make = createRequire",
      ...err('dynamic'),
    },
    // A package subpath with ".." climbs out of the package folder.
    { filename: plugin('src/index.ts'), code: "import x from '@alinha/ui/../../../../frontend/shell/src/host.tsx'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from 'react/../../../frontend/shell/src/main.tsx'", ...err('outside') },
    { filename: plugin('vitest.config.ts'), code: "import x from 'path/../../x'", ...err('outside') },
    // A dependency installed from a path (link:, file:, ../, workspace:../) is repo code, not a package. So is an alias
    // (workspace:<name>@, npm:<name>@) of a workspace package other than the three platform packages.
    ...['shell/src/host.tsx', 'local', 'relative/src/main.tsx', 'shellx/src/router', 'orgx', 'wspath/src/main.tsx', 'npmshell'].map((spec) => ({
      filename: fixture('src/a.ts'),
      options: [{ root: fixtures }],
      code: `import x from '${spec}'`,
      ...err('undeclared'),
    })),
    // A platform package name that package.json points somewhere else (an alias or a path) is not the platform package.
    ...['@alinha/ui', '@alinha/api-client/src/x'].map((spec) => ({
      filename: fixture('src/a.ts'),
      options: [{ root: fixtures }],
      code: `import x from '${spec}'`,
      ...err('platformPackage'),
    })),
    { filename: plugin('src/index.ts'), code: "type T = import('@alinha/plugin-risk-assets').Thing", ...err('platformPackage') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../risk-assets/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/pages/a/b/Page.tsx'), code: "import x from '../../../../../../risk-assets/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../../provider/code-repository/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../../../../frontend/shell/src/host.tsx'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from '../../backend/src/main/kotlin/x.ts'", ...err('outside') },
    // Bare names must be packages the plugin declares: a path alias (Vite resolve.alias, tsconfig paths) looks like a
    // bare name but resolves into the repo.
    { filename: plugin('src/index.ts'), code: "import x from '@/pages/Page.tsx'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "import x from '~/pages/Page.tsx'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "import x from '#shell/host.tsx'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "import x from 'shell/src/host.tsx'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "import x from 'lodash'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "import x from '@tanstack/react-table'", ...err('undeclared') },
    { filename: plugin('src/index.ts'), code: "const m = () => import('~/x.ts')", ...err('undeclared') },
    // Without a readable package.json nothing is declared (fails closed).
    {
      filename: '/elsewhere/repo/plugins/feature/x/frontend/src/a.ts',
      options: [{ root: '/elsewhere/repo' }],
      code: "import { useState } from 'react'",
      ...err('undeclared'),
    },
    { filename: plugin('src/index.ts'), code: "import x from '/etc/passwd'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "import x from 'file:///repo/plugins/feature/risk-assets/frontend/src/index.ts'", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const m = () => import('FILE:///repo/frontend/shell/src/host.tsx')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: 'const m = (name: string) => import(name)', ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: 'const m = (name: string) => import(`./${name}`)', ...err('dynamic') },
    // Vite: import.meta.glob and new URL(..., import.meta.url) reach files too.
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('../../../risk-assets/frontend/src/**/*.tsx')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob(['./pages/*.tsx', '../../../../frontend/shell/src/*.tsx'])", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('/src/**/*.tsx')", ...err('outside') },
    { filename: plugin('src/index.ts'), code: 'const pages = (p: string) => import.meta.glob(p)', ...err('dynamic') },
    // Vite's /@fs/ prefix serves any file on disk.
    { filename: plugin('src/index.ts'), code: "import x from '/@fs/repo/frontend/shell/src/host.tsx'", ...err('outside') },
    // The glob `base` option moves where relative patterns resolve from.
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('./*.tsx', { base: '../../../risk-assets/frontend/src' })", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('../../../../*.tsx', { base: './pages' })", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob(['./*.tsx', '!../../../../x.tsx'], { base: './pages' })", ...err('outside') },
    { filename: plugin('src/index.ts'), code: "const pages = (p: string) => import.meta.glob('./*.tsx', { base: p })", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const pages = (o: object) => import.meta.glob('./*.tsx', o)", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const pages = (o: object) => import.meta.glob('./*.tsx', { ...o })", ...err('dynamic') },
    { filename: plugin('src/index.ts'), code: "const pages = (k: string) => import.meta.glob('./*.tsx', { [k]: '../../..' })", ...err('dynamic') },
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
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('./*.tsx', { base: './pages', eager: true })" },
    { filename: plugin('src/pages/a/Page.tsx'), code: "const pages = import.meta.glob('../*.tsx', { base: '../b' })" },
    { filename: plugin('src/index.ts'), code: "const pages = import.meta.glob('./*.tsx', { 'eager': true, import: 'default' })" },
    { filename: plugin('src/index.ts'), code: "const url = new URL('./assets/logo.svg', import.meta.url)" },
    { filename: plugin('src/index.ts'), code: "const url = new URL('https://example.org/x')" },
  ],
  invalid: [],
})

describe('installedPackageOf', () => {
  const cases = [
    ['react', '^19.2.8', 'react'],
    ['@alinha/ui', 'workspace:*', '@alinha/ui'],
    ['@alinha/ui', 'workspace:^', '@alinha/ui'],
    ['@alinha/ui', 'workspace:^0.1.0', '@alinha/ui'],
    ['shellx', 'workspace:@alinha/shell@*', '@alinha/shell'],
    ['sdk', 'workspace:@alinha/plugin-sdk@^0.1.0', '@alinha/plugin-sdk'],
    ['lodash', 'npm:lodash-es@^4.17.21', 'lodash-es'],
    ['x', 'npm:@scope/pkg', '@scope/pkg'],
    ['wspath', 'workspace:../../frontend/shell', null],
    ['shell', 'link:../../frontend/shell', null],
    ['local', 'file:../x', null],
    ['odd', 'workspace:@alinha/shell@../x', null],
  ]
  for (const [name, version, expected] of cases) {
    it(`${name}: ${version} -> ${expected}`, () => assert.equal(installedPackageOf(name, version), expected))
  }
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

  it('applies the rule to every JavaScript and TypeScript extension', async () => {
    const escape = '../../../../../frontend/shell/src/main'
    for (const ext of ['js', 'jsx', 'mjs', 'cjs', 'ts', 'tsx', 'mts', 'cts']) {
      const code = ['cjs', 'cts'].includes(ext) ? `module.exports = require('${escape}')\n` : `import x from '${escape}'\nexport default x\n`
      const [result] = await eslint.lintText(code, { filePath: resolve(root, `plugins/feature/assessment/frontend/src/Probe.${ext}`) })
      const ids = result.messages.map((m) => m.ruleId)
      assert.ok(ids.includes('alinha/plugin-boundaries'), `not applied to .${ext}: ${JSON.stringify(result.messages)}`)
    }
  })

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
