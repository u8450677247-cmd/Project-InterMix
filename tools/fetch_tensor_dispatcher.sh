#!/usr/bin/env bash
set -euo pipefail

readonly VERSION="2.2.0"
readonly ARCHIVE_SHA256="b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d"
readonly LIBRARY_SHA256="35b59265eb8595a1d28c2f69693b1cad39d1fa4a38c2c18d5d54550d079264ac"
readonly ARCHIVE_URL="https://github.com/google-ai-edge/LiteRT/releases/download/v${VERSION}/litert_npu_runtime_libraries.zip"
readonly ARCHIVE_MEMBER="google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination_root="${1:-$repo_root/android/app/src/main/jniLibs/arm64-v8a}"
download_root="$(mktemp -d "${TMPDIR:-/tmp}/anicloud-tensor-dispatch.XXXXXX")"
archive="$download_root/litert_npu_runtime_libraries.zip"
partial="$download_root/libLiteRtDispatch_GoogleTensor.so"
cleanup_dispatcher_download() {
  rm -f -- "$archive" "$partial"
  rmdir -- "$download_root" 2>/dev/null || true
}
trap cleanup_dispatcher_download EXIT HUP INT TERM

curl --fail --location --retry 3 --connect-timeout 20 "$ARCHIVE_URL" --output "$archive"
printf '%s  %s\n' "$ARCHIVE_SHA256" "$archive" | sha256sum --check --status
unzip -p "$archive" "$ARCHIVE_MEMBER" > "$partial"
test -s "$partial"
printf '%s  %s\n' "$LIBRARY_SHA256" "$partial" | sha256sum --check --status

mkdir -p "$destination_root"
install -m 0644 "$partial" "$destination_root/libLiteRtDispatch_GoogleTensor.so"
printf 'Pinned Google Tensor dispatcher %s installed at %s\n' \
  "$VERSION" "$destination_root/libLiteRtDispatch_GoogleTensor.so"
