#!/usr/bin/env bash
# Cria um plugin novo a partir do alinha-plugin-template e liga-o como submodule.
#
#   ./scripts/new-plugin.sh feature policy-evidence "Policy & Evidence"
#   ./scripts/new-plugin.sh provider identity-cloud "Identity / Cloud"
#
# Requer: gh autenticado (gh auth login) com permissão para criar repos na org.
set -euo pipefail

ORG="${ORG:-PEI-aLinha}"
VISIBILITY="${VISIBILITY:---private}"

kind="${1:?tipo: feature | provider}"
id="${2:?id em kebab-case, ex.: policy-evidence}"
name="${3:?nome legível, ex.: \"Policy & Evidence\"}"

case "$kind" in
  feature)  repo="alinha-plugin-$id" ;;
  provider) repo="alinha-provider-$id" ;;
  *) echo "tipo inválido: $kind (feature | provider)"; exit 1 ;;
esac
path="plugins/$kind/$id"

cd "$(git rev-parse --show-toplevel)"
[ -e "$path" ] && { echo "$path já existe"; exit 1; }

echo "→ a criar $ORG/$repo a partir do template"
gh repo create "$ORG/$repo" "$VISIBILITY" --template "$ORG/alinha-plugin-template"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for i in 1 2 3 4 5; do
  gh repo clone "$ORG/$repo" "$tmp/$repo" -- -q 2>/dev/null && break
  echo "  (à espera que o GitHub termine de gerar o repo...)"; sleep 3
done

(cd "$tmp/$repo" && ./scripts/init-plugin.sh "$kind" "$id" "$name" \
  && git add -A && git commit -qm "Inicializa plugin $id" && git push -q origin HEAD:main)

echo "→ a adicionar submodule em $path"
git submodule add "../$repo.git" "$path"
git commit -m "Adiciona plugin $id como submodule"

echo
echo "Feito. Falta: git push, e acrescentar o dono do plugin ao CODEOWNERS de $repo."
