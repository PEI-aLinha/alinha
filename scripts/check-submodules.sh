#!/usr/bin/env bash
# Falha se algum submodule apontar para um commit que não está no main do
# repo do plugin (ex.: alguém fez commit no repo principal com um ponteiro
# para um branch local ou para um PR ainda não aceite).
set -euo pipefail

fail=0
while read -r _ path; do
  git -C "$path" fetch -q origin main
  sha=$(git -C "$path" rev-parse HEAD)
  if git -C "$path" merge-base --is-ancestor "$sha" origin/main; then
    echo "ok    $path @ ${sha:0:8}"
  else
    echo "ERRO  $path @ ${sha:0:8} não está no main do repo do plugin"
    fail=1
  fi
done < <(git config -f .gitmodules --get-regexp '^submodule\..*\.path$')

exit $fail
