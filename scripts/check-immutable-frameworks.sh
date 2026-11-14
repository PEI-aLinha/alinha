#!/usr/bin/env bash
# Fails if a PR adds, modifies, deletes or renames a file inside a released framework version (NFR05).
# Released versions are listed in frameworks/RELEASES (one framework/vN per line).
# The list is read from origin/main, so a PR cannot unlist a version and edit it in the same change.
set -euo pipefail
cd "$(dirname "$0")/.."

released=$(git show origin/main:frameworks/RELEASES 2>/dev/null | grep -E '^[^#[:space:]]' || true)
if [ -z "$released" ]; then
  echo "No released versions on main -- nothing to protect."
  exit 0
fi

changed=$(git diff --name-only --diff-filter=AMDT --no-renames origin/main...HEAD -- frameworks/)

fail=0
while IFS= read -r version; do
  while IFS= read -r file; do
    case "$file" in
      "frameworks/$version/"*)
        echo "ERROR: $file belongs to released version '$version'. Create a new vN/ folder instead (NFR05)."
        fail=1
        ;;
    esac
  done <<< "$changed"
done <<< "$released"

[ $fail -eq 0 ] || exit 1
echo "ok -- released framework versions are unchanged"
