#!/usr/bin/env bash
set -euo pipefail

remote="${1:-origin}"
branch="feature/intermix-librarian-continuity-20260919"
expected_base="329eed769c441c6687078c9b6cb4c032aa80f882"
implementation="194a63b5748b2a0ae9b9e0c7982a676100379b1b"

git rev-parse --is-inside-work-tree >/dev/null

if ! git diff --quiet || ! git diff --cached --quiet; then
    printf 'Refusing to update a dirty checkout.\n' >&2
    exit 1
fi

current="$(git rev-parse HEAD)"
if [[ "$current" == "$implementation" ]]; then
    printf 'LIBRARIAN-01 implementation is already applied.\n'
    exit 0
fi
if [[ "$current" != "$expected_base" ]]; then
    printf 'Expected base %s, found %s; refusing an implicit merge.\n' "$expected_base" "$current" >&2
    exit 1
fi

git fetch "$remote" "refs/heads/$branch"
git cat-file -e "$implementation^{commit}"
git merge --ff-only "$implementation"

printf 'Applied LIBRARIAN-01 implementation %s.\n' "$implementation"
