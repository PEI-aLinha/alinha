#!/bin/sh
# Writes the platform secrets into OpenBao. Production's openbao-seed job runs it on every deploy with the narrow
# alinha-seed token (deploy/openbao/alinha-seed.hcl), so a changed GitHub secret reaches OpenBao. Dev and staging
# run it through init.sh. Every value is required: init.sh gives dev its defaults.
set -eu

until bao status >/dev/null 2>&1; do sleep 1; done

# The GitHub App's OAuth client secret is optional: the key is written only when it has a value, so "the App is not
# configured" is an absent key, never an empty one.
set --
[ -z "${GITHUB_APP_CLIENT_SECRET:-}" ] || set -- "code-repository.github-app.client-secret=$GITHUB_APP_CLIENT_SECRET"

# Platform secrets read by the app at startup (spring.cloud.vault, context alinha/app). `kv put` replaces the whole
# secret on purpose: it holds exactly these keys, and a key added by hand is lost on the next deploy (DEPLOYMENT.md).
bao kv put secret/alinha/app \
  db.username=alinha_app \
  db.password="${ALINHA_APP_DB_PASSWORD:?}" \
  db.migration-username="${POSTGRES_USER:?}" \
  db.migration-password="${POSTGRES_PASSWORD:?}" \
  smtp.password="${SMTP_PASSWORD:?}" \
  identity.provisioner-secret="${KEYCLOAK_PROVISIONER_SECRET:?}" \
  ai-service.token="${AI_SERVICE_TOKEN:?}" \
  "$@"

# Secrets read by the AI Service (see ai-service/README.md). service.token must equal ai-service.token above: the AI
# Gateway sends it and the AI Service checks it. Kept in both paths so neither policy can read the other's secrets.
bao kv put secret/alinha/ai-service \
  llm.api-key="${LLM_API_KEY:?}" \
  service.token="${AI_SERVICE_TOKEN:?}"

# The aLinha GitHub App's id and private key, read only by the job consumer to mint read-only installation tokens
# (code-repository provider). Optional: without them the App is not offered and clients connect with a token.
# A deploy without GITHUB_APP_ID leaves a stored key as it is, so a missing CI variable never breaks the existing App
# connections. Removing the key (with every old version, e.g. after a leak) is explicit: GITHUB_APP_REMOVE=true.
if [ "${GITHUB_APP_REMOVE:-}" = "true" ]; then
  [ -z "${GITHUB_APP_ID:-}" ] || { echo "GITHUB_APP_REMOVE=true and GITHUB_APP_ID are both set: choose one" >&2; exit 1; }
  bao kv metadata delete secret/providers/code-repository >/dev/null
  app="removed secret/providers/code-repository"
elif [ -n "${GITHUB_APP_ID:-}" ]; then
  bao kv put secret/providers/code-repository \
    github_app_id="$GITHUB_APP_ID" \
    github_app_private_key="${GITHUB_APP_PRIVATE_KEY:?GITHUB_APP_PRIVATE_KEY is required with GITHUB_APP_ID}" >/dev/null
  app="secret/providers/code-repository"
else
  app="secret/providers/code-repository left as it was"
fi

echo "OpenBao seeded: secret/alinha/app, secret/alinha/ai-service; $app"
