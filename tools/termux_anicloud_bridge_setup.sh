#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: termux_anicloud_bridge_setup.sh [--project-root TERMUX_PATH] [--check]

Enables Termux's official RUN_COMMAND boundary for AniCloudAI. This does not
grant Android permission and does not run a project command.
EOF
}

termux_home="${HOME:?Termux HOME is unavailable}"
project_root="$PWD"
check_only=0

while (($#)); do
  case "$1" in
    --project-root)
      (($# >= 2)) || { printf 'Missing value for --project-root\n' >&2; exit 2; }
      project_root="$2"
      shift 2
      ;;
    --check)
      check_only=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$termux_home" in
  /data/data/com.termux/files/home) ;;
  *) printf 'This setup must run inside the official Termux home.\n' >&2; exit 1 ;;
esac

case "$project_root" in
  "$termux_home"|"$termux_home"/*) ;;
  *) printf 'Project root must remain inside %s\n' "$termux_home" >&2; exit 1 ;;
esac

[[ -d "$project_root" ]] || {
  printf 'Project root does not exist: %s\n' "$project_root" >&2
  exit 1
}

properties_dir="$termux_home/.termux"
properties_file="$properties_dir/termux.properties"

if ((check_only)); then
  if [[ -f "$properties_file" ]] && grep -q '^allow-external-apps=true$' "$properties_file"; then
    printf 'TERMUX RUN_COMMAND PROPERTY: READY\n'
  else
    printf 'TERMUX RUN_COMMAND PROPERTY: NOT ENABLED\n'
    exit 1
  fi
else
  mkdir -p "$properties_dir"
  temporary_file="$(mktemp "$properties_dir/termux.properties.XXXXXX")"
  trap 'rm -f -- "$temporary_file"' EXIT
  if [[ -f "$properties_file" ]]; then
    awk '!/^allow-external-apps=/' "$properties_file" > "$temporary_file"
  fi
  printf '\nallow-external-apps=true\n' >> "$temporary_file"
  chmod 600 "$temporary_file"
  mv -f -- "$temporary_file" "$properties_file"
  trap - EXIT
  if command -v termux-reload-settings >/dev/null 2>&1; then
    termux-reload-settings
  fi
  printf 'TERMUX RUN_COMMAND PROPERTY: ENABLED\n'
fi

printf '\nFinish in AniCloudAI:\n'
printf '1. Agents -> GRANT TERMUX COMMAND PERMISSION\n'
printf '2. Chat -> /exec workdir %s\n' "$project_root"
printf '3. Chat -> /exec on\n'
printf '4. Chat -> /exec status\n'
printf '\nEvery proposed command still waits for APPROVE & RUN in Agents.\n'
