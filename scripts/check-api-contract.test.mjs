import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { URL } from 'node:url'

const contract = readFileSync(new URL('../contracts/openapi/alinha.yaml', import.meta.url), 'utf8')
const generated = readFileSync(
  new URL('../frontend/packages/api-client/src/schema.generated.ts', import.meta.url),
  'utf8',
)

test('the contract keeps the v1 conventions', () => {
  assert.match(contract, /^openapi: 3\.1\.0$/m)
  for (const expected of [
    'Problem',
    'PageMeta',
    'X-Alinha-Tenant',
    'application/problem+json',
    '/api/me',
    '/api/plugins',
    '/api/{plugin}/ping',
  ]) {
    assert.ok(contract.includes(expected), expected)
  }
  assert.doesNotMatch(contract, /\/api\/[^\n]*tenant/i, 'tenant must not be in a path')
})

test('the generated api-client exposes the v1 operations', () => {
  for (const expected of ['getMe', 'listPlugins', 'pingPlugin', 'Me', 'PluginManifest']) {
    assert.ok(generated.includes(expected), expected)
  }
})
