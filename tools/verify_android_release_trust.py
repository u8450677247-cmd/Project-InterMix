#!/usr/bin/env python3
"""Validate the public trust anchors embedded in an Intermix release APK.

Debug builds may intentionally omit these values and keep the updater disabled.
Release builds must pass this validator and the equivalent Gradle gate before an
unsigned candidate is handed to the protected APK-signing job.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import ipaddress
import json
import os
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlsplit


ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class ReleaseTrustSummary:
    schema: int
    release_origin_url: str
    manifest_public_key_sha256: str
    apk_signer_sha256: str


def validate_release_trust(
    origin_url: str,
    manifest_public_key_base64: str,
    apk_signer_sha256: str,
) -> ReleaseTrustSummary:
    origin = origin_url.strip()
    if not origin or any(ord(character) < 0x21 for character in origin):
        raise ValueError("release origin is empty or contains whitespace/control characters")
    if "%" in origin or "\\" in origin:
        raise ValueError("release origin cannot contain encoded or backslash separators")

    try:
        parsed = urlsplit(origin)
        port = parsed.port
    except ValueError as failure:
        raise ValueError("release origin is not a valid URL") from failure
    if parsed.scheme.lower() != "https":
        raise ValueError("release origin must use HTTPS")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("release origin cannot contain credentials")
    if port not in (None, 443):
        raise ValueError("release origin must use the standard HTTPS port")
    if parsed.query or parsed.fragment:
        raise ValueError("release origin cannot contain a query or fragment")
    host = (parsed.hostname or "").lower()
    try:
        host.encode("ascii")
    except UnicodeEncodeError as failure:
        raise ValueError("release origin must use an ASCII or explicit IDNA hostname") from failure
    if (
        not host
        or "." not in host
        or host == "localhost"
        or host.endswith(".local")
        or host.endswith(".")
    ):
        raise ValueError("release origin must use a public DNS hostname")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ValueError("release origin cannot use a numeric IP address")

    compact_key = "".join(manifest_public_key_base64.split())
    if not compact_key:
        raise ValueError("manifest Ed25519 public key is empty")
    try:
        public_key = base64.b64decode(compact_key, validate=True)
    except (ValueError, binascii.Error) as failure:
        raise ValueError("manifest Ed25519 public key is not canonical Base64") from failure
    if base64.b64encode(public_key).decode("ascii") != compact_key:
        raise ValueError("manifest Ed25519 public key is not canonical Base64")
    if len(public_key) != len(ED25519_SPKI_PREFIX) + 32 or not public_key.startswith(
        ED25519_SPKI_PREFIX
    ):
        raise ValueError("manifest public key must be one Ed25519 X.509 DER public key")

    signer = apk_signer_sha256.strip()
    if not SHA256_PATTERN.fullmatch(signer):
        raise ValueError("APK certificate pin must be one lowercase SHA-256 digest")

    return ReleaseTrustSummary(
        schema=1,
        release_origin_url=origin,
        manifest_public_key_sha256=hashlib.sha256(public_key).hexdigest(),
        apk_signer_sha256=signer,
    )


def write_summary(path: Path, summary: ReleaseTrustSummary) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        with temporary.open("x", encoding="utf-8") as target:
            json.dump(asdict(summary), target, ensure_ascii=True, indent=2, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--origin",
        default=os.environ.get("INTERMIX_RELEASE_ORIGIN_URL", ""),
    )
    parser.add_argument(
        "--manifest-public-key-b64",
        default=os.environ.get("INTERMIX_RELEASE_MANIFEST_PUBLIC_KEY_B64", ""),
    )
    parser.add_argument(
        "--apk-cert-sha256",
        default=os.environ.get("INTERMIX_RELEASE_APK_CERT_SHA256", ""),
    )
    parser.add_argument("--summary", type=Path)
    arguments = parser.parse_args(argv)

    try:
        summary = validate_release_trust(
            arguments.origin,
            arguments.manifest_public_key_b64,
            arguments.apk_cert_sha256,
        )
        if arguments.summary is not None:
            write_summary(arguments.summary, summary)
    except (OSError, ValueError) as failure:
        print(f"release trust validation failed: {failure}", file=sys.stderr)
        return 2

    print(
        "release trust validated: "
        f"origin={summary.release_origin_url} "
        f"manifest-key-sha256={summary.manifest_public_key_sha256} "
        f"apk-signer-sha256={summary.apk_signer_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
