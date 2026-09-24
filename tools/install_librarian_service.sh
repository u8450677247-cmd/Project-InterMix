#!/usr/bin/env bash
set -eu

usage() {
    cat <<'EOF'
Install the LIBRARIAN-01 HTTP service under Termux runit supervision.

Usage: tools/install_librarian_service.sh [options]

  --host ADDRESS         Bind address (default: 127.0.0.1)
  --port PORT            TCP port (default: 8765)
  --node-id ID           Stable node identifier (default: librarian-01)
  --token-file PATH      Mode-600 bearer-token file
  --snapshot-dir PATH    Local verified snapshot directory
  --archive-root PATH    Mounted DS215j archive root (optional)
  --enable               Start now and across supervisor restarts
  --dry-run              Print resolved paths and make no changes
  -h, --help             Show this help

The service is installed disabled unless --enable is explicit. HTTP binds only
to loopback. Use a separately configured authenticated TLS or encrypted-overlay
proxy for remote clients; do not expose the bearer token over ordinary LAN.
EOF
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

host="127.0.0.1"
port="8765"
node_id="librarian-01"
token_file="${INTERMIX_LIBRARIAN_TOKEN_FILE:-$HOME/.config/intermix/librarian.token}"
project_dir="${INTERMIX_PROJECT_DIR:-$HOME/project-intermix}"
snapshot_dir="${INTERMIX_LIBRARIAN_SNAPSHOT_DIR:-$project_dir/archive/librarian-snapshots}"
archive_root=""
enable_service="0"
dry_run="0"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --host) [ "$#" -ge 2 ] || die "--host requires a value"; host="$2"; shift 2 ;;
        --port) [ "$#" -ge 2 ] || die "--port requires a value"; port="$2"; shift 2 ;;
        --node-id) [ "$#" -ge 2 ] || die "--node-id requires a value"; node_id="$2"; shift 2 ;;
        --token-file) [ "$#" -ge 2 ] || die "--token-file requires a value"; token_file="$2"; shift 2 ;;
        --snapshot-dir) [ "$#" -ge 2 ] || die "--snapshot-dir requires a value"; snapshot_dir="$2"; shift 2 ;;
        --archive-root) [ "$#" -ge 2 ] || die "--archive-root requires a value"; archive_root="$2"; shift 2 ;;
        --trusted-overlay) die "--trusted-overlay was removed; bind to loopback behind an authenticated encrypted proxy" ;;
        --enable) enable_service="1"; shift ;;
        --dry-run) dry_run="1"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

case "$port" in
    ''|*[!0-9]*) die "--port must be an integer" ;;
esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || die "--port must be between 1 and 65535"
[ -n "$node_id" ] || die "--node-id cannot be empty"

case "$host" in
    127.0.0.1) ;;
    *) die "Librarian HTTP must bind to loopback" ;;
esac

prefix="${PREFIX:-}"
[ -n "$prefix" ] || die "PREFIX is unset; run this inside Termux"
command -v python >/dev/null 2>&1 || die "python is required"
command -v intermix-librarian >/dev/null 2>&1 || die "intermix-librarian is not installed"
command -v sv >/dev/null 2>&1 || die "Termux services are required (install the termux-services package)"
command -v svlogd >/dev/null 2>&1 || die "svlogd is required from the termux-services package"

# The model-free pilot records its node ID at install time. Never silently
# register the default Redmi identity against an XCover authority database.
if [ -f "$project_dir/node-id" ]; then
    installed_node_id="$(cat "$project_dir/node-id")"
    if [ "$node_id" = "librarian-01" ]; then
        node_id="$installed_node_id"
    fi
    [ "$node_id" = "$installed_node_id" ] || die "--node-id does not match the installed Librarian identity"
fi

service_dir="$prefix/var/service/intermix-librarian"
config_file="${INTERMIX_CONFIG_FILE:-$HOME/.config/intermix/config.json}"
librarian_command="$(command -v intermix-librarian)"

printf 'Service directory: %s\n' "$service_dir"
printf 'Bind: %s:%s\n' "$host" "$port"
printf 'Token file: %s\n' "$token_file"
printf 'Snapshots: %s\n' "$snapshot_dir"
if [ -n "$archive_root" ]; then
    printf 'Archive root: %s\n' "$archive_root"
else
    printf 'Archive root: not configured (NO_NAS is explicit)\n'
fi
if [ "$dry_run" = "1" ]; then
    printf 'Dry run: no changes made.\n'
    exit 0
fi

umask 077
mkdir -p "$(dirname "$token_file")" "$snapshot_dir" "$service_dir/log"
# A newly discovered runit directory must not race ahead of token creation or a
# complete run script. Existing services are stopped at the final policy step.
: > "$service_dir/down"
if command -v sv >/dev/null 2>&1; then
    sv down "$service_dir" >/dev/null 2>&1 || true
fi
if [ ! -e "$token_file" ]; then
    intermix-librarian --node-id "$node_id" init \
        --token-file "$token_file" --create-token --import-existing
else
    [ -f "$token_file" ] || die "token path is not a regular file: $token_file"
    token_mode="$(python - "$token_file" <<'PY'
import stat
import sys
from pathlib import Path
print(oct(stat.S_IMODE(Path(sys.argv[1]).stat().st_mode)))
PY
)"
    case "$token_mode" in
        0o600|0o400) ;;
        *) die "token file must be mode 600 or 400, found $token_mode" ;;
    esac
    intermix-librarian --node-id "$node_id" init --import-existing
fi

export INTERMIX_SERVICE_DIR="$service_dir"
export INTERMIX_LIBRARIAN_COMMAND="$librarian_command"
export INTERMIX_LIBRARIAN_HOST="$host"
export INTERMIX_LIBRARIAN_PORT="$port"
export INTERMIX_LIBRARIAN_NODE_ID="$node_id"
export INTERMIX_LIBRARIAN_TOKEN_FILE="$token_file"
export INTERMIX_LIBRARIAN_SNAPSHOT_DIR="$snapshot_dir"
export INTERMIX_LIBRARIAN_ARCHIVE_ROOT="$archive_root"
export INTERMIX_CONFIG_FILE="$config_file"

python <<'PY'
import os
import shlex
from pathlib import Path

service = Path(os.environ["INTERMIX_SERVICE_DIR"])
log_directory = service / "log" / "main"
log_directory.mkdir(parents=True, exist_ok=True)
command = [
    os.environ["INTERMIX_LIBRARIAN_COMMAND"],
    "--node-id", os.environ["INTERMIX_LIBRARIAN_NODE_ID"],
    "serve",
    "--host", os.environ["INTERMIX_LIBRARIAN_HOST"],
    "--port", os.environ["INTERMIX_LIBRARIAN_PORT"],
    "--token-file", os.environ["INTERMIX_LIBRARIAN_TOKEN_FILE"],
    "--snapshot-dir", os.environ["INTERMIX_LIBRARIAN_SNAPSHOT_DIR"],
]
archive = os.environ.get("INTERMIX_LIBRARIAN_ARCHIVE_ROOT", "")
if archive:
    command.extend(["--archive-root", archive])

run = "\n".join(
    (
        "#!/usr/bin/env sh",
        "set -eu",
        "exec 2>&1",
        "umask 077",
        "export INTERMIX_CONFIG_FILE=" + shlex.quote(os.environ["INTERMIX_CONFIG_FILE"]),
        "exec " + " ".join(shlex.quote(part) for part in command),
        "",
    )
)
log_run = "\n".join(
    (
        "#!/usr/bin/env sh",
        "set -eu",
        "log_dir=" + shlex.quote(str(log_directory)),
        "mkdir -p \"$log_dir\"",
        "exec svlogd -tt \"$log_dir\"",
        "",
    )
)
(service / "run").write_text(run, encoding="utf-8")
(service / "log" / "run").write_text(log_run, encoding="utf-8")
(log_directory / "config").write_text("s1048576\nn10\n", encoding="utf-8")
os.chmod(service / "run", 0o700)
os.chmod(service / "log" / "run", 0o700)
os.chmod(log_directory / "config", 0o600)
PY

if [ "$enable_service" = "1" ]; then
    rm -f "$service_dir/down"
    if command -v sv >/dev/null 2>&1; then
        sv restart "$service_dir" || die "service was installed but did not start"
    fi
    printf 'LIBRARIAN-01 installed and enabled.\n'
else
    : > "$service_dir/down"
    if command -v sv >/dev/null 2>&1; then
        sv down "$service_dir" >/dev/null 2>&1 || true
    fi
    printf 'LIBRARIAN-01 installed disabled. Re-run with --enable after review.\n'
fi
