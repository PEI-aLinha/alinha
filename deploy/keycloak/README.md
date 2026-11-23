# Keycloak

> **Local development only.** This realm has `sslRequired: none`, a committed dev client secret and
> test passwords. Never import it as is into staging or production: `deploy/Dockerfile.keycloak` turns it into the
> production realm with `scripts/gen-prod-realm.mjs` (secrets, test passwords and URLs from the environment).

The `alinha` realm is imported from `alinha-realm.json` by `start-dev --import-realm`. The import
only runs when the realm does not exist; after editing the file, recreate the container:

```bash
docker compose -f deploy/docker-compose.dev.yml up -d --force-recreate keycloak
```

- Admin console: http://localhost:8180 (admin / admin, or `KEYCLOAK_ADMIN*` from `deploy/.env`)
- Realm: `alinha`
- All dev users have the password `alinha-dev-1` and **must set up TOTP on first login** (MFA).
- Login, TOTP setup and emails default to European Portuguese (`pt`).
- The login pages use the aLinha theme (`loginTheme: alinha`) from [`frontend/keycloak-theme`](../../frontend/keycloak-theme).
  Build it before starting Keycloak (`pnpm --filter @alinha/keycloak-theme build-keycloak-theme`); without the jar,
  Keycloak shows its own theme.

## Realm settings that matter

- **Required actions are listed in full.** An import registers only the required actions in `requiredActions`, so
  one missing from the list does not exist in the realm. `VERIFY_EMAIL` (priority 10) runs before `CONFIGURE_TOTP`
  (20, default action): a self-registered account confirms its email first, then sets up TOTP.
  `TERMS_AND_CONDITIONS` is listed but off until the terms page exists (#128).
- **Password policy:** at least 12 characters with letters and numbers, at most 128, not the email or username
  (the prototype's rules). The registration page shows them from the policy. An import fails if a seed password
  breaks the policy, which is why the dev password is `alinha-dev-1`.
- **Brute-force detection:** after 10 failures the account waits 1 minute, growing up to 15 minutes; failures are
  forgotten after 12 hours.

`start-dev` keeps its database in H2 inside the container. Recreating the Keycloak container
re-imports the realm and wipes every enrolled TOTP, so each user must scan the QR code again.

## Test users

| Person | Username | Realm role | Tenant claim |
|---|---|---|---|
| Rui Santos | `rui.santos@compagueda.pt` | `responsible` | `tenant_id` = Componentes Águeda |
| Pedro Almeida | `pedro.almeida@tiagueda.pt` | `analyst` | `tenant_id` = Componentes Águeda |
| Marta Ferreira | `marta.ferreira@alinha.pt` | `consultant` | `tenants` = Componentes Águeda, Metalúrgica de Aveiro |
| João Pereira | `joao.pereira@alinha.pt` | `consultant` | `tenants` = Metalúrgica de Aveiro |

Pedro's `@tiagueda.pt` address is intentional: he is the external IT analyst working for
Componentes Águeda, per the prototype personas.

Dev tenant UUIDs, reused by the persona seed data (#42):

| Organisation | UUID |
|---|---|
| Componentes Águeda | `11111111-1111-4111-8111-111111111111` |
| Metalúrgica de Aveiro | `22222222-2222-4222-8222-222222222222` |

Their organization profiles (name, NIF, sector…) come from
`backend/app/src/main/resources/db/demo/R__demo_organization_profiles.sql`, loaded only when the app runs with the
`demo` Spring profile. Without it the menu shows the tenant UUID instead of the organization name.

Marta can act on both organisations; João only on Metalúrgica de Aveiro, so "organization not
assigned to this consultant" (403) can be exercised locally.

## Self-registration and the provisioner (ADR 0004)

- The login page has **Registar** (email as username, verified email through Mailpit, TOTP on first login).
- A new account gets the default role `default-roles-alinha`, which includes `registrant` and no `tenant_id`.
  The seed users above keep only their explicit role, because an import does not add the default role.
- **Every** account created later through the admin console or the Admin API also gets the default role, and so
  `registrant`. When creating a consultant or analyst by hand, remove `default-roles-alinha` from it. Code that creates
  accounts (`IdentityDirectory`) must do the same.
- **No account console.** The default role has no `account` client roles (`view-profile`, `manage-account`), and the
  seed users never had them, so nobody can open the account console. This is deliberate: the platform's own pages
  hold the user's data, a forgotten password goes through *Esqueceu-se da palavra-passe?* on the login page, and a
  lost TOTP device is reset by an admin (*Credentials* → delete OTP; the user enrols again on the next login).
- `alinha-provisioner` is a confidential client with a service account (`realm-management`: `manage-users`,
  `view-users`). When an organization is registered, the app sets `tenant_id`, maps `responsible`, maps the other
  roles of `default-roles-alinha` (`offline_access`, `uma_authorization`) directly and then removes the composite, so
  only `registrant` goes away. It cannot create roles or read clients, but `manage-users`
  can map **any** realm role (`consultant` included) to any user: treat its secret like an admin credential.
- `tenant_id` and `tenants` are admin-only in the user profile (view and edit `admin`), and unmanaged attributes are
  off, so a user cannot set them in the registration form (or an account console, if one is ever enabled). Keep it that way: a user who could
  write `tenant_id` could enter another organization.
- Its dev secret (`dev-provisioner-secret`) is also seeded in OpenBao as `identity.provisioner-secret`; the app reads
  it from there, never from config files. Outside local development use a new secret and never this one.
- The default role only exists in a realm created from this file. An existing dev realm must be recreated
  (command above), or self-registered accounts get no `registrant` role and are refused (403).

## Token contract

The `bff` client adds these to the access token. Backend contract: [`docs/tenancy.md`](../../docs/tenancy.md)
and [`docs/rbac.md`](../../docs/rbac.md).

- `aud` includes `alinha-api`
- `sub` is the Keycloak user id (`basic` client scope). Without it the backend refuses registration (403), so an
  existing realm imported before this scope was listed needs it added to `bff` by hand
- `realm_access.roles` contains the realm role (`roles` client scope)
- `tenant_id` — user attribute, string UUID, for `responsible` and `analyst`
- `tenants` — multivalued user attribute, list of UUID strings, for `consultant`

The BFF forwards `X-Alinha-Tenant` unchanged for consultants. The secret in the export is dev-only.

## Email

Keycloak sends mail through Mailpit (`mailpit:1025`). The UI is at http://localhost:8025.
