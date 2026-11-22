#!/bin/sh
# Checks that the production and staging compose files are valid and fully interpolated: `docker compose config`
# with placeholder values (ci-env.sh), so a typo or a new ${VAR:?} without a value fails in CI and not on the VM.
# CI runs it; locally: sh deploy/test-compose.sh (needs Docker Compose).
set -eu
cd "$(dirname "$0")"
. ./ci-env.sh

docker compose -f docker-compose.prod.yml config --quiet
echo "ok   docker-compose.prod.yml"
docker compose -f docker-compose.prod.yml -f docker-compose.staging.yml config --quiet
echo "ok   docker-compose.prod.yml + docker-compose.staging.yml"

# Every ${VAR:?} in the production file must be set by deploy.yml, or the first deploy stops on it.
missing=0
for v in $(grep -o '\${[A-Z_]*:?' docker-compose.prod.yml | sed 's/^\${//; s/:?$//' | sort -u); do
  if ! grep -q "^ *$v: \${{" ../.github/workflows/deploy.yml; then
    echo "FAIL $v is required by docker-compose.prod.yml but deploy.yml does not set it"
    missing=$((missing + 1))
  fi
done
if [ "$missing" -gt 0 ]; then exit 1; fi
echo "ok   deploy.yml sets every required variable"
