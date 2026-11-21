#!/usr/bin/env node
// Builds the production realm from the dev realm (deploy/keycloak/alinha-realm.json), so there is one source of
// truth for roles, mappers, flows and the user profile. Run by deploy/Dockerfile.keycloak:
//   node scripts/gen-prod-realm.mjs <dev-realm.json> <prod-realm.json>
//
// Every dev secret and localhost URL becomes a Keycloak import placeholder (${VAR}), which Keycloak resolves from
// the container's environment on `--import-realm`. So no production secret is ever baked into the image.
import { readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export const PUBLIC_URL = '${ALINHA_PUBLIC_URL}'

/** Returns a production copy of the dev realm. The input is not modified. */
export function toProdRealm(devRealm) {
  const realm = structuredClone(devRealm)
  // HTTPS is terminated by the proxy in front; Keycloak still refuses plain HTTP from public addresses.
  realm.sslRequired = 'external'
  realm.clients = realm.clients.map(prodClient)
  // Test users stay (personas for demos), but with a secret password: the dev one is public in this repo,
  // and whoever logs in first would enrol their own TOTP.
  realm.users = realm.users.map((user) => ({
    ...user,
    credentials: user.credentials?.map((c) =>
      c.type === 'password' ? { ...c, value: '${ALINHA_TEST_USER_PASSWORD}' } : c,
    ),
  }))
  return realm
}

function prodClient(client) {
  switch (client.clientId) {
    case 'bff':
      return {
        ...client,
        rootUrl: PUBLIC_URL,
        baseUrl: PUBLIC_URL,
        secret: '${BFF_CLIENT_SECRET}',
        redirectUris: [`${PUBLIC_URL}/login/oauth2/code/keycloak`],
        webOrigins: [PUBLIC_URL],
        attributes: { ...client.attributes, 'post.logout.redirect.uris': `${PUBLIC_URL}/*` },
      }
    case 'alinha-provisioner':
      return { ...client, secret: '${KEYCLOAK_PROVISIONER_SECRET}' }
    default:
      if (client.secret) throw new Error(`client ${client.clientId} has a secret with no production placeholder`)
      return client
  }
}

function main([input, output]) {
  if (!input || !output) {
    console.error('usage: gen-prod-realm.mjs <dev-realm.json> <prod-realm.json>')
    process.exit(2)
  }
  const realm = toProdRealm(JSON.parse(readFileSync(input, 'utf8')))
  writeFileSync(output, `${JSON.stringify(realm, null, 2)}\n`)
}

const isMain = () =>
  !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))

if (isMain()) main(process.argv.slice(2))
