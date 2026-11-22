#!/bin/sh
# Dev (docker-compose.dev.yml) and staging: sets OpenBao up (root token) and seeds it, on every start.
# Dev gets the defaults below; staging passes its own values. Production does not use this file: see bootstrap.sh
# and secrets.sh.
set -eu

until bao status >/dev/null 2>&1; do sleep 1; done

export ALINHA_APP_DB_PASSWORD="${ALINHA_APP_DB_PASSWORD:-alinha_app}"
export POSTGRES_USER="${POSTGRES_USER:-alinha}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-alinha}"
export SMTP_PASSWORD="${SMTP_PASSWORD:-change-me}"
export LLM_API_KEY="${LLM_API_KEY:-change-me}"
export AI_SERVICE_TOKEN="${AI_SERVICE_TOKEN:-dev-ai-service-token}"
export KEYCLOAK_PROVISIONER_SECRET="${KEYCLOAK_PROVISIONER_SECRET:-dev-provisioner-secret}"
export ALINHA_BAO_APP_TOKEN="${ALINHA_BAO_APP_TOKEN:-dev-app-token}"
# Staging passes an empty AI Service token (no AI Service yet); dev gets its fixed one.
export ALINHA_BAO_AI_TOKEN="${ALINHA_BAO_AI_TOKEN-dev-ai-token}"

sh /init/bootstrap.sh
# Staging seeds like production, with the narrow alinha-seed token bootstrap.sh just created; dev has none and seeds
# with the root token.
if [ -n "${ALINHA_BAO_SEED_TOKEN:-}" ]; then
  BAO_TOKEN="$ALINHA_BAO_SEED_TOKEN" sh /init/secrets.sh
else
  sh /init/secrets.sh
fi
