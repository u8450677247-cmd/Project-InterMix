#!/usr/bin/env bash
set -euo pipefail

# Bootstrap one private dogfood identity with native OpenSSL, store its encoded
# signing inputs as GitHub Actions secrets, and fetch APKs signed by CI. This
# avoids Android 17's incompatible Termux Java signing tools. Key material is
# never committed, printed, or placed in Android shared storage.

repo="${ANICLOUD_GITHUB_REPOSITORY:-u8450677247-cmd/Project-InterMix}"
branch="${ANICLOUD_DOGFOOD_BRANCH:-feature/anicloud-cockpit-convergence-20260907}"
workflow="${ANICLOUD_ANDROID_WORKFLOW:-android-foundation.yml}"
artifact="${ANICLOUD_ANDROID_ARTIFACT:-AniCloudAI-e4b-cockpit-dogfood}"
key_root="${ANICLOUD_DOGFOOD_KEY_DIR:-$HOME/.local/share/anicloud-dogfood-signing}"
private_key="$key_root/anicloud-dogfood.pk8"
certificate="$key_root/anicloud-dogfood-cert.pem"
downloads="${ANICLOUD_DOWNLOADS_DIR:-$HOME/storage/downloads}"
initialize_key=false
open_installer=false
run_id=""

usage() {
    printf '%s\n' \
        "Usage:" \
        "  $0 --initialize-key [--branch NAME]" \
        "  $0 [--open] [--branch NAME] [--run-id ID]" \
        "" \
        "Initialization generates the identity in Termux-private storage," \
        "uploads encoded copies as GitHub Actions secrets, and starts CI." \
        "Back up both identity files securely; losing them prevents updates."
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
            printf '%s\n' \
                "Local APK re-signing is unavailable on this Android runtime." \
                "Use the persistently signed GitHub Actions artifact instead." >&2
            exit 2
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

for command_name in gh sha256sum; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Missing command: %s\n' "$command_name" >&2
        exit 1
    fi
done

umask 077
if [[ "$initialize_key" == true ]]; then
    for command_name in openssl base64; do
        if ! command -v "$command_name" >/dev/null 2>&1; then
            printf 'Missing command: %s\n' "$command_name" >&2
            printf '%s\n' "On Termux, install OpenSSL with: pkg install openssl-tool" >&2
            exit 1
        fi
    done

    if [[ -f "$private_key" && -f "$certificate" ]]; then
        printf '%s\n' "Reusing the complete private dogfood identity in $key_root."
    elif [[ -e "$private_key" || -e "$certificate" || -e "$key_root/keystore.password" || -e "$key_root/anicloud-dogfood.jks" ]]; then
        printf '%s\n' "Incomplete or legacy signing identity found; refusing to overwrite it." >&2
        printf 'Move this directory to a backup location, then retry: %s\n' "$key_root" >&2
        exit 1
    else
        mkdir -p "$key_root"
        chmod 700 "$key_root"
        temp_pem="$(mktemp "$key_root/.private.XXXXXX.pem")"
        temp_key="$(mktemp "$key_root/.private.XXXXXX.pk8")"
        temp_cert="$(mktemp "$key_root/.certificate.XXXXXX.pem")"
        cleanup_identity_temporaries() {
            rm -f -- "$temp_pem" "$temp_key" "$temp_cert"
        }
        trap cleanup_identity_temporaries EXIT HUP INT TERM

        openssl genpkey \
            -algorithm RSA \
            -pkeyopt rsa_keygen_bits:4096 \
            -out "$temp_pem"
        openssl req \
            -new \
            -x509 \
            -sha256 \
            -days 36500 \
            -key "$temp_pem" \
            -out "$temp_cert" \
            -subj "/CN=AniCloudAI Private Dogfood/O=Project Intermix"
        openssl pkcs8 \
            -topk8 \
            -nocrypt \
            -in "$temp_pem" \
            -outform DER \
            -out "$temp_key"
        openssl pkey -inform DER -in "$temp_key" -check -noout
        openssl x509 -in "$temp_cert" -noout -checkend 86400

        mv -- "$temp_key" "$private_key"
        mv -- "$temp_cert" "$certificate"
        chmod 600 "$private_key" "$certificate"
        cleanup_identity_temporaries
        trap - EXIT HUP INT TERM
        printf '%s\n' "Created a private dogfood signing identity in $key_root."
    fi

    certificate_sha="$(
        openssl x509 -in "$certificate" -noout -fingerprint -sha256 |
            awk -F= '{print tolower($2)}' |
            tr -d ':'
    )"
    test -n "$certificate_sha"

    base64 "$private_key" | tr -d '\n' |
        gh secret set ANICLOUD_DOGFOOD_PRIVATE_KEY_B64 --repo "$repo"
    base64 "$certificate" | tr -d '\n' |
        gh secret set ANICLOUD_DOGFOOD_CERTIFICATE_B64 --repo "$repo"

    printf '%s\n' \
        "Persistent signing inputs uploaded as GitHub Actions secrets." \
        "Signing certificate SHA-256: $certificate_sha" \
        "Starting $workflow on $branch."
    gh workflow run "$workflow" --repo "$repo" --ref "$branch"
    printf '%s\n' \
        "CI was requested. Wait for it to finish, then run:" \
        "  $0 --open"
    exit 0
fi

if [[ ! -d "$downloads" ]]; then
    printf 'Picker-visible downloads directory is unavailable: %s\n' "$downloads" >&2
    exit 1
fi

temp_root="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/anicloud-update.XXXXXX")"
cleanup() {
    if [[ -n "${temp_root:-}" && -d "$temp_root" ]]; then
        rm -r -- "$temp_root"
    fi
}
trap cleanup EXIT HUP INT TERM

head_sha=""
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

gh run download "$run_id" \
    --repo "$repo" \
    --name "$artifact" \
    --dir "$temp_root/artifact"
mapfile -t apk_candidates < <(find "$temp_root/artifact" -type f -name '*.apk' -print)
mapfile -t checksum_candidates < <(find "$temp_root/artifact" -type f -name '*.apk.sha256' -print)
[[ "${#apk_candidates[@]}" -eq 1 ]] || {
    printf 'Expected exactly one APK in artifact %s; found %s.\n' "$artifact" "${#apk_candidates[@]}" >&2
    exit 1
}
[[ "${#checksum_candidates[@]}" -eq 1 ]] || {
    printf 'Expected exactly one APK checksum in artifact %s; found %s.\n' "$artifact" "${#checksum_candidates[@]}" >&2
    exit 1
}

source_apk="${apk_candidates[0]}"
checksum_file="${checksum_candidates[0]}"
(
    cd "$(dirname "$source_apk")"
    sha256sum -c "$(basename "$checksum_file")"
)

artifact_certificate_sha="$(
    tr -d '[:space:]' < "$temp_root/artifact/certificate-sha256.txt" |
        tr '[:upper:]' '[:lower:]'
)"
[[ "$artifact_certificate_sha" =~ ^[0-9a-f]{64}$ ]] || {
    printf 'Invalid artifact certificate fingerprint: %s\n' "$artifact_certificate_sha" >&2
    exit 1
}
artifact_commit="$(tr -d '[:space:]' < "$temp_root/artifact/commit.txt")"
[[ "$artifact_commit" == "$head_sha" ]] || {
    printf 'Artifact commit mismatch: run=%s artifact=%s\n' "$head_sha" "$artifact_commit" >&2
    exit 1
}

if [[ -f "$certificate" ]] && command -v openssl >/dev/null 2>&1; then
    local_certificate_sha="$(
        openssl x509 -in "$certificate" -noout -fingerprint -sha256 |
            awk -F= '{print tolower($2)}' |
            tr -d ':\n'
    )"
    [[ "$local_certificate_sha" == "$artifact_certificate_sha" ]] || {
        printf '%s\n' "Artifact signing certificate does not match the private dogfood identity." >&2
        exit 1
    }
fi

signed_sha="$(sha256sum "$source_apk" | awk '{print $1}')"
signed_apk="$downloads/AniCloudAI-dogfood-${run_id}.apk"
install -m 600 "$source_apk" "$signed_apk"
printf '%s\n' \
    "ANICLOUDAI PERSISTENT DOGFOOD UPDATE READY" \
    "Repository: $repo" \
    "Branch: $branch" \
    "Workflow run: $run_id" \
    "Commit: $head_sha" \
    "Signed APK SHA-256: $signed_sha" \
    "Signing certificate SHA-256: $artifact_certificate_sha" \
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
