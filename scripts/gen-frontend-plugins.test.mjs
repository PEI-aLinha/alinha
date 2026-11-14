// Run with: pnpm test
import { after, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { findPluginFrontends, renderPluginsModule } from './gen-frontend-plugins.mjs'

const root = resolve(import.meta.dirname, '..')

describe('findPluginFrontends', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'alinha-plugins-'))
  after(() => rmSync(tmp, { recursive: true, force: true }))

  const plugin = (path, { frontend = true } = {}) => {
    const dir = join(tmp, path)
    mkdirSync(join(dir, frontend ? 'frontend/src' : 'backend'), { recursive: true })
    if (frontend) writeFileSync(join(dir, 'frontend/src/index.ts'), 'export default {}\n')
    else writeFileSync(join(dir, 'backend/build.gradle.kts'), '')
  }
  plugin('provider/zeta')
  plugin('feature/risk-assets')
  plugin('feature/assessment')
  plugin('provider/backend-only', { frontend: false })
  mkdirSync(join(tmp, 'feature/not-initialised'), { recursive: true })

  const { entries, empty } = findPluginFrontends(tmp)

  it('registers every plugin with a frontend entry, sorted by kind then id', () => {
    assert.deepEqual(entries.map((e) => `${e.kind}/${e.id}`), ['feature/assessment', 'feature/risk-assets', 'provider/zeta'])
  })

  it('reports only empty folders (submodule not initialised)', () => {
    assert.deepEqual(empty, ['feature/not-initialised'])
  })

  it('returns nothing when there is no plugins folder', () => {
    assert.deepEqual(findPluginFrontends(join(tmp, 'missing')), { entries: [], empty: [] })
  })
})

describe('renderPluginsModule', () => {
  it('imports each entry relative to the generated file', () => {
    const out = join(root, 'frontend/shell/src/plugins.generated.ts')
    const source = renderPluginsModule([{ id: 'a', entry: join(root, 'plugins/feature/a/frontend/src/index.ts') }], out)
    assert.match(source, /^import p0 from '\.\.\/\.\.\/\.\.\/plugins\/feature\/a\/frontend\/src\/index\.ts'$/m)
    assert.match(source, /^export const plugins: FrontendPlugin\[\] = \[p0\]$/m)
  })

  it('renders an empty list without plugins', () => {
    assert.match(renderPluginsModule([], 'x.ts'), /^export const plugins: FrontendPlugin\[\] = \[\]$/m)
  })
})

describe('this repo', () => {
  const submodules = [...readFileSync(join(root, '.gitmodules'), 'utf8').matchAll(/^\s*path = (plugins\/.+)$/gm)].map((m) => m[1])
  const { entries, empty } = findPluginFrontends(join(root, 'plugins'))

  it('has every plugin submodule initialised', () => {
    assert.deepEqual(empty, [], 'run: git submodule update --init --recursive')
  })

  it('registers the frontend of every plugin submodule that has one', () => {
    const withFrontend = submodules.filter((p) => existsSync(join(root, p, 'frontend'))).sort()
    assert.ok(withFrontend.length > 0)
    assert.deepEqual(entries.map((e) => `plugins/${e.kind}/${e.id}`), withFrontend)
  })
})
