# Contratos entre linguagens

Fonte única dos formatos partilhados entre Kotlin, Python e TypeScript.
Um PR que muda um contrato muda **os dois lados no mesmo dia** (ver README principal > "Mudar a plugin-api ou um contrato").

| Pasta | Entre | Formato |
|---|---|---|
| `jobs/` | Job Dispatcher (Kotlin) ↔ workers (Python) via Redis Streams | JSON Schema, um ficheiro por tipo e versão (`<tipo>.v<n>.schema.json`) |
| `openapi/` | BFF/frontend ↔ aLinha Application | OpenAPI 3 (`alinha.yaml`), gera `@alinha/api-client` |
| `ai/` | AI Gateway (Kotlin) ↔ AI Service (Python) | OpenAPI 3 |

Regra: nunca alterar um schema publicado de forma incompatível — cria-se `v2` e mantém-se `v1` até todos os consumidores migrarem.
