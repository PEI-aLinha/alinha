#!/usr/bin/env bash
# Validates every framework catalogue against its JSON Schema (contracts/frameworks/).
# Needs check-jsonschema (brew install check-jsonschema / pipx install check-jsonschema).
set -euo pipefail
cd "$(dirname "$0")/.."

shopt -s nullglob
catalogues=(frameworks/*/v*/catalogue.yaml)

if [[ ${#catalogues[@]} -eq 0 ]]; then
  echo "No catalogues to validate."
  exit 0
fi

# A catalogue with a top-level `levels:` is in the v2 format (ADR 0007); the others are v1.
v1=() v2=()
for c in "${catalogues[@]}"; do
  if grep -q '^levels:' "$c"; then v2+=("$c"); else v1+=("$c"); fi
done
[[ ${#v1[@]} -eq 0 ]] || check-jsonschema --schemafile contracts/frameworks/catalogue.v1.schema.json "${v1[@]}"
[[ ${#v2[@]} -eq 0 ]] || check-jsonschema --schemafile contracts/frameworks/catalogue.v2.schema.json "${v2[@]}"
