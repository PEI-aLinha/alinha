# AI Service (Python + FastAPI, separate process)

RAG over the legislation (read-only knowledge base) and provenance (model, prompt version, sources,
date — RNF14). Only called by the core's AI Gateway (contract in `contracts/ai/`). No write access to
platform data (RNF11). Context arrives already redacted by the core (RNF13) and is treated as untrusted
data, never as instructions.

## LLM provider and EU processing

The provider is abstracted (`providers/`): `fake` for dev and tests, `openai_compat` for any provider
with an OpenAI-compatible Chat Completions API. The LLM must process data in the EU, so the host of
`LLM_BASE_URL` must be in `EU_LLM_HOSTS` (`src/ai_service/config.py`); otherwise the service does not
start. The list lives in code so approving a provider needs a reviewed PR. The URL must also use
`https` on the default port and carry no credentials; only `localhost`/`127.0.0.1` may use `http`
and any port.

| Host | Provider | Notes |
|---|---|---|
| `api.mistral.ai` | Mistral AI (FR) | La Plateforme |
| `api.scaleway.ai` | Scaleway (FR) | Generative APIs |
| `localhost`, `127.0.0.1` | self-hosted (vLLM, Ollama) | data does not leave the machine |

Before adding a host, check where the provider processes and stores data and add a row here.

## Configuration (env)

| Variable | Required | Default |
|---|---|---|
| `LLM_PROVIDER` | always (`fake` or `openai_compat`) | none: a missing value stops startup |
| `LLM_BASE_URL`, `LLM_MODEL` | with `openai_compat` | — |
| `LLM_TIMEOUT_SECONDS` | no | `30` |
| `MAX_REQUEST_BYTES` | no | `262144` (256 KiB); larger bodies get 413. `context` is also capped at 20 levels of nesting (422) |
| `BAO_ADDR` | no | `http://localhost:8200` |
| `BAO_TOKEN` | always (dev: `dev-ai-token`) | — |

## Secrets (RNF09)

Read from OpenBao at startup, never from code, env or versioned config. KV v2 path
`secret/alinha/ai-service` (policy `alinha-ai-service`, read-only):

- `llm.api-key`: the LLM provider's API key (only with `openai_compat`)
- `service.token`: the token the AI Gateway must send as `Authorization: Bearer <token>` on every `/v1`
  request. The core reads the same value from `secret/alinha/app` (`ai-service.token`). `/health` needs no token.

Seeded in dev by `deploy/openbao/init.sh`.

## Run and test

```bash
docker compose -f deploy/docker-compose.dev.yml up -d   # from the repo root (OpenBao)
cd ai-service
uv sync --extra dev
LLM_PROVIDER=fake BAO_TOKEN=dev-ai-token uv run uvicorn ai_service.main:app --port 8000
uv run pytest --cov
```
