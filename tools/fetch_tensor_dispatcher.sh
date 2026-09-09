#!/usr/bin/env bash
set -euo pipefail

readonly VERSION="2.1.6"
readonly ARCHIVE_SHA256="98aabbdce8607f6dc6ab7cb92217326eef24a8c97b973b69e62bd0ce14b7495b"
readonly ARCHIVE_URL="https://github.com/google-ai-edge/LiteRT/releases/download/v${VERSION}/litert_npu_runtime_libraries.zip"
readonly ARCHIVE_MEMBER="google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination_root="${1:-$repo_root/android/app/src/main/jniLibs/arm64-v8a}"
download_root="$(mktemp -d "${TMPDIR:-/tmp}/anicloud-tensor-dispatch.XXXXXX")"
archive="$download_root/litert_npu_runtime_libraries.zip"
partial="$download_root/libLiteRtDispatch_GoogleTensor.so"

curl --fail --location --retry 3 --connect-timeout 20 "$ARCHIVE_URL" --output "$archive"
printf '%s  %s\n' "$ARCHIVE_SHA256" "$archive" | sha256sum --check --status
unzip -p "$archive" "$ARCHIVE_MEMBER" > "$partial"
test -s "$partial"

mkdir -p "$destination_root"
install -m 0644 "$partial" "$destination_root/libLiteRtDispatch_GoogleTensor.so"
printf 'Pinned Google Tensor dispatcher %s installed at %s\n' \
  "$VERSION" "$destination_root/libLiteRtDispatch_GoogleTensor.so"
