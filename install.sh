#!/usr/bin/env bash
# Project Intermix public alpha installer: fresh setup, upgrade, and safe staging.

set -Eeuo pipefail

BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(tr -d '[:space:]' < "$BUNDLE_DIR/VERSION")"

project_dir="${INTERMIX_PROJECT_DIR:-$HOME/project-intermix}"
config_file="${INTERMIX_CONFIG_FILE:-$HOME/.config/intermix/config.json}"
model_path=""
model_label=""
librarian_model_path=""
librarian_model_label=""
user_name=""
assistant_name=""
workspace_dir=""
context_tokens=""
librarian_context_tokens=""
dual_model="auto"
sensitive_mode="off"
install_dependencies=1
non_interactive=0
force=0
dry_run=0
stage_dir=""


usage() {
    cat <<'USAGE'
Project Intermix installer

Usage:
  bash install.sh [options]

Options:
  --project-dir PATH       Installation root (default: ~/project-intermix)
  --model PATH             Existing .litertlm model; model weights are never bundled
  --model-label TEXT       Friendly model label shown in the cockpit
  --librarian-model PATH   Optional E2B .litertlm model for conversational routing
  --librarian-label TEXT   Friendly E2B label shown in diagnostics
  --librarian-context N    Physical KV ceiling for the optional E2B model
  --dual-model MODE        auto, on, or off (default: auto)
  --user-name TEXT         Name shown for the local user
  --assistant-name TEXT    Name shown for the local Core
  --workspace-dir PATH     Fixed agent workspace
  --context-tokens N       Physical KV ceiling (auto: 8000 on recommended memory)
  --sensitive-memory MODE  off or explicit (default for fresh installs: off)
  --skip-dependencies      Do not install missing Termux/Python dependencies
  --non-interactive        Use supplied/default values; requires an unambiguous model
  --dry-run                Run discovery and capability checks without installing
  --force                  Continue past unsupported-resource warnings
  -h, --help               Show this help

The installer never downloads model weights, asks for provider keys, launches
inference, or imports legacy conversations without a separate explicit command.
USAGE
}


fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}


note() {
    printf '\n[%s] %s\n' "$1" "$2"
}


warn() {
    printf 'WARNING: %s\n' "$*" >&2
}


require_value() {
    [ "$#" -ge 2 ] || fail "$1 requires a value"
}


while [ "$#" -gt 0 ]; do
    case "$1" in
        --project-dir)
            require_value "$@"; project_dir="$2"; shift 2 ;;
        --model)
            require_value "$@"; model_path="$2"; shift 2 ;;
        --model-label)
            require_value "$@"; model_label="$2"; shift 2 ;;
        --librarian-model)
            require_value "$@"; librarian_model_path="$2"; shift 2 ;;
        --librarian-label)
            require_value "$@"; librarian_model_label="$2"; shift 2 ;;
        --librarian-context)
            require_value "$@"; librarian_context_tokens="$2"; shift 2 ;;
        --dual-model)
            require_value "$@"; dual_model="$2"; shift 2 ;;
        --user-name)
            require_value "$@"; user_name="$2"; shift 2 ;;
        --assistant-name)
            require_value "$@"; assistant_name="$2"; shift 2 ;;
        --workspace-dir)
            require_value "$@"; workspace_dir="$2"; shift 2 ;;
        --context-tokens)
            require_value "$@"; context_tokens="$2"; shift 2 ;;
        --sensitive-memory)
            require_value "$@"; sensitive_mode="$2"; shift 2 ;;
        --skip-dependencies)
            install_dependencies=0; shift ;;
        --non-interactive)
            non_interactive=1; shift ;;
        --dry-run)
            dry_run=1; shift ;;
        --force)
            force=1; shift ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            fail "Unknown option: $1" ;;
    esac
done

case "$sensitive_mode" in
    off|explicit) ;;
    *) fail "--sensitive-memory must be off or explicit" ;;
esac

case "$context_tokens" in
    "") ;;
    *[!0-9]*) fail "--context-tokens must be an integer" ;;
esac

case "$librarian_context_tokens" in
    "") ;;
    *[!0-9]*) fail "--librarian-context must be an integer" ;;
esac

case "$dual_model" in
    auto|on|off) ;;
    *) fail "--dual-model must be auto, on, or off" ;;
esac

cleanup() {
    if [ -n "$stage_dir" ] && [ -d "$stage_dir" ]; then
        case "$stage_dir" in
            "$project_dir"/.install.*) rm -rf -- "$stage_dir" ;;
        esac
    fi
}
trap cleanup EXIT


note "1/8" "Checking host, memory, storage, and terminal geometry"

termux_detected=0
case "${PREFIX:-}" in
    *com.termux*) termux_detected=1 ;;
esac

if [ "$termux_detected" -ne 1 ]; then
    if [ "$force" -ne 1 ]; then
        fail "This public alpha installer is verified only for Termux. Use --force for contributor experiments."
    fi
    warn "Non-Termux installation is an unverified contributor path."
fi

machine="$(uname -m 2>/dev/null || printf unknown)"
case "$machine" in
    aarch64|arm64|x86_64) ;;
    *)
        [ "$force" -eq 1 ] || fail "Unsupported architecture candidate: $machine"
        warn "Architecture $machine is outside the current compatibility matrix."
        ;;
esac

mem_total_mb=0
mem_available_mb=0
if [ -r /proc/meminfo ]; then
    mem_total_mb="$(awk '/^MemTotal:/ {print int($2/1024); exit}' /proc/meminfo)"
    mem_available_mb="$(awk '/^MemAvailable:/ {print int($2/1024); exit}' /proc/meminfo)"
fi

if [ "$mem_total_mb" -gt 0 ] && [ "$mem_total_mb" -lt 8192 ]; then
    [ "$force" -eq 1 ] || fail "${mem_total_mb} MiB physical memory is below the 8 GiB community floor."
fi
if [ "$mem_total_mb" -gt 0 ] && [ "$mem_total_mb" -lt 12288 ]; then
    warn "${mem_total_mb} MiB physical memory: use a smaller model or reduced context first."
fi
if [ "$mem_available_mb" -gt 0 ] && [ "$mem_available_mb" -lt 4096 ]; then
    warn "Only ${mem_available_mb} MiB is currently available; close GPU-heavy/background applications before first inference."
fi

storage_available_mb="$(df -Pk "$HOME" | awk 'NR==2 {print int($4/1024)}')"
if [ "$storage_available_mb" -lt 6144 ]; then
    [ "$force" -eq 1 ] || fail "Less than 6 GiB free storage remains; LiteRT model caches may exhaust it."
elif [ "$storage_available_mb" -lt 10240 ]; then
    warn "Less than 10 GiB free storage remains; monitor compiled model caches closely."
fi

terminal_columns="$(tput cols 2>/dev/null || printf 0)"
if [ "$terminal_columns" -gt 0 ] && [ "$terminal_columns" -lt 90 ]; then
    warn "The ${terminal_columns}-column terminal will use compact mode; desktop mode or an external display is recommended."
fi

android_release=""
if command -v getprop >/dev/null 2>&1; then
    android_release="$(getprop ro.build.version.release 2>/dev/null || true)"
fi

printf 'Host: %s · Android %s · RAM %s MiB total / %s MiB available · storage %s MiB free\n' \
    "$machine" "${android_release:-not detected}" "${mem_total_mb:-unknown}" \
    "${mem_available_mb:-unknown}" "$storage_available_mb"


note "2/8" "Preparing required Termux and Python dependencies"

if ! command -v python >/dev/null 2>&1; then
    if [ "$install_dependencies" -eq 1 ] && [ "$termux_detected" -eq 1 ]; then
        pkg install -y python git coreutils findutils
    else
        fail "Python is missing and dependency installation is disabled."
    fi
elif [ "$install_dependencies" -eq 1 ] && [ "$termux_detected" -eq 1 ]; then
    pkg install -y git coreutils findutils >/dev/null
fi

python_major_minor="$(python -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
python - <<'PY' || fail "Python 3.11 or newer is required"
import sys
raise SystemExit(0 if sys.version_info >= (3, 11) else 1)
PY

dependencies_ready=0
if command -v litert-lm >/dev/null 2>&1 && python - <<'PY' >/dev/null 2>&1
import litert_lm
import textual
PY
then
    dependencies_ready=1
fi

if [ "$dependencies_ready" -ne 1 ]; then
    if [ "$install_dependencies" -ne 1 ]; then
        fail "Textual and LiteRT-LM are missing and --skip-dependencies was supplied."
    fi
    python -m pip install --disable-pip-version-check --no-input -r "$BUNDLE_DIR/requirements.txt"
fi

python - <<'PY' || fail "SQLite FTS5 is required"
import sqlite3
with sqlite3.connect(":memory:") as db:
    db.execute("CREATE VIRTUAL TABLE probe USING fts5(content)")
print(f"Python {__import__('platform').python_version()} · SQLite {sqlite3.sqlite_version} · FTS5 ready")
PY


note "3/8" "Selecting local identity, workspace, model, and physical context"

if [ -f "$config_file" ]; then
    mapfile -t existing_config < <(python - "$config_file" <<'PY'
import json, sys
try:
    value = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    value = {}
for key in (
    "user_name", "assistant_name", "model_path", "model_label",
    "workspace_dir", "context_tokens", "librarian_model_path",
    "librarian_model_label", "librarian_context_tokens", "dual_model_enabled",
):
    print(str(value.get(key, "")).replace("\n", " "))
PY
    )
    user_name="${user_name:-${existing_config[0]:-}}"
    assistant_name="${assistant_name:-${existing_config[1]:-}}"
    model_path="${model_path:-${existing_config[2]:-}}"
    model_label="${model_label:-${existing_config[3]:-}}"
    workspace_dir="${workspace_dir:-${existing_config[4]:-}}"
    context_tokens="${context_tokens:-${existing_config[5]:-}}"
    librarian_model_path="${librarian_model_path:-${existing_config[6]:-}}"
    librarian_model_label="${librarian_model_label:-${existing_config[7]:-}}"
    librarian_context_tokens="${librarian_context_tokens:-${existing_config[8]:-}}"
    if [ "$dual_model" = "auto" ]; then
        case "${existing_config[9]:-}" in
            True|true|1) dual_model="on" ;;
            False|false|0) dual_model="off" ;;
        esac
    fi
fi

user_name="${user_name:-Operator}"
assistant_name="${assistant_name:-Intermix Core}"

if [ -z "$workspace_dir" ]; then
    workspace_dir="$HOME/storage/downloads/intermix_workspace"
fi

if [ -z "$context_tokens" ]; then
    if [ "$mem_total_mb" -eq 0 ] || [ "$mem_total_mb" -ge 12288 ]; then
        context_tokens=8000
    else
        context_tokens=4096
    fi
fi

librarian_context_tokens="${librarian_context_tokens:-$context_tokens}"

if [ "$context_tokens" -lt 1024 ] || [ "$context_tokens" -gt 32768 ]; then
    fail "Context tokens must be between 1024 and 32768."
fi
if [ "$context_tokens" -gt 8000 ] && [ "$force" -ne 1 ]; then
    fail "The public alpha is bounded to 8000 tokens by default; use --force for larger experimental contexts."
fi
if [ "$librarian_context_tokens" -lt 1024 ] || [ "$librarian_context_tokens" -gt 32768 ]; then
    fail "Librarian context tokens must be between 1024 and 32768."
fi
if [ "$librarian_context_tokens" -gt 8000 ] && [ "$force" -ne 1 ]; then
    fail "The public alpha librarian is bounded to 8000 tokens by default; use --force for larger experiments."
fi

if [ "$non_interactive" -ne 1 ]; then
    printf 'User display name [%s]: ' "$user_name"
    IFS= read -r answer || true
    user_name="${answer:-$user_name}"
    printf 'Core display name [%s]: ' "$assistant_name"
    IFS= read -r answer || true
    assistant_name="${answer:-$assistant_name}"
fi

if [ -n "$model_path" ] && [ ! -f "$model_path" ]; then
    warn "Configured model no longer exists: $model_path"
    model_path=""
fi

if [ -n "$librarian_model_path" ] && [ ! -f "$librarian_model_path" ]; then
    warn "Configured librarian model no longer exists: $librarian_model_path"
    librarian_model_path=""
fi

if [ -z "$model_path" ]; then
    model_candidates=()
    librarian_candidates=()
    search_roots=("$project_dir/models" "$HOME/storage/shared" "$HOME/storage/downloads")
    for root in "${search_roots[@]}"; do
        [ -d "$root" ] || continue
        while IFS= read -r -d '' candidate; do
            case "$(basename "$candidate" | tr '[:upper:]' '[:lower:]')" in
                *e2b*) librarian_candidates+=("$candidate") ;;
                *) model_candidates+=("$candidate") ;;
            esac
        done < <(find "$root" -maxdepth 3 -type f -iname '*.litertlm' -print0 2>/dev/null)
    done
    if [ -z "$librarian_model_path" ] && [ "${#librarian_candidates[@]}" -eq 1 ]; then
        librarian_model_path="${librarian_candidates[0]}"
    fi
    if [ "${#model_candidates[@]}" -eq 1 ]; then
        model_path="${model_candidates[0]}"
    elif [ "${#model_candidates[@]}" -gt 1 ] && [ "$non_interactive" -ne 1 ]; then
        printf 'Detected LiteRT-LM models:\n'
        index=1
        for candidate in "${model_candidates[@]}"; do
            size="$(du -h "$candidate" | awk '{print $1}')"
            printf '  %s. %s  (%s)\n' "$index" "$candidate" "$size"
            index=$((index + 1))
        done
        printf 'Choose model number: '
        IFS= read -r selection
        case "$selection" in
            *[!0-9]*|"") fail "No valid model selection was supplied." ;;
        esac
        [ "$selection" -ge 1 ] && [ "$selection" -le "${#model_candidates[@]}" ] \
            || fail "Model selection is out of range."
        model_path="${model_candidates[$((selection - 1))]}"
    elif [ "${#model_candidates[@]}" -gt 1 ]; then
        fail "Multiple .litertlm models were found; pass --model PATH in non-interactive mode."
    fi
fi

[ -n "$model_path" ] || fail "No .litertlm model was found. Download a compatible model separately, then pass --model PATH."
[ -r "$model_path" ] || fail "The selected model is not readable: $model_path"
case "${model_path,,}" in
    *.litertlm) ;;
    *) fail "The selected model must use the .litertlm format." ;;
esac

model_bytes="$(wc -c < "$model_path")"
if [ "$model_bytes" -lt 104857600 ]; then
    [ "$force" -eq 1 ] || fail "The selected model is unexpectedly small and may be incomplete."
fi
model_label="${model_label:-$(basename "$model_path" .litertlm)}"

if [ -z "$librarian_model_path" ]; then
    librarian_model_path="$project_dir/models/gemma-4-E2B-it.litertlm"
fi
if [ -f "$librarian_model_path" ]; then
    [ -r "$librarian_model_path" ] || fail "The librarian model is not readable: $librarian_model_path"
    case "${librarian_model_path,,}" in
        *.litertlm) ;;
        *) fail "The librarian model must use the .litertlm format." ;;
    esac
    librarian_bytes="$(wc -c < "$librarian_model_path")"
    if [ "$librarian_bytes" -lt 104857600 ]; then
        [ "$force" -eq 1 ] || fail "The librarian model is unexpectedly small and may be incomplete."
    fi
fi
librarian_model_label="${librarian_model_label:-$(basename "$librarian_model_path" .litertlm)}"
dual_model_enabled=true
if [ "$dual_model" = "off" ]; then
    dual_model_enabled=false
fi

printf 'Identity: %s ↔ %s\n' "$user_name" "$assistant_name"
printf 'Reasoning model: %s\n' "$model_path"
if [ -f "$librarian_model_path" ] && [ "$dual_model_enabled" = true ]; then
    printf 'Librarian model: %s (one resident model at a time)\n' "$librarian_model_path"
elif [ "$dual_model_enabled" = true ]; then
    printf 'Librarian model: not installed; E4B fallback remains active\n'
else
    printf 'Librarian model: disabled\n'
fi
printf 'Workspace: %s\n' "$workspace_dir"
printf 'Physical context: %s tokens\n' "$context_tokens"
printf 'Librarian context: %s tokens\n' "$librarian_context_tokens"
printf 'Sensitive wellbeing capture: %s\n' "$sensitive_mode"

if [ "$dry_run" -eq 1 ]; then
    printf '\nDRY RUN COMPLETE — no Project Intermix files were changed.\n'
    exit 0
fi


note "4/8" "Running the complete isolated release test suite"
PYTHONPATH="$BUNDLE_DIR/engine" python -m unittest discover -s "$BUNDLE_DIR/tests" -v


note "5/8" "Creating a private rollback snapshot and versioned release"

if pgrep -af '[t]ui_app.py' >/dev/null 2>&1; then
    fail "A Project Intermix cockpit is running. Close it with Ctrl+Q, then rerun the installer."
fi

mkdir -p "$project_dir/releases" "$project_dir/memory" "$project_dir/models" "$project_dir/archive"
mkdir -p "$workspace_dir"

stamp="$(date +%Y%m%d_%H%M%S)"
snapshot_dir="$project_dir/archive/pre_public_install_$stamp"
mkdir -p "$snapshot_dir"

for item in engine VERSION; do
    if [ -e "$project_dir/$item" ]; then
        cp -a "$project_dir/$item" "$snapshot_dir/"
    fi
done
if [ -L "$project_dir/current" ]; then
    readlink "$project_dir/current" > "$snapshot_dir/previous_current_target.txt"
fi
for launcher in intermix sovereign intermix-providers intermix-doctor intermix-rollback; do
    if [ -e "${PREFIX:-/usr/local}/bin/$launcher" ]; then
        cp -a "${PREFIX:-/usr/local}/bin/$launcher" "$snapshot_dir/" 2>/dev/null || true
    fi
done

memory_db="$project_dir/memory/sovereign.db"
memory_existed=0
if [ -f "$memory_db" ]; then
    memory_existed=1
    python - "$memory_db" "$snapshot_dir/sovereign.db" <<'PY'
import sqlite3, sys
source = sqlite3.connect(sys.argv[1])
target = sqlite3.connect(sys.argv[2])
with target:
    source.backup(target)
source.close()
target.close()
PY
fi

stage_dir="$(mktemp -d "$project_dir/.install.$VERSION.XXXXXX")"
cp -a "$BUNDLE_DIR/engine" "$stage_dir/"
cp -a "$BUNDLE_DIR/bin" "$stage_dir/"
cp -a "$BUNDLE_DIR/tools" "$stage_dir/"
cp -a "$BUNDLE_DIR/tests" "$stage_dir/"
cp -a "$BUNDLE_DIR/docs" "$stage_dir/"
for item in VERSION README.md LICENSE NOTICE SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md \
    CHANGELOG.md requirements.txt requirements-dev.txt requirements-ci.txt install.sh; do
    [ -e "$BUNDLE_DIR/$item" ] && cp -a "$BUNDLE_DIR/$item" "$stage_dir/"
done

release_dir="$project_dir/releases/$VERSION"
if [ -e "$release_dir" ]; then
    release_dir="$project_dir/releases/${VERSION}-reinstall-$stamp"
fi
mv "$stage_dir" "$release_dir"
stage_dir=""


note "6/8" "Writing non-secret runtime configuration and initializing local memory"

mkdir -p "$(dirname "$config_file")"
python - "$config_file" "$project_dir" "$model_path" "$model_label" "$user_name" \
    "$assistant_name" "$workspace_dir" "$context_tokens" "$librarian_model_path" \
    "$librarian_model_label" "$librarian_context_tokens" "$dual_model_enabled" <<'PY'
import json, os, sys, tempfile
from pathlib import Path

(
    config_file, project_dir, model_path, model_label, user_name,
    assistant_name, workspace_dir, context_tokens, librarian_model_path,
    librarian_model_label, librarian_context_tokens, dual_model_enabled,
) = sys.argv[1:]
path = Path(config_file).expanduser()
path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(path.parent, 0o700)
payload = {
    "schema": 2,
    "project_name": "Project Intermix",
    "user_name": user_name,
    "assistant_name": assistant_name,
    "model_label": model_label,
    "project_dir": str(Path(project_dir).expanduser().resolve()),
    "model_path": str(Path(model_path).expanduser().resolve()),
    "librarian_model_path": str(Path(librarian_model_path).expanduser().resolve()),
    "librarian_model_label": librarian_model_label,
    "librarian_context_tokens": int(librarian_context_tokens),
    "dual_model_enabled": dual_model_enabled.casefold() == "true",
    "model_cache_dir": str(Path(project_dir).expanduser().resolve() / "models"),
    "memory_db": str(Path(project_dir).expanduser().resolve() / "memory" / "sovereign.db"),
    "identity_file": str(Path(project_dir).expanduser().resolve() / "memory" / "identity.txt"),
    "archive_dir": str(Path(project_dir).expanduser().resolve() / "archive"),
    "workspace_dir": str(Path(workspace_dir).expanduser().resolve()),
    "audio_bridge_dir": str(Path.home() / "storage" / "downloads" / "IntermixAudioBridge"),
    "context_tokens": int(context_tokens),
}
descriptor, temporary = tempfile.mkstemp(prefix=".config.", suffix=".tmp", dir=path.parent)
try:
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    os.chmod(path, 0o600)
except Exception:
    try:
        os.unlink(temporary)
    except OSError:
        pass
    raise
PY

INTERMIX_CONFIG_FILE="$config_file" PYTHONPATH="$release_dir/engine" \
python - "$memory_existed" "$sensitive_mode" "$VERSION" <<'PY'
import sys
from memory_store import MemoryStore

store = MemoryStore()
if sys.argv[1] == "0":
    mode = "explicit_only" if sys.argv[2] == "explicit" else "off"
    store.set_setting("sensitive_memory_mode", mode)
    store.set_setting("sensitive_retention_days", "365")
store.set_setting("installed_public_release", sys.argv[3])
status = store.status()
print(f"SQLite schema v{status['schema_version']} · {status['messages']} messages · {status['memories']} memories")
PY


note "7/8" "Activating launchers atomically"

if [ -e "$project_dir/current" ] && [ ! -L "$project_dir/current" ]; then
    fail "$project_dir/current exists but is not a symlink; move it aside and rerun."
fi
old_current=""
if [ -L "$project_dir/current" ]; then
    old_current="$(readlink "$project_dir/current")"
fi

temporary_link="$project_dir/.current.$stamp"
ln -s "$release_dir" "$temporary_link"
mv -f "$temporary_link" "$project_dir/current"
if [ -n "$old_current" ] && [ -e "$old_current" ]; then
    temporary_previous="$project_dir/.previous.$stamp"
    ln -s "$old_current" "$temporary_previous"
    mv -f "$temporary_previous" "$project_dir/previous"
fi

launcher_dir="${PREFIX:-/usr/local}/bin"
mkdir -p "$launcher_dir"
for launcher in intermix intermix-providers intermix-doctor intermix-rollback; do
    install -m 0755 "$release_dir/bin/$launcher" "$launcher_dir/$launcher"
done
ln -sf intermix "$launcher_dir/sovereign"


note "8/8" "Producing a non-mutating legacy migration report"
INTERMIX_CONFIG_FILE="$config_file" PYTHONPATH="$release_dir/engine" \
    python "$release_dir/engine/memory_migrate.py" --dry-run

printf '\nPROJECT INTERMIX PUBLIC ALPHA INSTALLED\n'
printf 'Release: %s\n' "$VERSION"
printf 'Runtime: %s\n' "$release_dir"
printf 'Rollback snapshot: %s\n' "$snapshot_dir"
printf 'Configuration: %s (mode 600; contains no provider keys)\n' "$config_file"
printf 'Launch: intermix  (compatibility alias: sovereign)\n'
printf 'Provider vault: intermix-providers\n'
printf 'Share-safe diagnostics: intermix-doctor --json\n'
printf 'The model was validated but not loaded; first launch may compile caches for several minutes.\n'
printf 'Legacy data was not imported. Review the report before ever using --apply.\n'
