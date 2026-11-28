# Cross-language contracts

Single source of the formats shared between Kotlin, Python and TypeScript.
A PR that changes a contract changes **both sides together** (see the main README > "Changing the plugin-api or a contract").

| Folder | Between | Format |
|---|---|---|
| `jobs/` | Job Dispatcher (Kotlin) ↔ workers (Python) via Redis Streams | JSON Schema, one file per type and version (`<type>.v<n>.schema.json`) |
| `openapi/` | BFF/frontend ↔ aLinha Application | OpenAPI 3.1 (`alinha.yaml`), generates `@alinha/api-client` ([guide](openapi/README.md)) |
| `ai/` | AI Gateway (Kotlin) ↔ AI Service (Python) | OpenAPI 3 |
| `frameworks/` | Compliance Engine (Kotlin) — catalogue validation in CI | JSON Schema, one file per type and version (`<type>.v<n>.schema.json`) |

Rule: never change a published schema in an incompatible way — create `v2` and keep `v1` until every consumer has migrated.

## Shared examples (contract tests)

Each contract version has example payloads that **both sides** test against, so a change on one side cannot
silently break the other:

```
contracts/jobs/examples/<contract>/{valid,invalid}/<case>.json             e.g. jobs/examples/evidence-collect.v1/valid/minimal.json
contracts/ai/examples/ai-service.v1/<Schema>/{valid,invalid}/<case>.json   <Schema> = components.schemas name
<contract folder>/expected-errors.json                                     why each invalid case is refused
```

- `valid/` must pass the schema; `invalid/` must fail it.
- `expected-errors.json` maps each invalid case to **every** violation it causes, as `location: keyword` (plus the
  property name for `required`), e.g. `"missing-provider.json": ["/: required provider"]`. Both sides assert it, so an
  example cannot end up refused for some other reason. Keep one reason per invalid case.
- Kotlin (`backend/core`) and Python (`workers/job_consumer`, `ai-service`) load these same files in their tests.
- A schema change updates the examples in the same PR.
- CI (`scripts/check_contract_compat.py`) compares with the merge base and refuses incompatible changes to a
  published version:
  - `jobs/*.vN.schema.json` must stay byte-identical (`additionalProperties: false` makes any change incompatible for
    one side). A change is a new `vN+1` file.
  - `ai/*.vN.yaml` may grow (see the compatibility rule inside the file), but every `valid/` example at the merge base
    must still validate, and every `invalid/` example of a **response** schema (`AiDraft`, `Error`) must still be
    refused. A request schema may accept more.
  - Only what the examples exercise is checked: keep them representative of real payloads.
