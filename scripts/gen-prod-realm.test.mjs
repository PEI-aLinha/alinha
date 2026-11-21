// Run with: pnpm test
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PUBLIC_URL, toProdRealm } from './gen-prod-realm.mjs'

const root = resolve(import.meta.dirname, '..')
const devRealm = JSON.parse(readFileSync(resolve(root, 'deploy/keycloak/alinha-realm.json'), 'utf8'))
const prod = toProdRealm(devRealm)
const client = (id) => prod.clients.find((c) => c.clientId === id)

describe('toProdRealm', () => {
  it('requires HTTPS for external requests', () => {
    assert.equal(prod.sslRequired, 'external')
  })

  it('points the bff client at the public URL only', () => {
    const bff = client('bff')
    assert.deepEqual(bff.redirectUris, [`${PUBLIC_URL}/login/oauth2/code/keycloak`])
    assert.deepEqual(bff.webOrigins, [PUBLIC_URL])
    assert.equal(bff.attributes['post.logout.redirect.uris'], `${PUBLIC_URL}/*`)
    assert.equal(bff.rootUrl, PUBLIC_URL)
  })

  it('keeps the bff protocol mappers', () => {
    assert.deepEqual(client('bff').protocolMappers, devRealm.clients.find((c) => c.clientId === 'bff').protocolMappers)
  })

  it('replaces every client secret with a placeholder', () => {
    assert.equal(client('bff').secret, '${BFF_CLIENT_SECRET}')
    assert.equal(client('alinha-provisioner').secret, '${KEYCLOAK_PROVISIONER_SECRET}')
  })

  it('leaves no dev secret or localhost URL behind', () => {
    const json = JSON.stringify(prod)
    for (const leaked of ['localhost', 'dev-bff-secret', 'dev-provisioner-secret', 'alinha-dev-1']) {
      assert.ok(!json.includes(leaked), `${leaked} is still in the production realm`)
    }
  })

  it('gives the test users the secret password', () => {
    const passwords = prod.users.flatMap((u) => u.credentials ?? []).filter((c) => c.type === 'password')
    assert.ok(passwords.length > 0)
    for (const p of passwords) assert.equal(p.value, '${ALINHA_TEST_USER_PASSWORD}')
  })

  it('does not modify the dev realm', () => {
    assert.equal(devRealm.sslRequired, 'none')
    assert.equal(devRealm.clients.find((c) => c.clientId === 'bff').secret, 'dev-bff-secret')
  })

  it('only uses placeholders that the production compose requires for Keycloak', () => {
    // An unset placeholder is imported as the literal text "${...}", so each one must fail compose when missing.
    const compose = readFileSync(resolve(root, 'deploy/docker-compose.prod.yml'), 'utf8')
    const keycloak = compose.slice(compose.indexOf('\n  keycloak:'), compose.indexOf('\n  postgres:'))
    const placeholders = new Set([...JSON.stringify(prod).matchAll(/\$\{([A-Z_]+)\}/g)].map((m) => m[1]))
    assert.ok(placeholders.size > 0)
    for (const name of placeholders) {
      assert.match(keycloak, new RegExp(`\\b${name}: \\$\\{${name}:\\?\\}`), `${name} is not required for Keycloak`)
    }
  })

  it('refuses a client with a secret it does not know', () => {
    const realm = { ...devRealm, clients: [...devRealm.clients, { clientId: 'new', secret: 'x' }] }
    assert.throws(() => toProdRealm(realm), /client new has a secret/)
  })
})
