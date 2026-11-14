#!/usr/bin/env bash
# Validates every framework catalogue against its JSON Schema (contracts/frameworks/).
# Needs check-jsonschema (brew install check-jsonschema / pipx install check-jsonschema).
set -euo pipefail
cd "$(dirname "$0")/.."

shopt -s nullglob
catalogues=(frameworks/*/v*/catalogue.yaml)

if [ ${#catalogues[@]} -eq 0 ]; then
  echo "No catalogues to validate."
  exit 0
fi

check-jsonschema --schemafile contracts/frameworks/catalogue.v1.schema.json "${catalogues[@]}"
