#!/usr/bin/env node
// Gera frontend/shell/src/plugins.generated.ts a partir dos submodules em
// plugins/<tipo>/<id>/frontend. Corre automaticamente antes de dev/build/typecheck.
// Adicionar um plugin = adicionar o submodule; o shell não é editado à mão.
import { existsSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const pluginsDir = join(root, 'plugins')
const outFile = join(root, 'frontend/shell/src/plugins.generated.ts')

const dirs = (p) => (existsSync(p) ? readdirSync(p).filter((d) => statSync(join(p, d)).isDirectory()) : [])

const entries = []
for (const kind of dirs(pluginsDir).sort()) {
  for (const id of dirs(join(pluginsDir, kind)).sort()) {
    const entry = join(pluginsDir, kind, id, 'frontend/src/index.ts')
    if (existsSync(entry)) entries.push({ id, entry })
    else if (existsSync(join(pluginsDir, kind, id, '.git')) || dirs(join(pluginsDir, kind, id)).length === 0) {
      console.warn(`! plugins/${kind}/${id} está vazio — falta "git submodule update --init"?`)
    }
  }
}

const toImport = (p) => relative(dirname(outFile), p).split('\\').join('/')
const lines = [
  '// GERADO por scripts/gen-frontend-plugins.mjs — não editar.',
  "import type { FrontendPlugin } from '@alinha/plugin-sdk'",
  ...entries.map((e, i) => `import p${i} from '${toImport(e.entry)}'`),
  '',
  `export const plugins: FrontendPlugin[] = [${entries.map((_, i) => `p${i}`).join(', ')}]`,
  '',
]
writeFileSync(outFile, lines.join('\n'))
console.log(`plugins.generated.ts: ${entries.length} plugin(s) — ${entries.map((e) => e.id).join(', ') || 'nenhum'}`)
