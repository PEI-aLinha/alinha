#!/bin/sh
# Runs production's OpenBao path end to end, with the openbao and openbao-seed services of docker-compose.prod.yml:
# a real (non-dev) server, `bao operator init` and unseal, bootstrap.sh with the root token, then the seed job with
# the narrow alinha-seed token under `up --wait`, twice (the second run is the next deploy, an update). Then checks
# that the seed token can do nothing else and that the app's token reads what it wrote.
# CI runs it; locally: sh deploy/openbao/test-seed.sh (needs Docker Compose and jq).
set -eu
cd "$(dirname "$0")/.."
. ./ci-env.sh

# `up --wait` fails on a one-shot service that nothing depends on ("exited (0)"). In production `app` depends on
# openbao-seed with service_completed_successfully, which `--wait` accepts: this stand-in for `app` keeps that shape.
OVERRIDE=$(mktemp)
cat >"$OVERRIDE" <<'EOF'
services:
  app-stand-in:
    image: openbao/openbao:2.4.1
    entrypoint: ["sleep", "infinity"]
    networks: [alinha]
    depends_on:
      openbao-seed:
        condition: service_completed_successfully
EOF
COMPOSE="docker compose --project-name alinha-seed-test -f docker-compose.prod.yml -f $OVERRIDE"
down() { $COMPOSE down --volumes --remove-orphans -t 0 >/dev/null 2>&1 || true; }
trap 'down; rm -f "$OVERRIDE"' EXIT
down

$COMPOSE up -d openbao
# `bao status` exits 2 while sealed or uninitialized, 1 when the server does not answer yet.
for _ in $(seq 1 30); do
  rc=0; $COMPOSE exec -T openbao bao status >/dev/null 2>&1 || rc=$?
  [ "$rc" -ne 1 ] && break
  sleep 1
done

init=$($COMPOSE exec -T openbao bao operator init -key-shares=1 -key-threshold=1 -format=json)
key=$(echo "$init" | jq -r '.unseal_keys_b64[0]')
root=$(echo "$init" | jq -r '.root_token')
$COMPOSE exec -T openbao bao operator unseal "$key" >/dev/null
$COMPOSE exec -T -e BAO_TOKEN="$root" -e ALINHA_BAO_APP_TOKEN="$BAO_APP_TOKEN" \
  -e ALINHA_BAO_SEED_TOKEN="$BAO_SEED_TOKEN" -e ALINHA_BAO_WORKER_TOKEN="$BAO_WORKER_TOKEN" openbao sh /init/bootstrap.sh

failures=0
expect_ok() {
  label="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "ok   $label"; else echo "FAIL $label"; failures=$((failures + 1)); fi
}
expect_denied() {
  label="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "FAIL $label: allowed"; failures=$((failures + 1)); else echo "ok   $label: denied"; fi
}

seed() { $COMPOSE exec -T -e BAO_TOKEN="$BAO_SEED_TOKEN" openbao bao "$@"; }
app() { $COMPOSE exec -T -e BAO_TOKEN="$BAO_APP_TOKEN" openbao bao "$@"; }
worker() { $COMPOSE exec -T -e BAO_TOKEN="$BAO_WORKER_TOKEN" openbao bao "$@"; }

# What deploy.yml does, limited to the seed job: it must exit 0 and `--wait` must accept it. The first deploy has
# the GitHub App set; the second (an update) has it removed, which must remove its key too.
DEPLOY="up -d --wait --wait-timeout 120 --force-recreate openbao-seed app-stand-in"
env GITHUB_APP_ID=7 GITHUB_APP_PRIVATE_KEY=test-key $COMPOSE $DEPLOY
echo "ok   seed job (first deploy)"
got=$(worker kv get -field=github_app_private_key secret/providers/code-repository)
if [ "$got" = "test-key" ]; then echo "ok   worker token reads the seeded GitHub App key"
else echo "FAIL worker token: GitHub App key is not the seeded value"; failures=$((failures + 1)); fi
$COMPOSE $DEPLOY
echo "ok   seed job (second deploy, GitHub App removed)"
expect_denied "worker token reads the GitHub App key after it was removed" \
  worker kv get -version=1 secret/providers/code-repository

expect_denied "seed token reads secret/alinha/app" seed kv get secret/alinha/app
expect_denied "seed token writes a tenant's credentials" seed kv put secret/tenants/acme/github token=x
expect_denied "seed token writes another platform path" seed kv put secret/alinha/other a=b
expect_denied "seed token writes a policy" seed policy write evil /init/alinha-app.hcl
expect_denied "seed token creates a token" seed token create -policy=alinha-app
expect_ok "seed token renews itself (healthcheck)" seed token renew

got=$(app kv get -field=db.password secret/alinha/app)
if [ "$got" = "$ALINHA_APP_DB_PASSWORD" ]; then echo "ok   app token reads the seeded db.password"
else echo "FAIL app token: db.password is not the seeded value"; failures=$((failures + 1)); fi
expect_denied "app token writes secret/alinha/app" app kv put secret/alinha/app a=b

# The worker reads a provider credential the app stored, and nothing else (docs/jobs.md, "Credentials for a collector").
expect_ok "app token stores a provider credential" app kv put secret/tenants/t1/providers/code-repository token=x
expect_denied "seed token deletes a tenant's credentials" seed kv metadata delete secret/tenants/t1/providers/code-repository
expect_ok "worker token reads a provider credential" worker kv get secret/tenants/t1/providers/code-repository
expect_ok "seed token writes a provider's platform credential" seed kv put secret/providers/code-repository github_app_id=1
expect_ok "worker token reads a provider's platform credential" worker kv get secret/providers/code-repository
expect_denied "worker token writes a provider's platform credential" worker kv put secret/providers/code-repository github_app_id=2
expect_denied "app token reads a provider's platform credential" app kv get secret/providers/code-repository
expect_denied "worker token writes a provider credential" worker kv put secret/tenants/t1/providers/code-repository token=y
expect_denied "worker token lists tenants" worker kv list secret/tenants
expect_denied "worker token reads secret/alinha/app" worker kv get secret/alinha/app
expect_denied "worker token reads outside providers/" worker kv get secret/tenants/t1/other
expect_ok "worker token renews itself (healthcheck)" worker token renew

if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "All OpenBao seed checks passed"
