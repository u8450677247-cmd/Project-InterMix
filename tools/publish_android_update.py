#!/usr/bin/env python3
"""Build a signed, static Intermix Android release-origin tree.

The destination contains only immutable APK bytes plus one channel envelope
holding the exact manifest bytes and detached Ed25519 signature. The manifest
private key is never copied into the destination. Android devices independently
verify the signature, APK hash, package/version, and pinned APK signing
certificate before PackageInstaller.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path


MAX_APK_BYTES = 512 * 1024 * 1024
MAX_MANIFEST_BYTES = 64 * 1024
MAX_ENVELOPE_BYTES = 96 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40,64}$")
RELEASE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
PACKAGE_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


def normalize_sha256(value: str, label: str) -> str:
    normalized = value.strip().lower().replace(":", "")
    if not SHA256_PATTERN.fullmatch(normalized):
        raise ValueError(f"{label} must be one SHA-256 fingerprint")
    return normalized


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iso8601(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_manifest(
    *,
    release_id: str,
    package_name: str,
    version_code: int,
    version_name: str,
    apk_path: str,
    apk_bytes: int,
    apk_sha256: str,
    apk_signer_sha256: str,
    source_commit: str,
    issued_at: datetime,
    not_before: datetime,
    expires_at: datetime,
    rollout_basis_points: int,
    min_current_version_code: int,
    channel: str = "community",
) -> bytes:
    if not RELEASE_ID_PATTERN.fullmatch(release_id):
        raise ValueError("release_id contains unsupported characters")
    if not PACKAGE_PATTERN.fullmatch(package_name):
        raise ValueError("package_name is invalid")
    if version_code <= 0 or min_current_version_code < 0:
        raise ValueError("version codes must be non-negative and target must be positive")
    if not version_name.strip() or len(version_name) > 96:
        raise ValueError("version_name is invalid")
    path_parts = apk_path.split("/")
    if (
        apk_path.startswith("/")
        or "\\" in apk_path
        or "%" in apk_path
        or any(part in {"", ".", ".."} for part in path_parts)
    ):
        raise ValueError("apk_path must be a plain relative path")
    if not 0 < apk_bytes <= MAX_APK_BYTES:
        raise ValueError("APK size is outside the supported range")
    apk_sha256 = normalize_sha256(apk_sha256, "APK hash")
    apk_signer_sha256 = normalize_sha256(apk_signer_sha256, "APK signer")
    source_commit = source_commit.strip().lower()
    if not COMMIT_PATTERN.fullmatch(source_commit):
        raise ValueError("source_commit must be a full commit identifier")
    if not 0 <= rollout_basis_points <= 10_000:
        raise ValueError("rollout_basis_points must be between 0 and 10000")
    if not issued_at <= not_before <= expires_at:
        raise ValueError("manifest validity timestamps are inconsistent")

    payload = {
        "schema": 1,
        "channel": channel,
        "release_id": release_id,
        "package_name": package_name,
        "version_code": version_code,
        "version_name": version_name.strip(),
        "apk_path": apk_path,
        "apk_bytes": apk_bytes,
        "apk_sha256": apk_sha256,
        "apk_signer_sha256": apk_signer_sha256,
        "source_commit": source_commit,
        "issued_at": iso8601(issued_at),
        "expires_at": iso8601(expires_at),
        "not_before": iso8601(not_before),
        "rollout_basis_points": rollout_basis_points,
        "min_current_version_code": min_current_version_code,
    }
    return (json.dumps(payload, ensure_ascii=True, indent=2) + "\n").encode("utf-8")


def sign_ed25519(manifest: bytes, private_key: Path, openssl: str = "openssl") -> bytes:
    mode = stat.S_IMODE(private_key.stat().st_mode)
    if mode & 0o077:
        raise ValueError("manifest private key must not be group/world accessible")
    descriptor, message_name = tempfile.mkstemp(prefix="intermix-manifest-", suffix=".json")
    message = Path(message_name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(manifest)
            target.flush()
            os.fsync(target.fileno())
        message.chmod(0o600)
        completed = subprocess.run(
            [
                openssl,
                "pkeyutl",
                "-sign",
                "-rawin",
                "-in",
                str(message),
                "-inkey",
                str(private_key),
            ],
            capture_output=True,
            check=False,
        )
    finally:
        message.unlink(missing_ok=True)
    if completed.returncode != 0 or len(completed.stdout) != 64:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:240]
        raise RuntimeError(f"Ed25519 manifest signing failed: {detail}")
    return base64.b64encode(completed.stdout) + b"\n"


def build_envelope(manifest: bytes, signature_base64: bytes) -> bytes:
    if not 0 < len(manifest) <= MAX_MANIFEST_BYTES:
        raise ValueError("manifest is outside the supported size range")
    try:
        encoded_signature = signature_base64.decode("ascii").strip()
        signature = base64.b64decode(encoded_signature, validate=True)
    except (UnicodeDecodeError, ValueError) as failure:
        raise ValueError("manifest signature is not canonical Base64") from failure
    if len(signature) != 64 or len(encoded_signature) != 88:
        raise ValueError("manifest signature must contain one Ed25519 signature")
    payload = {
        "schema": 1,
        "manifest_b64": base64.b64encode(manifest).decode("ascii"),
        "signature_b64": encoded_signature,
    }
    envelope = (json.dumps(payload, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    if len(envelope) > MAX_ENVELOPE_BYTES:
        raise ValueError("release envelope is outside the supported size range")
    return envelope


def find_apksigner(explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit).expanduser().resolve()
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
        raise ValueError("--apksigner is not executable")
    found = shutil.which("apksigner")
    if found:
        return Path(found).resolve()
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(variable)
        if not root:
            continue
        candidates = sorted((Path(root) / "build-tools").glob("*/apksigner"), reverse=True)
        for candidate in candidates:
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return candidate.resolve()
    raise ValueError("apksigner was not found; pass --apksigner explicitly")


def verify_apk_signer(apk: Path, expected_sha256: str, apksigner: Path) -> str:
    expected = normalize_sha256(expected_sha256, "expected APK signer")
    completed = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise ValueError("apksigner rejected the release APK")
    fingerprints = {
        normalize_sha256(match, "reported APK signer")
        for match in re.findall(
            r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]{64,95})",
            completed.stdout,
        )
    }
    if fingerprints != {expected}:
        raise ValueError("release APK signer does not match the pinned production certificate")
    return expected


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(data)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        path.chmod(0o644)
    finally:
        temporary.unlink(missing_ok=True)


def copy_immutable(source: Path, destination: Path, expected_sha256: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if destination.is_file() and sha256_file(destination) == expected_sha256:
            return
        raise ValueError(f"immutable release path already contains different bytes: {destination.name}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with source.open("rb") as read_from, os.fdopen(descriptor, "wb") as write_to:
            shutil.copyfileobj(read_from, write_to, length=1024 * 1024)
            write_to.flush()
            os.fsync(write_to.fileno())
        if sha256_file(temporary) != expected_sha256:
            raise RuntimeError("APK changed while the release bundle was being built")
        os.replace(temporary, destination)
        destination.chmod(0o644)
    finally:
        temporary.unlink(missing_ok=True)


def parse_instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamps must include a UTC offset")
    return parsed.astimezone(timezone.utc)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest-private-key", required=True, type=Path)
    parser.add_argument("--apksigner")
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--package-name", default="dev.anicloud.sovereign.prototype")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--expected-apk-signer-sha256", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--rollout-basis-points", type=int, default=500)
    parser.add_argument("--min-current-version-code", type=int, default=0)
    parser.add_argument("--issued-at", help="ISO-8601 UTC timestamp; defaults to now")
    parser.add_argument("--not-before", help="ISO-8601 UTC timestamp; defaults to issued-at")
    parser.add_argument("--valid-hours", type=int, default=336)
    args = parser.parse_args(argv)

    apk = args.apk.expanduser().resolve(strict=True)
    private_key = args.manifest_private_key.expanduser().resolve(strict=True)
    output = args.output_dir.expanduser().resolve()
    if not apk.is_file() or apk.stat().st_size not in range(1, MAX_APK_BYTES + 1):
        raise ValueError("APK is missing or outside the supported size range")
    if not private_key.is_file():
        raise ValueError("manifest private key is not a regular file")
    if args.valid_hours not in range(1, 24 * 31 + 1):
        raise ValueError("valid-hours must be between 1 and 744")

    signer = verify_apk_signer(
        apk,
        args.expected_apk_signer_sha256,
        find_apksigner(args.apksigner),
    )
    issued = parse_instant(args.issued_at) if args.issued_at else datetime.now(timezone.utc).replace(microsecond=0)
    not_before = parse_instant(args.not_before) if args.not_before else issued
    expires = issued + timedelta(hours=args.valid_hours)
    apk_hash = sha256_file(apk)
    apk_path = f"releases/{args.release_id}/intermix-{args.version_code}.apk"
    manifest = build_manifest(
        release_id=args.release_id,
        package_name=args.package_name,
        version_code=args.version_code,
        version_name=args.version_name,
        apk_path=apk_path,
        apk_bytes=apk.stat().st_size,
        apk_sha256=apk_hash,
        apk_signer_sha256=signer,
        source_commit=args.source_commit,
        issued_at=issued,
        not_before=not_before,
        expires_at=expires,
        rollout_basis_points=args.rollout_basis_points,
        min_current_version_code=args.min_current_version_code,
    )
    signature = sign_ed25519(manifest, private_key, args.openssl)
    envelope = build_envelope(manifest, signature)

    copy_immutable(apk, output / apk_path, apk_hash)
    atomic_write(output / "channels/community/release.json", envelope)
    summary = {
        "release_id": args.release_id,
        "version_code": args.version_code,
        "apk_sha256": apk_hash,
        "apk_signer_sha256": signer,
        "rollout_basis_points": args.rollout_basis_points,
        "origin_tree": str(output),
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as failure:
        print(f"release publisher failed: {failure}", file=sys.stderr)
        raise SystemExit(2)
