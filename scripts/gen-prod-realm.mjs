#!/usr/bin/env node
// Builds the production realm from the dev realm (deploy/keycloak/alinha-realm.json), so there is one source of
// truth for roles, mappers, flows and the user profile. Run by deploy/Dockerfile.keycloak:
//   node scripts/gen-prod-realm.mjs [--staging] <dev-realm.json> <prod-realm.json>
// --staging keeps the dev personas (see deploy/docker-compose.staging.yml).
//
// Every dev secret and localhost URL becomes a Keycloak import placeholder (${VAR}), which Keycloak resolves from
// the container's environment on `--import-realm`. So no production secret is ever baked into the image.
import { readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export const PUBLIC_URL = '${ALINHA_PUBLIC_URL}'

/**
 * Returns a production copy of the dev realm. The input is not modified.
 * Production (the default): the dev personas are dropped, so only real accounts exist (service-account users stay,
 * because their client, e.g. alinha-provisioner, needs their role mappings).
 * `staging` keeps the personas (with a secret password).
 * Both send mail through the SMTP server given in the environment (SMTP_SERVER): Mailpit until there is a relay.
 */
export function toProdRealm(devRealm, { staging = false } = {}) {
  const realm = structuredClone(devRealm)
  // HTTPS is terminated by the proxy in front; Keycloak still refuses plain HTTP from public addresses.
  realm.sslRequired = 'external'
  realm.clients = realm.clients.map(prodClient)
  realm.users = staging
    ? realm.users.map(withSecretPassword)
    : realm.users.filter((user) => user.serviceAccountClientId)
  realm.smtpServer = SMTP_SERVER
  return realm
}

/**
 * The realm's SMTP server, all from the environment: docker-compose.prod.yml defaults to its Mailpit (no auth, no
 * TLS); a real relay sets SMTP_AUTH and SMTP_STARTTLS to true (deploy/DEPLOYMENT.md, "Emails").
 */
export const SMTP_SERVER = {
  host: '${SMTP_HOST}',
  port: '${SMTP_PORT}',
  from: '${SMTP_FROM}',
  fromDisplayName: 'aLinha',
  replyTo: '${SMTP_FROM}',
  auth: '${SMTP_AUTH}',
  user: '${SMTP_USERNAME}',
  password: '${SMTP_PASSWORD}',
  starttls: '${SMTP_STARTTLS}',
  ssl: 'false',
}

// The dev password is public in this repo, and whoever logs in first would enrol their own TOTP.
function withSecretPassword(user) {
  return {
    ...user,
    credentials: user.credentials?.map((c) =>
      c.type === 'password' ? { ...c, value: '${ALINHA_TEST_USER_PASSWORD}' } : c,
    ),
  }
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

function main(args) {
  const staging = args[0] === '--staging'
  const [input, output, ...extra] = staging ? args.slice(1) : args
  if (!input || !output || extra.length > 0) {
    console.error('usage: gen-prod-realm.mjs [--staging] <dev-realm.json> <prod-realm.json>')
    process.exit(2)
  }
  const realm = toProdRealm(JSON.parse(readFileSync(input, 'utf8')), { staging })
  writeFileSync(output, `${JSON.stringify(realm, null, 2)}\n`)
}

const isMain = () =>
  !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))

if (isMain()) main(process.argv.slice(2))
