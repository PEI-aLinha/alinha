#!/bin/sh
# Checks the web image's Nginx rules (alinha.conf.template): what must never be public returns 404, and the shell
# gets its security headers. Runs Nginx alone, with the same template and snippets as deploy/Dockerfile.web, so no
# upstream is needed. CI runs it; locally: sh deploy/nginx/test-routes.sh (needs Docker and curl).
set -eu
cd "$(dirname "$0")"

NAME=alinha-nginx-routes-test
NET=alinha-nginx-routes-test
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

HTML=$(mktemp -d)
echo '<!doctype html><title>shell</title>' >"$HTML/index.html"
chmod -R a+rX "$HTML"

# A user network gives Nginx Docker's DNS (127.0.0.11), which the template's resolver points at.
docker network create "$NET" >/dev/null
docker run -d --name "$NAME" --network "$NET" -p 127.0.0.1::80 -e TRUSTED_PROXY_CIDR=127.0.0.1/32 \
  -v "$PWD/alinha.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  -v "$PWD/proxy-headers.conf:/etc/nginx/snippets/proxy-headers.conf:ro" \
  -v "$PWD/security-headers.conf:/etc/nginx/snippets/security-headers.conf:ro" \
  -v "$PWD/spa-headers.conf:/etc/nginx/snippets/spa-headers.conf:ro" \
  -v "$HTML:/usr/share/nginx/html:ro" nginx:1.27-alpine >/dev/null
PORT=$(docker port "$NAME" 80/tcp | head -1 | sed 's/.*://')
BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 30); do curl -fsS -o /dev/null "$BASE/" 2>/dev/null && break; sleep 1; done

failures=0
expect_status() {
  got=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$2")
  if [ "$got" = "$1" ]; then echo "ok   $1 $2"; else echo "FAIL $2: expected $1, got $got"; failures=$((failures + 1)); fi
}
expect_header() {
  if curl -s -D - -o /dev/null "$BASE$1" | grep -qi "^$2"; then echo "ok   $1 has $2"
  else echo "FAIL $1: no $2 header"; failures=$((failures + 1)); fi
}
expect_no_header() {
  if curl -s -D - -o /dev/null "$BASE$1" | grep -qi "^$2"; then echo "FAIL $1: must not have $2"; failures=$((failures + 1))
  else echo "ok   $1 has no $2"; fi
}

# Never public (deploy/DEPLOYMENT.md): reached only through the SSH tunnel to Keycloak.
for path in /kc/admin /kc/admin/ /kc/admin/master/console/ /kc/admin/realms/alinha/users \
  /kc/realms/master /kc/realms/master/protocol/openid-connect/token /kc/realms/master/account \
  /kc/metrics /kc/health /kc/health/ready; do
  expect_status 404 "$path"
done

# The shell: index and SPA routes, with the CSP and the other security headers.
expect_status 200 /
expect_status 200 /portfolio/acme/dashboard
for path in / /portfolio /index.html; do
  expect_header "$path" 'content-security-policy:'
  expect_header "$path" 'strict-transport-security:'
  expect_header "$path" 'x-frame-options:'
done
# Keycloak's pages have inline scripts: the shell's CSP must not reach them (502 here: no Keycloak upstream).
expect_no_header /kc/realms/alinha/account 'content-security-policy:'
expect_header /kc/realms/alinha/account 'x-frame-options:'

if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "All Nginx route checks passed"
