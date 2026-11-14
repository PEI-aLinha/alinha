#!/usr/bin/env node
// Regression checks for the local dev environment (issue #5): the Keycloak realm
// contract and the compose file. Run in CI: node scripts/check-dev-env.mjs
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (path) => readFileSync(join(root, path), "utf8");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const realm = JSON.parse(read("deploy/keycloak/alinha-realm.json"));
assert.equal(realm.realm, "alinha");

const roles = new Set(realm.roles.realm.map((role) => role.name));
for (const role of ["consultant", "responsible", "analyst"]) {
  assert.ok(roles.has(role), `realm role ${role} is missing`);
}

const client = realm.clients.find((c) => c.clientId === "bff");
assert.ok(client, "client bff is missing");
assert.equal(client.publicClient, false);
assert.ok(client.defaultClientScopes.includes("roles"), "bff must request the roles scope");

const mapper = (name) => client.protocolMappers.find((m) => m.name === name);
assert.equal(mapper("alinha-api audience")?.config["included.custom.audience"], "alinha-api");
for (const [name, attribute, multivalued] of [
  ["tenant_id", "tenant_id", "false"],
  ["tenants", "tenants", "true"],
]) {
  const found = mapper(name);
  assert.ok(found, `protocol mapper ${name} is missing`);
  assert.equal(found.config["access.token.claim"], "true");
  assert.equal(found.config["user.attribute"], attribute);
  assert.equal(found.config.multivalued, multivalued);
}

const otp = realm.requiredActions.find((action) => action.alias === "CONFIGURE_TOTP");
assert.ok(otp?.enabled && otp.defaultAction, "CONFIGURE_TOTP must be enabled by default");

const users = new Map(realm.users.map((user) => [user.username, user]));
const expected = {
  "rui.santos@compagueda.pt": { role: "responsible", claim: "tenant_id" },
  "pedro.almeida@tiagueda.pt": { role: "analyst", claim: "tenant_id" },
  "marta.ferreira@alinha.pt": { role: "consultant", claim: "tenants" },
  "joao.pereira@alinha.pt": { role: "consultant", claim: "tenants" },
};
for (const [username, { role, claim }] of Object.entries(expected)) {
  const user = users.get(username);
  assert.ok(user, `user ${username} is missing`);
  assert.ok(user.realmRoles.includes(role), `${username} must have role ${role}`);
  assert.ok(user.requiredActions.includes("CONFIGURE_TOTP"), `${username} must configure OTP`);
  const tenants = user.attributes?.[claim];
  assert.ok(Array.isArray(tenants) && tenants.length > 0, `${username} needs a ${claim} attribute`);
  for (const tenant of tenants) assert.match(tenant, UUID, `${username} has an invalid ${claim}`);
  const other = claim === "tenant_id" ? "tenants" : "tenant_id";
  assert.equal(user.attributes?.[other], undefined, `${username} must not have ${other}`);
}
assert.equal(users.get("marta.ferreira@alinha.pt").attributes.tenants.length, 2);
assert.equal(users.get("joao.pereira@alinha.pt").attributes.tenants.length, 1);

assert.equal(realm.smtpServer.host, "mailpit");
const compose = read("deploy/docker-compose.dev.yml");
for (const service of ["postgres:", "redis:", "keycloak:", "mailpit:"]) {
  assert.match(compose, new RegExp(`^\\s{2}${service}`, "m"), `compose service ${service} is missing`);
}
assert.match(compose, /"1025:1025"/, "Mailpit SMTP port is not published");
assert.ok(existsSync(join(root, "deploy/.env.example")), "deploy/.env.example is missing");

console.log("dev environment checks passed");
