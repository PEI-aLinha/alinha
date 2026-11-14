#!/usr/bin/env bash
# Fails if a PR modifies or deletes a file inside a released framework version.
# Released versions are listed in frameworks/RELEASES (one framework/vN per line).
# Add a line there when a version is published and used by existing assessments.
set -euo pipefail
cd "$(dirname "$0")/.."

releases_file="frameworks/RELEASES"
if [ ! -f "$releases_file" ] || ! grep -qE '^[^#[:space:]]' "$releases_file" 2>/dev/null; then
  echo "No released versions defined — nothing to protect."
  exit 0
fi

changed=$(git diff --name-status origin/main...HEAD -- 'frameworks/' | grep -E '^[MD]' | awk '{print $2}' || true)
if [ -z "$changed" ]; then
  exit 0
fi

fail=0
while IFS= read -r line; do
  [[ "$line" =~ ^[[:space:]]*# || -z "${line// /}" ]] && continue
  released="$line"
  if echo "$changed" | grep -qE "^frameworks/$released(/|$)"; then
    echo "ERROR: a file in released version '$released' was modified or deleted."
    echo "       Create a new vN/ folder instead (NFR05)."
    fail=1
  fi
done < "$releases_file"

[ $fail -eq 0 ] || exit 1
echo "ok -- immutable framework versions check passed"
