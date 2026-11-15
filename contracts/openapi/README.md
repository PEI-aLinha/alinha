# OpenAPI contract

`alinha.yaml` is the single public HTTP contract of aLinha. It is written **contract-first**: the
specification is reviewed before the code, and the BFF, frontend and application follow it. The decision and
its trade-offs are in [`docs/adr/0003-openapi-contract-first.md`](../../docs/adr/0003-openapi-contract-first.md).

Everything the browser calls goes through the BFF under `/api`, using the BFF session cookie. The document
also includes BFF-owned endpoints such as `/api/me`.

## Generated TypeScript

`frontend/packages/api-client/src/schema.generated.ts` is generated from this contract. Never edit it by hand.

```bash
pnpm gen:api     # regenerate after changing alinha.yaml
pnpm check:api   # regenerate and fail if the committed file differs (CI runs this)
```

The generator is pinned in `frontend/packages/api-client/package.json` (`openapi-typescript@7.13.0`).
`frontend/packages/api-client/src/index.ts` re-exports the generated types and keeps the short aliases the
shell already uses (`MeDto`, `OrganizationRefDto`, `Role`).

## Conventions

| Topic | Rule |
|---|---|
| Base path | `/api/<resource>`; plugin endpoints use `/api/<plugin-id>/...` (the id from `plugin.yaml`). |
| Authentication | BFF session cookie (`sessionCookie`); tokens never reach the browser. |
| Tenant | Never in the URL, query or body. Consultants send `X-Alinha-Tenant`; the token's claims are the upper bound (ADR 0002). |
| Errors | RFC 9457 Problem Details as `application/problem+json`; reuse `Problem`, `code` for stable machine values, `errors` for fields. |
| Pagination | `page` (0-based), `pageSize` (default 20, max 100); responses extend `PageMeta` with `items`, `page`, `pageSize`, `total`. |
| IDs | UUID strings (`format: uuid`). |
| Dates | RFC 3339 UTC with `format: date-time`; date-only values use `format: date`. |
| Paths and operations | English, kebab-case paths; camelCase `operationId`; one tag per plugin or area. |
| Compatibility | Never change a published operation incompatibly. Add `v2` and keep `v1` until every consumer has migrated. |

## How a plugin adds its endpoints

1. Agree the endpoint shape with the consumers (frontend, BFF and the plugin).
2. Add the path to `alinha.yaml` under `/api/<plugin-id>/`. Add `Tenant`, `Page` and `PageSize` where they
   apply, and reference the shared error responses.
3. Put request and response schemas under `components.schemas`; reuse `PageMeta` for every list.
4. Run `pnpm gen:api` and commit `alinha.yaml` and `schema.generated.ts` together.
5. Open the contract PR in this repo first. After it is merged, the plugin imports the types from
   `@alinha/api-client` and opens its own PR. This is the multi-repo rule in the main `README.md`.
6. Remove the temporary `/api/{plugin}/ping` path once the plugin has a real endpoint.

Example for a plugin's first list endpoint:

```yaml
paths:
  /api/risk-assets/assets:
    get:
      tags: [risk-assets]
      operationId: listAssets
      summary: List assets
      parameters:
        - $ref: '#/components/parameters/Tenant'
        - $ref: '#/components/parameters/Page'
        - $ref: '#/components/parameters/PageSize'
      responses:
        '200':
          description: A page of assets.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AssetPage'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'

components:
  schemas:
    AssetPage:
      allOf:
        - $ref: '#/components/schemas/PageMeta'
        - type: object
          required: [items]
          properties:
            items:
              type: array
              items:
                $ref: '#/components/schemas/Asset'
```

Do not add a second error shape, a tenant in the path, a per-plugin OpenAPI file, or hand-written DTOs in the
frontend.
