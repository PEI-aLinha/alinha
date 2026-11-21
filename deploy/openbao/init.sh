#!/bin/sh
# Seeds OpenBao with the secrets the app expects. Dev (docker-compose.dev.yml) uses the defaults below;
# production (docker-compose.prod.yml) passes every value in the environment.
set -eu

until bao status >/dev/null 2>&1; do sleep 1; done

# Dev mode mounts kv-v2 at secret/ by itself; a production server starts with no secrets engine.
if ! bao secrets list -format=json | grep -q '"secret/"'; then
  bao secrets enable -path=secret kv-v2
fi

# Platform secrets read by the app at startup (spring.cloud.vault, context alinha/app).
bao kv put secret/alinha/app \
  db.username=alinha_app \
  db.password="${ALINHA_APP_DB_PASSWORD:-alinha_app}" \
  db.migration-username="${POSTGRES_USER:-alinha}" \
  db.migration-password="${POSTGRES_PASSWORD:-alinha}" \
  smtp.password=change-me \
  identity.provisioner-secret="${KEYCLOAK_PROVISIONER_SECRET:-dev-provisioner-secret}"

# Secrets read by the AI Service (see ai-service/README.md).
bao kv put secret/alinha/ai-service llm.api-key=change-me

# Policies live next to this script (mounted at /init) so tests can load the same files.
bao policy write alinha-app /init/alinha-app.hcl
bao policy write alinha-ai-service /init/alinha-ai-service.hcl

# Fixed dev token with the app policy, so the app never runs with the root token.
# An existing token (re-run, or every production deploy) is renewed instead; any other failure stops the script.
create_token() {
  if bao token lookup "$1" >/dev/null 2>&1; then
    bao token renew "$1" >/dev/null
    echo "token for $2 already exists, renewed"  # never print the token itself
  else
    bao token create -id="$1" -policy="$2" -orphan -period=768h >/dev/null
  fi
}
# Production (deploy/docker-compose.prod.yml) passes its own app token and no AI Service token yet.
APP_TOKEN="${ALINHA_BAO_APP_TOKEN:-dev-app-token}"
AI_TOKEN="${ALINHA_BAO_AI_TOKEN-dev-ai-token}"
create_token "$APP_TOKEN" alinha-app
if [ -n "$AI_TOKEN" ]; then create_token "$AI_TOKEN" alinha-ai-service; fi

echo "OpenBao seeded: secret/alinha/app, secret/alinha/ai-service; app and AI Service tokens created"
