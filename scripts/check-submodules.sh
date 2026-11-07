#!/usr/bin/env bash
# Fails if any submodule points to a commit that is not on the plugin repo's
# main branch (e.g. someone committed a pointer to a local branch or to a PR
# that has not been merged yet).
set -euo pipefail

fail=0
while read -r _ path; do
  git -C "$path" fetch -q origin main
  sha=$(git -C "$path" rev-parse HEAD)
  if git -C "$path" merge-base --is-ancestor "$sha" origin/main; then
    echo "ok     $path @ ${sha:0:8}"
  else
    echo "ERROR  $path @ ${sha:0:8} is not on the plugin repo's main"
    fail=1
  fi
done < <(git config -f .gitmodules --get-regexp '^submodule\..*\.path$')

exit $fail
