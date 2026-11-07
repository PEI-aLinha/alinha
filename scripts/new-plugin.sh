#!/usr/bin/env bash
# Creates a new plugin from alinha-plugin-template and adds it as a submodule.
#
#   ./scripts/new-plugin.sh feature policy-evidence "Policy & Evidence"
#   ./scripts/new-plugin.sh provider identity-cloud "Identity / Cloud"
#
# Requires: gh authenticated (gh auth login) with permission to create repos in the org.
set -euo pipefail

ORG="${ORG:-PEI-aLinha}"
VISIBILITY="${VISIBILITY:---private}"

kind="${1:?kind: feature | provider}"
id="${2:?kebab-case id, e.g. policy-evidence}"
name="${3:?display name, e.g. \"Policy & Evidence\"}"

case "$kind" in
  feature)  repo="alinha-plugin-$id" ;;
  provider) repo="alinha-provider-$id" ;;
  *) echo "invalid kind: $kind (feature | provider)"; exit 1 ;;
esac
path="plugins/$kind/$id"

cd "$(git rev-parse --show-toplevel)"
[ -e "$path" ] && { echo "$path already exists"; exit 1; }

echo "→ creating $ORG/$repo from the template"
gh repo create "$ORG/$repo" "$VISIBILITY" --template "$ORG/alinha-plugin-template"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for i in 1 2 3 4 5; do
  gh repo clone "$ORG/$repo" "$tmp/$repo" -- -q 2>/dev/null && break
  echo "  (waiting for GitHub to finish generating the repo...)"; sleep 3
done

(cd "$tmp/$repo" && ./scripts/init-plugin.sh "$kind" "$id" "$name" \
  && git add -A && git commit -qm "Initialise plugin $id" && git push -q origin HEAD:main)

echo "→ adding submodule at $path"
git submodule add "../$repo.git" "$path"
git commit -m "Add plugin $id as submodule"

echo
echo "Done. Still to do: git push, and add the plugin owner to $repo's CODEOWNERS."
