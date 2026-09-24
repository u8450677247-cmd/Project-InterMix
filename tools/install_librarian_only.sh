#!/usr/bin/env bash
set -Eeuo pipefail

# Install the deterministic Librarian in private Termux storage without a
# Gemma model, Android UI, provider keys, or a listening network service.
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_dir="$HOME/project-intermix-librarian"
node_id="librarian-xcover-a"
dry_run=0

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --project-dir) [ "$#" -ge 2 ] || die "--project-dir requires a value"; project_dir="$2"; shift 2 ;;
        --node-id) [ "$#" -ge 2 ] || die "--node-id requires a value"; node_id="$2"; shift 2 ;;
        --dry-run) dry_run=1; shift ;;
        -h|--help)
            printf 'Usage: %s [--project-dir PRIVATE_TERMUX_PATH] [--node-id ID] [--dry-run]\n' "$0"
            exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

command -v python3 >/dev/null 2>&1 || die "Python 3 is required"
python3 -c 'import sqlite3' || die "Python 3 requires sqlite3"
[[ "$node_id" =~ ^[a-z][a-z0-9-]{1,62}$ ]] || die "invalid node ID"
project_dir="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).expanduser().resolve(strict=False))' "$project_dir")"
private_home="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve(strict=False))' "$HOME")"
case "$project_dir" in
    "$private_home"/*) ;;
    *) die "install inside the private Termux home directory" ;;
esac
[ ! -e "$project_dir" ] && [ ! -L "$project_dir" ] || die "destination already exists; never overwrite a Librarian installation"
runtime_files=(
    engine/librarian_cli.py engine/runtime_config.py
    engine/librarian/__init__.py engine/librarian/client.py
    engine/librarian/health.py engine/librarian/legacy.py
    engine/librarian/models.py engine/librarian/network.py
    engine/librarian/schema.sql engine/librarian/service.py
    engine/librarian/snapshots.py engine/librarian/store.py
    engine/librarian/workers.py
)
for file in "${runtime_files[@]}"; do
    [ -f "$source_dir/$file" ] || die "missing source file: $file"
done

printf 'Librarian-only target: %s\nNode ID: %s\n' "$project_dir" "$node_id"
if [ "$dry_run" -eq 1 ]; then
    printf 'Dry run: no files changed; no model or service started.\n'
    exit 0
fi

umask 077
parent_dir="$(dirname "$project_dir")"
mkdir -p "$parent_dir"
stage="$(mktemp -d "$parent_dir/.intermix-librarian-stage.XXXXXXXX")"
cleanup() { [ -z "$stage" ] || [ ! -d "$stage" ] || rm -r -- "$stage"; }
trap cleanup EXIT
mkdir -p "$stage/engine/librarian" "$stage/bin" "$stage/memory" "$stage/archive"
for file in "${runtime_files[@]}"; do
    cp -- "$source_dir/$file" "$stage/$file"
done
printf '%s\n' "$node_id" > "$stage/node-id"
cat > "$stage/bin/intermix-librarian" <<'LAUNCHER'
#!/usr/bin/env bash
set -eu
project_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
export INTERMIX_PROJECT_DIR="$project_dir"
# Isolate this pilot from a full Intermix configuration in the operator's shell.
export INTERMIX_CONFIG_FILE="$project_dir/config.json"
export INTERMIX_MEMORY_DB="$project_dir/memory/sovereign.db"
export INTERMIX_ARCHIVE_DIR="$project_dir/archive"
node_id="$(cat "$project_dir/node-id")"
exec python3 "$project_dir/engine/librarian_cli.py" --node-id "$node_id" "$@"
LAUNCHER
chmod 700 "$stage/bin/intermix-librarian"
mv -- "$stage" "$project_dir"
stage=""
printf 'Installed disabled. Initialize explicitly: %s/bin/intermix-librarian init\n' "$project_dir"
