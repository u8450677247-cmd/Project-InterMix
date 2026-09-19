#!/usr/bin/env bash
set -euo pipefail

expected="194a63b5748b2a0ae9b9e0c7982a676100379b1b"
git merge-base --is-ancestor "$expected" HEAD

PYTHONPATH=engine PYTHONDONTWRITEBYTECODE=1 \
    python3 -m unittest discover -s tests -v
python3 tools/public_release_audit.py .
git diff --check

printf 'LIBRARIAN-01 source verification passed.\n'
