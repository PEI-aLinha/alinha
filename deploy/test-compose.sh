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

# The app trusts X-Forwarded-For only from ALINHA_TRUSTED_PROXIES, a regex for the BFF's fixed address. If they
# disagree, the app sees the BFF as every client and the per-IP rate limit applies to everyone at once.
for files in "-f docker-compose.prod.yml" "-f docker-compose.prod.yml -f docker-compose.staging.yml"; do
  # shellcheck disable=SC2086
  docker compose $files config --format json | python3 -c '
import json, re, sys
c = json.load(sys.stdin)["services"]
ip = c["bff"]["networks"]["alinha"]["ipv4_address"]
regex = c["app"]["environment"]["ALINHA_TRUSTED_PROXIES"]
if not re.fullmatch(regex, ip):
    sys.exit(f"FAIL ALINHA_TRUSTED_PROXIES {regex!r} does not match BFF_IP {ip}")
' || exit 1
done
echo "ok   ALINHA_TRUSTED_PROXIES matches BFF_IP"
