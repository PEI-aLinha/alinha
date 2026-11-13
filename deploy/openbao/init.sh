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
# Only an existing token (re-run) is skipped; any other failure stops the script.
create_token() {
  if bao token lookup "$1" >/dev/null 2>&1; then
    echo "token $1 already exists"
  else
    bao token create -id="$1" -policy="$2" -orphan -period=768h >/dev/null
  fi
}
create_token dev-app-token alinha-app
create_token dev-ai-token alinha-ai-service

echo "OpenBao seeded: secret/alinha/app, secret/alinha/ai-service; tokens dev-app-token, dev-ai-token"
