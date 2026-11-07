# AI Service (Python + FastAPI, separate process)

RAG over the legislation (read-only knowledge base), redaction of personal data and provenance
(model, prompt version, sources, date — RNF14). Only called by the core's AI Gateway (contract in `contracts/ai/`).
No write access to platform data (RNF11). The LLM provider is abstracted here and processes data in the EU (RNF13).

## Secrets (NFR09)

Secrets are read from OpenBao at startup, never from code or versioned config:

- `BAO_ADDR` (dev: `http://localhost:8200`) and `BAO_TOKEN` (dev: `dev-ai-token`, policy `alinha-ai-service`)
- KV v2 path `secret/alinha/ai-service`, key `llm.api-key`

Seeded in dev by `deploy/openbao/init.sh`. Use the `hvac` client when the service is implemented.
