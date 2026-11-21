#!/bin/sh
# Sets OpenBao up: the kv-v2 engine, the policies and the platform's tokens. Needs a root token.
# Dev and staging run it on every start through init.sh. Production runs it by hand, once after `bao operator init`
# and again after changing a policy file (deploy/DEPLOYMENT.md, "OpenBao"): the root token never leaves the operator.
#   ALINHA_BAO_APP_TOKEN   the app's token (required)
#   ALINHA_BAO_AI_TOKEN    the AI Service's token (empty: none)
#   ALINHA_BAO_SEED_TOKEN  production's seed job token (empty: none; dev seeds with the root token)
set -eu
: "${ALINHA_BAO_APP_TOKEN:?}"

# Dev mode mounts kv-v2 at secret/ by itself; a production server starts with no secrets engine.
if ! bao secrets list -format=json | grep -q '"secret/"'; then
  bao secrets enable -path=secret kv-v2
fi

# Policies live next to this script (mounted at /init) so tests can load the same files.
bao policy write alinha-app /init/alinha-app.hcl
bao policy write alinha-ai-service /init/alinha-ai-service.hcl
bao policy write alinha-seed /init/alinha-seed.hcl

# Fixed tokens, so nothing at runtime uses the root token. Periodic: the OpenBao healthcheck and every seed renew
# them. An existing token (re-run) is renewed instead; any other failure stops the script.
create_token() {
  if bao token lookup "$1" >/dev/null 2>&1; then
    bao token renew "$1" >/dev/null
    echo "token for $2 already exists, renewed"  # never print the token itself
  else
    bao token create -id="$1" -policy="$2" -orphan -period=768h >/dev/null
    echo "token for $2 created"
  fi
}
create_token "$ALINHA_BAO_APP_TOKEN" alinha-app
if [ -n "${ALINHA_BAO_AI_TOKEN:-}" ]; then create_token "$ALINHA_BAO_AI_TOKEN" alinha-ai-service; fi
if [ -n "${ALINHA_BAO_SEED_TOKEN:-}" ]; then create_token "$ALINHA_BAO_SEED_TOKEN" alinha-seed; fi
