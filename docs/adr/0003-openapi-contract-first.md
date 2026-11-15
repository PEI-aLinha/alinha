# ADR 0003 — Contract-first OpenAPI and generated TypeScript types

- **Status:** accepted
- **Date:** 2026-11-15
- **Issues:** #6 (OpenAPI contract v1 and generated api-client)
- **Refs:** `contracts/openapi/`, `docs/adr/0002-tenant-selection-header.md`

## Context
The browser talks only to the BFF, and the BFF forwards requests to the aLinha Application. The frontend
shell and every plugin need the same request and response types. Today `@alinha/api-client` is hand-written,
so it drifts as soon as an endpoint changes. The backend controllers are still walking skeletons, and there
is no BFF implementation yet, so generating the document from running code would describe almost nothing.

## Decision
- `contracts/openapi/alinha.yaml` is the **single public HTTP contract**, written in **OpenAPI 3.1.0**.
  It covers everything the browser calls under `/api` through the BFF, including BFF-owned endpoints such
  as `/api/me`. Application endpoints that are not exposed through the BFF are not part of this document.
- **Contract-first:** the specification is reviewed before the implementation. Code follows the contract,
  and a contract change lands in the same PR as the regenerated types.
- **Errors** use RFC 9457 Problem Details (`application/problem+json`, `type`, `title`, `status`, `detail`,
  `instance`) with two extensions: `code` (stable machine-readable value) and `errors` (field-level
  validation messages).
- **Pagination** is page-based: query parameters `page` (0-based) and `pageSize` (default 20, maximum 100);
  the response carries `items`, `page`, `pageSize` and `total`.
- **IDs** are UUID strings. **Dates** are RFC 3339 UTC (`date-time`); date-only values use `date`.
- The **tenant never appears in the URL, query or body**. It comes from the token and, for consultants,
  from the `X-Alinha-Tenant` header validated by `TenantContext` (ADR 0002).
- TypeScript types are generated with a pinned `openapi-typescript` version into
  `frontend/packages/api-client/src/schema.generated.ts`, which is committed. CI regenerates and fails when
  the committed file differs from the contract.
- A plugin adds its endpoints to the same document under `/api/<plugin-id>/`. There is one published
  document, not one spec per plugin.

## Consequences
- (+) Frontend, BFF and application share one reviewed contract, and the TypeScript types cannot drift
  without CI noticing.
- (+) A plugin's endpoint is documented where every consumer can find it, without a second client.
- (−) The contract is not enforced against the running backend yet: CI proves that types match the spec,
  not that controllers match the spec.
- (−) Several teams edit one YAML file, so unrelated changes can conflict. If that becomes frequent, split
  the source into fragments and bundle it into the single published document behind a new decision.
- (−) Changing the contract requires a PR in this repo before the plugin PR, following the repository rules.

## Alternatives considered
- **Generate with springdoc from the Spring application:** describes only the application, not the BFF,
  and cannot document endpoints that do not exist yet. It would also add a runtime dependency to get a file
  that should be reviewed before implementation.
- **One OpenAPI file per plugin:** avoids some merge conflicts, but the frontend would need several clients
  and no single place describes `/api`.
- **Hand-written TypeScript types:** already drifting; rejected.
