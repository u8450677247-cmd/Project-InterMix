#!/usr/bin/env bash
set -euo pipefail

# Bootstrap one private dogfood identity plus an independent Ed25519 manifest
# identity with native OpenSSL, configure only public updater trust anchors as
# repository variables, and fetch APKs signed by the isolated CI job. This
# avoids Android 17's incompatible Termux Java signing tools. Private key
# material is never committed, printed, or placed in Android shared storage.

repo="${ANICLOUD_GITHUB_REPOSITORY:-u8450677247-cmd/Project-InterMix}"
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checkout_root="$(git -C "$script_root/.." rev-parse --show-toplevel 2>/dev/null || true)"
detected_branch=""
if [[ -n "$checkout_root" ]]; then
    detected_branch="$(git -C "$checkout_root" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
fi
branch="${ANICLOUD_DOGFOOD_BRANCH:-${detected_branch:-feature/anicloud-cockpit-convergence-20260907}}"
workflow="${ANICLOUD_ANDROID_WORKFLOW:-android-foundation.yml}"
artifact="${ANICLOUD_ANDROID_ARTIFACT:-AniCloudAI-e4b-cockpit-dogfood}"
signing_environment="${ANICLOUD_DOGFOOD_ENVIRONMENT:-dogfood-signing}"
key_root="${ANICLOUD_DOGFOOD_KEY_DIR:-$HOME/.local/share/anicloud-dogfood-signing}"
private_key="$key_root/anicloud-dogfood.pk8"
certificate="$key_root/anicloud-dogfood-cert.pem"
manifest_key_root="${ANICLOUD_RELEASE_MANIFEST_KEY_DIR:-$HOME/.local/share/anicloud-release-manifest}"
manifest_private_key="$manifest_key_root/manifest-ed25519.pem"
downloads="${ANICLOUD_DOWNLOADS_DIR:-$HOME/storage/downloads}"
initialize_key=false
configure_release_trust=false
release_origin="${INTERMIX_RELEASE_ORIGIN_URL:-}"
open_installer=false
run_id=""

usage() {
    printf '%s\n' \
        "Usage:" \
        "  $0 --initialize-key [--origin HTTPS_URL] [--branch NAME]" \
        "  $0 --configure-release-trust --origin HTTPS_URL [--branch NAME]" \
        "  $0 [--open] [--branch NAME] [--run-id ID]" \
        "" \
        "Initialization generates the identity in Termux-private storage," \
        "uploads encoded copies only to the protected $signing_environment" \
        "GitHub environment, and removes repository-level secret copies." \
        "Release-trust configuration creates a separate local manifest key," \
        "publishes only its public key plus public origin/signer pins, then starts CI." \
        "Back up all private identity files securely; losing them prevents updates."
}

while (($#)); do
    case "$1" in
        --initialize-key) initialize_key=true ;;
        --configure-release-trust) configure_release_trust=true ;;
        --origin)
            shift
            release_origin="${1:?--origin requires a value}"
            ;;
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

if [[ "$initialize_key" == true && "$configure_release_trust" == true ]]; then
    printf '%s\n' "Choose either --initialize-key or --configure-release-trust, not both." >&2
    exit 2
fi
if [[ -n "$release_origin" && "$initialize_key" != true && "$configure_release_trust" != true ]]; then
    printf '%s\n' "--origin requires --initialize-key or --configure-release-trust." >&2
    exit 2
fi
if [[ "$open_installer" == true && ( "$initialize_key" == true || "$configure_release_trust" == true ) ]]; then
    printf '%s\n' "--open cannot be combined with key or release-trust configuration." >&2
    exit 2
fi

for command_name in gh sha256sum python3; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Missing command: %s\n' "$command_name" >&2
        exit 1
    fi
done

umask 077
if [[ "$initialize_key" == true || "$configure_release_trust" == true ]]; then
    for command_name in openssl base64 python3; do
        if ! command -v "$command_name" >/dev/null 2>&1; then
            printf 'Missing command: %s\n' "$command_name" >&2
            printf '%s\n' "On Termux, install OpenSSL with: pkg install openssl-tool" >&2
            exit 1
        fi
    done
    if [[ -z "$checkout_root" || ! -f "$checkout_root/tools/verify_android_release_trust.py" ]]; then
        printf '%s\n' "Run this helper from a complete Project Intermix Git checkout." >&2
        exit 1
    fi

    reviewer_count="$(
        gh api "repos/$repo/environments/$signing_environment" \
            --jq '[.protection_rules[]? | select(.type == "required_reviewers") | .reviewers[]?] | length'
    )"
    if [[ ! "$reviewer_count" =~ ^[1-9][0-9]*$ ]]; then
        printf '%s\n' \
            "Refusing to upload signing material to an unprotected environment." \
            "Create the GitHub environment '$signing_environment', add at least one required reviewer," \
            "and allow that reviewer to approve their own deployment for this one-person release lane." >&2
        exit 1
    fi
fi

if [[ "$initialize_key" == true ]]; then
    environment_secret_names="$(
        gh secret list \
            --repo "$repo" \
            --env "$signing_environment" \
            --json name \
            --jq '.[].name'
    )"
    if [[ -f "$private_key" && -f "$certificate" ]]; then
        printf '%s\n' "Reusing the complete private dogfood identity in $key_root."
    elif [[ -e "$private_key" || -e "$certificate" || -e "$key_root/keystore.password" || -e "$key_root/anicloud-dogfood.jks" ]]; then
        printf '%s\n' "Incomplete or legacy signing identity found; refusing to overwrite it." >&2
        printf 'Move this directory to a backup location, then retry: %s\n' "$key_root" >&2
        exit 1
    else
        if grep -Eq \
            '^(ANICLOUD_DOGFOOD_PRIVATE_KEY_B64|ANICLOUD_DOGFOOD_CERTIFICATE_B64)$' \
            <<< "$environment_secret_names"
        then
            printf '%s\n' \
                "Protected dogfood secrets already exist but the local identity is missing." \
                "Restore $key_root from backup; refusing to rotate the Android update identity." >&2
            exit 1
        fi
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
    configured_signer_pin="$(
        gh variable list \
            --repo "$repo" \
            --json name,value \
            --jq '.[] | select(.name == "INTERMIX_RELEASE_APK_CERT_SHA256") | .value'
    )"
    if [[ -n "$configured_signer_pin" && "$configured_signer_pin" != "$certificate_sha" ]]; then
        printf '%s\n' \
            "The local dogfood certificate differs from the repository's configured update pin." \
            "Restore the original identity; refusing to rotate the Android signer." >&2
        exit 1
    fi

    base64 "$private_key" | tr -d '\n' |
        gh secret set ANICLOUD_DOGFOOD_PRIVATE_KEY_B64 \
            --repo "$repo" \
            --env "$signing_environment"
    base64 "$certificate" | tr -d '\n' |
        gh secret set ANICLOUD_DOGFOOD_CERTIFICATE_B64 \
            --repo "$repo" \
            --env "$signing_environment"

    repository_secret_names="$(
        gh secret list --repo "$repo" --json name --jq '.[].name'
    )"
    for secret_name in \
        ANICLOUD_DOGFOOD_PRIVATE_KEY_B64 \
        ANICLOUD_DOGFOOD_CERTIFICATE_B64
    do
        if grep -Fxq -- "$secret_name" <<< "$repository_secret_names"; then
            gh secret delete "$secret_name" --repo "$repo"
        fi
    done

    printf '%s\n' \
        "Persistent signing inputs moved to the protected $signing_environment environment." \
        "Repository-level copies are absent." \
        "Signing certificate SHA-256: $certificate_sha"
fi

if [[ "$initialize_key" == true || "$configure_release_trust" == true ]]; then
    if [[ ! -f "$private_key" || ! -f "$certificate" ]]; then
        printf '%s\n' \
            "The complete dogfood signing identity is unavailable in $key_root." \
            "Run $0 --initialize-key first." >&2
        exit 1
    fi
    certificate_sha="$(
        openssl x509 -in "$certificate" -outform DER |
            sha256sum | awk '{print $1}'
    )"
    [[ "$certificate_sha" =~ ^[0-9a-f]{64}$ ]] || {
        printf '%s\n' "Could not derive the dogfood certificate SHA-256." >&2
        exit 1
    }
    configured_manifest_public_key="$(
        gh variable list \
            --repo "$repo" \
            --json name,value \
            --jq '.[] | select(.name == "INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64") | .value'
    )"
    configured_signer_pin="$(
        gh variable list \
            --repo "$repo" \
            --json name,value \
            --jq '.[] | select(.name == "INTERMIX_RELEASE_APK_CERT_SHA256") | .value'
    )"
    if [[ -n "$configured_signer_pin" && "$configured_signer_pin" != "$certificate_sha" ]]; then
        printf '%s\n' \
            "The local dogfood certificate differs from the repository's configured update pin." \
            "Restore the original identity; refusing to rotate the Android signer." >&2
        exit 1
    fi

    if [[ -z "$release_origin" ]]; then
        if [[ "$configure_release_trust" == true ]]; then
            printf '%s\n' "--configure-release-trust requires --origin with the public HTTPS base URL." >&2
            exit 2
        fi
        printf '%s\n' \
            "Signing identity initialization is complete; no APK was dispatched." \
            "The release build remains safely gated until the public origin is known." \
            "Next:" \
            "  $0 --configure-release-trust --origin https://YOUR_PUBLIC_HOST/intermix/ --branch $branch"
        exit 0
    fi

    if [[ -f "$manifest_private_key" ]]; then
        openssl pkey -in "$manifest_private_key" -check -noout
        printf '%s\n' "Reusing the private manifest identity in $manifest_key_root."
    elif [[ -e "$manifest_private_key" ]]; then
        printf 'Manifest key path exists but is not a regular file: %s\n' "$manifest_private_key" >&2
        exit 1
    else
        if [[ -n "$configured_manifest_public_key" ]]; then
            printf '%s\n' \
                "A manifest public key is already configured but its local private key is missing." \
                "Restore $manifest_private_key from backup; refusing silent key rotation." >&2
            exit 1
        fi
        mkdir -p "$manifest_key_root"
        chmod 700 "$manifest_key_root"
        temp_manifest_key="$(mktemp "$manifest_key_root/.manifest-ed25519.XXXXXX.pem")"
        cleanup_manifest_key_temporary() {
            rm -f -- "$temp_manifest_key"
        }
        trap cleanup_manifest_key_temporary EXIT HUP INT TERM
        openssl genpkey -algorithm ED25519 -out "$temp_manifest_key"
        openssl pkey -in "$temp_manifest_key" -check -noout
        mv -- "$temp_manifest_key" "$manifest_private_key"
        chmod 600 "$manifest_private_key"
        cleanup_manifest_key_temporary
        trap - EXIT HUP INT TERM
        printf '%s\n' "Created a private manifest identity in $manifest_key_root."
    fi

    manifest_public_key_b64="$(
        openssl pkey -in "$manifest_private_key" -pubout -outform DER |
            base64 | tr -d '\n'
    )"
    if [[ -n "$configured_manifest_public_key" && "$configured_manifest_public_key" != "$manifest_public_key_b64" ]]; then
        printf '%s\n' \
            "The local manifest key differs from the repository's configured public key." \
            "Restore the original manifest identity; refusing silent key rotation." >&2
        exit 1
    fi
    INTERMIX_RELEASE_ORIGIN_URL="$release_origin" \
    INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64="$manifest_public_key_b64" \
    INTERMIX_RELEASE_APK_CERT_SHA256="$certificate_sha" \
        python3 "$checkout_root/tools/verify_android_release_trust.py"

    gh variable set INTERMIX_RELEASE_ORIGIN_URL \
        --repo "$repo" \
        --body "$release_origin"
    gh variable set INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64 \
        --repo "$repo" \
        --body "$manifest_public_key_b64"
    gh variable set INTERMIX_RELEASE_APK_CERT_SHA256 \
        --repo "$repo" \
        --body "$certificate_sha"

    printf '%s\n' \
        "Validated public release trust anchors are configured for $repo." \
        "The manifest private key remains only in Termux-private storage." \
        "Release origin: $release_origin" \
        "Signing certificate SHA-256: $certificate_sha" \
        "Starting $workflow on $branch."
    gh workflow run "$workflow" \
        --repo "$repo" \
        --ref "$branch" \
        -f sign_dogfood=true
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
    candidate_records="$(gh run list \
        --repo "$repo" \
        --workflow "$workflow" \
        --branch "$branch" \
        --status success \
        --limit 30 \
        --json databaseId,headSha \
        --jq '.[] | [.databaseId, .headSha] | @tsv')"
    run_record=""
    while IFS=$'\t' read -r candidate_id candidate_sha; do
        [[ "$candidate_id" =~ ^[0-9]+$ && "$candidate_sha" =~ ^[0-9a-f]{40}$ ]] || continue
        artifact_names="$(gh api \
            "repos/$repo/actions/runs/$candidate_id/artifacts" \
            --paginate \
            --jq '.artifacts[].name')"
        if grep -Fxq -- "$artifact" <<< "$artifact_names"
        then
            run_record="$candidate_id $candidate_sha"
            break
        fi
    done <<< "$candidate_records"
    [[ -n "$run_record" ]] || {
        latest_record="$(gh run list \
            --repo "$repo" \
            --workflow "$workflow" \
            --branch "$branch" \
            --limit 1 \
            --json databaseId,status,conclusion,url \
            --jq 'if length == 0 then empty else .[0] | [.databaseId, .status, (.conclusion // "pending"), .url] | @tsv end')"
        if [[ -n "$latest_record" ]]; then
            read -r latest_id latest_status latest_conclusion latest_url <<< "$latest_record"
            printf 'No successful signed artifact found for %s on %s. Latest run %s is %s/%s: %s\n' \
                "$workflow" "$branch" "$latest_id" "$latest_status" "$latest_conclusion" "$latest_url" >&2
            printf '%s\n' \
                "Dispatch the workflow with sign_dogfood=true and approve the protected environment." >&2
        else
            printf 'No %s run found on %s. Push an Android change or dispatch the workflow first.\n' \
                "$workflow" "$branch" >&2
        fi
        exit 1
    }
    read -r run_id head_sha <<< "$run_record"
    [[ "$run_id" =~ ^[0-9]+$ && "$head_sha" =~ ^[0-9a-f]{40}$ ]] || {
        printf 'Invalid workflow selection for %s: %s\n' "$branch" "$run_record" >&2
        exit 1
    }
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
trust_summary="$temp_root/artifact/release-trust.json"
[[ -f "$trust_summary" ]] || {
    printf '%s\n' \
        "Signed artifact has no release-trust provenance; refusing the legacy debug artifact." >&2
    exit 1
}
IFS=$'\t' read -r artifact_origin artifact_manifest_key_sha artifact_pinned_signer < <(
    python3 - "$trust_summary" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)
expected = {
    "schema",
    "release_origin_url",
    "manifest_public_key_sha256",
    "apk_signer_sha256",
}
if set(payload) != expected or payload.get("schema") != 1:
    raise SystemExit("invalid release trust provenance")
origin = payload.get("release_origin_url", "")
manifest_key = payload.get("manifest_public_key_sha256", "")
signer = payload.get("apk_signer_sha256", "")
if not origin.startswith("https://") or any(character.isspace() for character in origin):
    raise SystemExit("invalid release origin provenance")
if re.fullmatch(r"[0-9a-f]{64}", manifest_key) is None:
    raise SystemExit("invalid manifest-key provenance")
if re.fullmatch(r"[0-9a-f]{64}", signer) is None:
    raise SystemExit("invalid signer provenance")
print(origin, manifest_key, signer, sep="\t")
PY
)
[[ "$artifact_pinned_signer" == "$artifact_certificate_sha" ]] || {
    printf '%s\n' "Signed artifact certificate does not match its embedded release pin." >&2
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
    "Manifest public-key SHA-256: $artifact_manifest_key_sha" \
    "Release origin: $artifact_origin" \
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
