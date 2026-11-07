# Cross-language contracts

Single source of the formats shared between Kotlin, Python and TypeScript.
A PR that changes a contract changes **both sides together** (see the main README > "Changing the plugin-api or a contract").

| Folder | Between | Format |
|---|---|---|
| `jobs/` | Job Dispatcher (Kotlin) ↔ workers (Python) via Redis Streams | JSON Schema, one file per type and version (`<type>.v<n>.schema.json`) |
| `openapi/` | BFF/frontend ↔ aLinha Application | OpenAPI 3 (`alinha.yaml`), generates `@alinha/api-client` |
| `ai/` | AI Gateway (Kotlin) ↔ AI Service (Python) | OpenAPI 3 |

Rule: never change a published schema in an incompatible way — create `v2` and keep `v1` until every consumer has migrated.
