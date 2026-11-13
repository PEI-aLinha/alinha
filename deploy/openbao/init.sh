#!/bin/sh
# Seeds the dev OpenBao (docker-compose.dev.yml) with the secrets the app expects.
# Dev-only values: same rule as the other credentials in docker-compose.dev.yml.
set -eu

until bao status >/dev/null 2>&1; do sleep 1; done

# Platform secrets read by the app at startup (spring.cloud.vault, context alinha/app).
bao kv put secret/alinha/app \
  db.username=alinha \
  db.password=alinha \
  smtp.password=change-me \
  llm.api-key=change-me

# Secrets read by the AI Service (see ai-service/README.md).
bao kv put secret/alinha/ai-service llm.api-key=change-me

# Policies live next to this script (mounted at /init) so tests can load the same files.
bao policy write alinha-app /init/alinha-app.hcl
bao policy write alinha-ai-service /init/alinha-ai-service.hcl

# Fixed dev token with the app policy, so the app never runs with the root token.
bao token create -id=dev-app-token -policy=alinha-app -orphan -period=768h >/dev/null 2>&1 || true
bao token create -id=dev-ai-token -policy=alinha-ai-service -orphan -period=768h >/dev/null 2>&1 || true

echo "OpenBao seeded: secret/alinha/app, secret/alinha/ai-service; tokens dev-app-token, dev-ai-token"
