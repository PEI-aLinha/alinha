#!/bin/sh
# Writes the platform secrets into OpenBao. Production's openbao-seed job runs it on every deploy with the narrow
# alinha-seed token (deploy/openbao/alinha-seed.hcl), so a changed GitHub secret reaches OpenBao. Dev and staging
# run it through init.sh. Every value is required: init.sh gives dev its defaults.
set -eu

until bao status >/dev/null 2>&1; do sleep 1; done

# Platform secrets read by the app at startup (spring.cloud.vault, context alinha/app).
bao kv put secret/alinha/app \
  db.username=alinha_app \
  db.password="${ALINHA_APP_DB_PASSWORD:?}" \
  db.migration-username="${POSTGRES_USER:?}" \
  db.migration-password="${POSTGRES_PASSWORD:?}" \
  smtp.password="${SMTP_PASSWORD:?}" \
  identity.provisioner-secret="${KEYCLOAK_PROVISIONER_SECRET:?}"

# Secrets read by the AI Service (see ai-service/README.md).
bao kv put secret/alinha/ai-service llm.api-key="${LLM_API_KEY:?}"

echo "OpenBao seeded: secret/alinha/app, secret/alinha/ai-service"
