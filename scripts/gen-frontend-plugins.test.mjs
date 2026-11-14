// Run with: pnpm test
import { after, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
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
  // Finder leaves a .DS_Store behind; the folder is still an uninitialised submodule.
  mkdirSync(join(tmp, 'feature/with-ds-store'), { recursive: true })
  writeFileSync(join(tmp, 'feature/with-ds-store/.DS_Store'), '')

  const { entries, empty } = findPluginFrontends(tmp)

  it('registers every plugin with a frontend entry, sorted by kind then id', () => {
    assert.deepEqual(entries.map((e) => `${e.kind}/${e.id}`), ['feature/assessment', 'feature/risk-assets', 'provider/zeta'])
  })

  it('reports only empty folders (submodule not initialised), ignoring dotfiles such as .DS_Store', () => {
    assert.deepEqual(empty, ['feature/not-initialised', 'feature/with-ds-store'])
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

describe('running the script', () => {
  const realOut = join(root, 'frontend/shell/src/plugins.generated.ts')
  const snapshot = () => (existsSync(realOut) ? statSync(realOut).mtimeMs : null)

  it('also runs when called through a symlinked path, and --out leaves the real generated file alone', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'alinha-link-'))
    const before = snapshot()
    try {
      symlinkSync(join(root, 'scripts'), join(tmp, 'scripts'), 'dir')
      const out = join(tmp, 'plugins.generated.ts')
      const run = spawnSync(process.execPath, [join(tmp, 'scripts/gen-frontend-plugins.mjs'), '--out', out], { encoding: 'utf8' })
      assert.equal(run.status, 0, run.stderr)
      assert.match(run.stdout, /plugins\.generated\.ts: \d+ plugin\(s\)/, 'main() did not run')
      assert.match(readFileSync(out, 'utf8'), /^export const plugins: FrontendPlugin\[\] = \[/m)
      assert.equal(snapshot(), before, 'the real plugins.generated.ts was rewritten')
    } finally {
      rmSync(tmp, { recursive: true, force: true })
    }
  })

  it('fails when --out has no path', () => {
    const run = spawnSync(process.execPath, [join(root, 'scripts/gen-frontend-plugins.mjs'), '--out'], { encoding: 'utf8' })
    assert.notEqual(run.status, 0)
    assert.match(run.stderr, /--out needs a file path/)
  })
})

// Needs the plugin submodules: git clone --recurse-submodules (or git submodule update --init --recursive).
describe('this repo', () => {
  const submodules = [...readFileSync(join(root, '.gitmodules'), 'utf8').matchAll(/^\s*path = (plugins\/.+)$/gm)].map((m) => m[1])
  const { entries, empty } = findPluginFrontends(join(root, 'plugins'))

  it('has every plugin submodule initialised', () => {
    assert.deepEqual(empty, [], 'run: git submodule update --init --recursive')
  })

  it('registers the frontend of every plugin submodule that has one', () => {
    const withFrontend = submodules.filter((p) => existsSync(join(root, p, 'frontend'))).sort()
    const registered = entries.map((e) => `plugins/${e.kind}/${e.id}`)
    const withoutEntry = withFrontend.filter((p) => !registered.includes(p))
    assert.ok(withFrontend.length > 0, 'no plugin submodule has a frontend/ folder')
    assert.deepEqual(withoutEntry, [], `these plugins have frontend/ but no ${'frontend/src/index.ts'}, so they are not registered`)
    assert.deepEqual(registered, withFrontend)
  })
})
