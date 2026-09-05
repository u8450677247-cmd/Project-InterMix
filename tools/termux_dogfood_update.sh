#!/usr/bin/env bash
set -euo pipefail

# Fetch a successful AniCloudAI Android artifact, replace the ephemeral CI
# debug signature with one private dogfood signature, verify it, and optionally
# hand it to Android's visible package installer. No key material enters Git.

repo="${ANICLOUD_GITHUB_REPOSITORY:-u8450677247-cmd/Project-InterMix}"
branch="${ANICLOUD_DOGFOOD_BRANCH:-feature/anicloud-e4b-cockpit-20260906}"
workflow="${ANICLOUD_ANDROID_WORKFLOW:-android-foundation}"
artifact="${ANICLOUD_ANDROID_ARTIFACT:-AniCloudAI-e4b-cockpit-debug}"
key_root="${ANICLOUD_DOGFOOD_KEY_DIR:-$HOME/.local/share/anicloud-dogfood-signing}"
keystore="$key_root/anicloud-dogfood.jks"
password_file="$key_root/keystore.password"
key_alias="anicloud-dogfood"
downloads="${ANICLOUD_DOWNLOADS_DIR:-$HOME/storage/downloads}"
initialize_key=false
open_installer=false
run_id=""
local_apk=""

usage() {
    printf '%s\n' \
        "Usage:" \
        "  $0 [--initialize-key] [--open] [--branch NAME] [--run-id ID]" \
        "  $0 [--initialize-key] [--open] --apk PATH" \
        "" \
        "The signing key remains in Termux-private storage. Back it up securely;" \
        "losing it prevents seamless updates to builds signed by that key."
}

while (($#)); do
    case "$1" in
        --initialize-key) initialize_key=true ;;
        --open) open_installer=true ;;
        --branch)
            shift
            branch="${1:?--branch requires a value}"
            ;;
        --run-id)
            shift
            run_id="${1:?--run-id requires a value}"
            ;;
        --apk)
            shift
            local_apk="${1:?--apk requires a value}"
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
    shift
done

if [[ -n "$run_id" && -n "$local_apk" ]]; then
    printf '%s\n' "Choose either --run-id or --apk, not both." >&2
    exit 2
fi

for command_name in sha256sum keytool apksigner; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Missing command: %s\n' "$command_name" >&2
        printf '%s\n' "On Termux, install the required JDK and Android signing tools first." >&2
        exit 1
    fi
done

umask 077
if [[ ! -f "$keystore" || ! -f "$password_file" ]]; then
    if [[ "$initialize_key" != true ]]; then
        printf '%s\n' \
            "No persistent dogfood signing identity exists yet." \
            "Re-run once with --initialize-key, inspect the printed certificate," \
            "then keep both files under $key_root private and backed up." >&2
        exit 1
    fi
    if [[ -e "$keystore" || -e "$password_file" ]]; then
        printf '%s\n' "Incomplete signing identity found; refusing to overwrite it." >&2
        printf 'Review this directory manually: %s\n' "$key_root" >&2
        exit 1
    fi
    mkdir -p "$key_root"
    password="$(LC_ALL=C od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    printf '%s\n' "$password" > "$password_file"
    chmod 600 "$password_file"
    keytool -genkeypair \
        -keystore "$keystore" \
        -storepass "$password" \
        -keypass "$password" \
        -alias "$key_alias" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 36500 \
        -dname "CN=AniCloudAI Private Dogfood,O=Project Intermix"
    unset password
    chmod 600 "$keystore"
    printf '%s\n' "Created a private dogfood signing identity. It was not uploaded."
fi

if [[ ! -d "$downloads" ]]; then
    printf 'Picker-visible downloads directory is unavailable: %s\n' "$downloads" >&2
    exit 1
fi

temp_root="$(mktemp -d "${TMPDIR:-/tmp}/anicloud-update.XXXXXX")"
cleanup() {
    if [[ -n "${temp_root:-}" && -d "$temp_root" ]]; then
        rm -r -- "$temp_root"
    fi
}
trap cleanup EXIT HUP INT TERM

source_apk=""
source_label=""
head_sha="local"
if [[ -n "$local_apk" ]]; then
    source_apk="$(realpath "$local_apk")"
    [[ -f "$source_apk" ]] || { printf 'APK not found: %s\n' "$source_apk" >&2; exit 1; }
    source_label="local"
else
    command -v gh >/dev/null 2>&1 || {
        printf '%s\n' "Missing command: gh" >&2
        exit 1
    }
    if [[ -z "$run_id" ]]; then
        run_record="$(gh run list \
            --repo "$repo" \
            --workflow "$workflow" \
            --branch "$branch" \
            --status success \
            --limit 1 \
            --json databaseId,headSha \
            --jq '.[0] | "\(.databaseId) \(.headSha)"')"
        [[ -n "$run_record" ]] || {
            printf 'No successful %s run found on %s.\n' "$workflow" "$branch" >&2
            exit 1
        }
        read -r run_id head_sha <<< "$run_record"
    else
        run_record="$(gh run view "$run_id" --repo "$repo" --json conclusion,headSha --jq '"\(.conclusion) \(.headSha)"')"
        read -r conclusion head_sha <<< "$run_record"
        [[ "$conclusion" == "success" ]] || {
            printf 'Workflow run %s is not successful: %s\n' "$run_id" "$conclusion" >&2
            exit 1
        }
    fi
    gh run download "$run_id" --repo "$repo" --name "$artifact" --dir "$temp_root/artifact"
    mapfile -t apk_candidates < <(find "$temp_root/artifact" -type f -name '*.apk' -print)
    [[ "${#apk_candidates[@]}" -eq 1 ]] || {
        printf 'Expected exactly one APK in artifact %s; found %s.\n' "$artifact" "${#apk_candidates[@]}" >&2
        exit 1
    }
    source_apk="${apk_candidates[0]}"
    source_label="$run_id"
fi

unsigned_sha="$(sha256sum "$source_apk" | awk '{print $1}')"
signed_apk="$downloads/AniCloudAI-dogfood-${source_label}.apk"
apksigner sign \
    --ks "$keystore" \
    --ks-key-alias "$key_alias" \
    --ks-pass "file:$password_file" \
    --key-pass "file:$password_file" \
    --out "$signed_apk" \
    "$source_apk"
apksigner verify --verbose --print-certs "$signed_apk"

signed_sha="$(sha256sum "$signed_apk" | awk '{print $1}')"
certificate_sha="$(apksigner verify --print-certs "$signed_apk" 2>/dev/null | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}')"
printf '%s\n' \
    "ANICLOUDAI DOGFOOD UPDATE READY" \
    "Repository: $repo" \
    "Branch: $branch" \
    "Workflow run: $source_label" \
    "Commit: $head_sha" \
    "Downloaded APK SHA-256: $unsigned_sha" \
    "Signed APK SHA-256: $signed_sha" \
    "Signing certificate SHA-256: ${certificate_sha:-unavailable}" \
    "APK: $signed_apk"

if [[ "$open_installer" == true ]]; then
    command -v termux-open >/dev/null 2>&1 || {
        printf '%s\n' "termux-open is unavailable; open the printed APK from Android Files." >&2
        exit 1
    }
    termux-open --content-type application/vnd.android.package-archive "$signed_apk"
else
    printf '%s\n' "Installer not opened. Re-run with --open after reviewing the provenance above."
fi
