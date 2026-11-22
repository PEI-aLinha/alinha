// Run with: pnpm test
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PUBLIC_URL, SMTP_RELAY, toProdRealm } from './gen-prod-realm.mjs'

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

  it('drops the test users but keeps service accounts', () => {
    assert.deepEqual(prod.users.map((u) => u.username), ['service-account-alinha-provisioner'])
    assert.ok(!JSON.stringify(prod).includes('ALINHA_TEST_USER_PASSWORD'))
  })

  it('sends mail through the SMTP relay from the environment, with auth and STARTTLS', () => {
    assert.deepEqual(prod.smtpServer, SMTP_RELAY)
    assert.equal(prod.smtpServer.auth, 'true')
    assert.equal(prod.smtpServer.starttls, 'true')
    assert.ok(!JSON.stringify(prod).includes('mailpit'))
  })

  it('staging keeps Mailpit as SMTP server', () => {
    assert.deepEqual(toProdRealm(devRealm, { staging: true }).smtpServer, devRealm.smtpServer)
  })

  it('staging keeps every user and gives the personas the secret password', () => {
    const staging = toProdRealm(devRealm, { staging: true })
    assert.equal(staging.users.length, devRealm.users.length)
    const passwords = staging.users.flatMap((u) => u.credentials ?? []).filter((c) => c.type === 'password')
    assert.ok(passwords.length > 0)
    for (const p of passwords) assert.equal(p.value, '${ALINHA_TEST_USER_PASSWORD}')
  })

  it('the staging compose requires the test user password for Keycloak', () => {
    const staging = readFileSync(resolve(root, 'deploy/docker-compose.staging.yml'), 'utf8')
    const keycloak = staging.slice(staging.indexOf('\n  keycloak:'), staging.indexOf('\n  job-consumer:'))
    assert.match(keycloak, /STAGING: "true"/)
    assert.match(keycloak, /\bALINHA_TEST_USER_PASSWORD: \$\{ALINHA_TEST_USER_PASSWORD:\?\}/)
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
