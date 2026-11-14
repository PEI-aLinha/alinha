# Keycloak

The `alinha` realm is imported from `alinha-realm.json` by `start-dev --import-realm`. The import
only runs when the realm does not exist; after editing the file, recreate the container:

```bash
docker compose -f deploy/docker-compose.dev.yml up -d --force-recreate keycloak
```

- Admin console: http://localhost:8180 (admin / admin, or `KEYCLOAK_ADMIN*` from `deploy/.env`)
- Realm: `alinha`
- All dev users have the password `alinha` and **must set up TOTP on first login** (MFA).

## Test users

| Person | Username | Realm role | Tenant claim |
|---|---|---|---|
| Rui Santos | `rui.santos@compagueda.pt` | `responsible` | `tenant_id` = Componentes Águeda |
| Pedro Almeida | `pedro.almeida@tiagueda.pt` | `analyst` | `tenant_id` = Componentes Águeda |
| Marta Ferreira | `marta.ferreira@alinha.pt` | `consultant` | `tenants` = Componentes Águeda, Metalúrgica de Aveiro |
| João Pereira | `joao.pereira@alinha.pt` | `consultant` | `tenants` = Metalúrgica de Aveiro |

Dev tenant UUIDs, reused by the persona seed data (#42):

| Organisation | UUID |
|---|---|
| Componentes Águeda | `11111111-1111-4111-8111-111111111111` |
| Metalúrgica de Aveiro | `22222222-2222-4222-8222-222222222222` |

Marta can act on both organisations; João only on Metalúrgica de Aveiro, so "organization not
assigned to this consultant" (403) can be exercised locally.

## Token contract

The `bff` client adds these to the access token. Backend contract: the tenant and RBAC docs added
by #8 and #9.

- `aud` includes `alinha-api`
- `realm_access.roles` contains the realm role (`roles` client scope)
- `tenant_id` — user attribute, string UUID, for `responsible` and `analyst`
- `tenants` — multivalued user attribute, list of UUID strings, for `consultant`

The BFF forwards `X-Alinha-Tenant` unchanged for consultants. The secret in the export is dev-only.

## Email

Keycloak sends mail through Mailpit (`mailpit:1025`). The UI is at http://localhost:8025.
