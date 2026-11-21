#!/bin/sh
# Saves the logs of every alinha-prod container on the VM and prints only a status summary.
#   deploy/save-logs.sh <lines per container> <run id>
# Used by .github/workflows/logs.yml and deploy.yml. The repository is public, and so are job logs, while
# container logs can hold personal data (emails, user and tenant ids, IPs). So the logs stay on the VM:
# read them over SSH from ~/alinha-logs/<run id>/ (owner only, deleted after 14 days).
set -eu

TAIL="$1"
RUN="$2"
case "$TAIL" in '' | *[!0-9]*) echo "::error::lines per container must be a number"; exit 1 ;; esac
case "$RUN" in '' | *[!0-9A-Za-z_-]*) echo "::error::run id must be alphanumeric"; exit 1 ;; esac

BASE="$HOME/alinha-logs"
DIR="$BASE/$RUN"
umask 077
mkdir -p "$DIR"
find "$BASE" -mindepth 1 -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +

containers=$(docker ps -a --filter label=com.docker.compose.project=alinha-prod --format '{{.Names}}' | sort)
if [ -z "$containers" ]; then
  echo "::warning::No alinha-prod containers found."
  exit 0
fi

printf '%-40s %-10s %-10s %s\n' CONTAINER STATE HEALTH RESTARTS
for name in $containers; do
  docker logs --tail "$TAIL" --timestamps "$name" >"$DIR/$name.log" 2>&1 || true
  state=$(docker inspect -f '{{.State.Status}}' "$name")
  health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$name")
  restarts=$(docker inspect -f '{{.RestartCount}}' "$name")
  printf '%-40s %-10s %-10s %s\n' "$name" "$state" "$health" "$restarts"
done
echo
echo "Full logs (last $TAIL lines each) saved on the VM in $DIR"
