from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from publish_android_update import build_envelope, build_manifest, normalize_sha256, sign_ed25519


class AndroidUpdatePublisherTests(unittest.TestCase):
    def test_manifest_is_deterministic_bounded_and_device_compatible(self):
        issued = datetime(2026, 9, 17, 12, 0, tzinfo=timezone.utc)
        keyword = dict(
            release_id="community-23",
            package_name="dev.anicloud.sovereign.prototype",
            version_code=23,
            version_name="0.8.12-release-origin",
            apk_path="releases/community-23/intermix-23.apk",
            apk_bytes=42,
            apk_sha256="a" * 64,
            apk_signer_sha256="B" * 64,
            source_commit="c" * 40,
            issued_at=issued,
            not_before=issued,
            expires_at=issued + timedelta(days=14),
            rollout_basis_points=500,
            min_current_version_code=21,
        )
        first = build_manifest(**keyword)
        second = build_manifest(**keyword)
        self.assertEqual(first, second)
        payload = json.loads(first)
        self.assertEqual(payload["schema"], 1)
        self.assertEqual(payload["channel"], "community")
        self.assertEqual(payload["apk_signer_sha256"], "b" * 64)
        self.assertEqual(payload["rollout_basis_points"], 500)
        self.assertTrue(first.endswith(b"\n"))

        signature = base64.b64encode(bytes(range(64))) + b"\n"
        envelope = json.loads(build_envelope(first, signature))
        self.assertEqual(base64.b64decode(envelope["manifest_b64"]), first)
        self.assertEqual(base64.b64decode(envelope["signature_b64"]), bytes(range(64)))

    def test_envelope_rejects_a_non_ed25519_signature(self):
        with self.assertRaises(ValueError):
            build_envelope(b"{}\n", base64.b64encode(b"too short"))

    def test_manifest_rejects_path_traversal_and_bad_fingerprints(self):
        self.assertRaises(ValueError, normalize_sha256, "not-a-hash", "fixture")
        issued = datetime.now(timezone.utc)
        with self.assertRaises(ValueError):
            build_manifest(
                release_id="community-23",
                package_name="dev.anicloud.sovereign.prototype",
                version_code=23,
                version_name="test",
                apk_path="../escape.apk",
                apk_bytes=42,
                apk_sha256="a" * 64,
                apk_signer_sha256="b" * 64,
                source_commit="c" * 40,
                issued_at=issued,
                not_before=issued,
                expires_at=issued + timedelta(days=1),
                rollout_basis_points=1,
                min_current_version_code=0,
            )

    @unittest.skipUnless(__import__("shutil").which("openssl"), "OpenSSL is required")
    def test_detached_ed25519_signature_verifies_exact_manifest_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            private_key = root / "manifest-key.pem"
            public_key = root / "manifest-public.pem"
            signature_file = root / "manifest.sig"
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(private_key)],
                check=True,
                capture_output=True,
            )
            private_key.chmod(0o600)
            subprocess.run(
                ["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                check=True,
                capture_output=True,
            )
            manifest = b'{"schema":1,"release_id":"fixture"}\n'
            manifest_file = root / "manifest.json"
            tampered_file = root / "manifest-tampered.json"
            manifest_file.write_bytes(manifest)
            tampered_file.write_bytes(manifest + b" ")
            signature_file.write_bytes(base64.b64decode(sign_ed25519(manifest, private_key)))
            verified = subprocess.run(
                [
                    "openssl",
                    "pkeyutl",
                    "-verify",
                    "-pubin",
                    "-inkey",
                    str(public_key),
                    "-sigfile",
                    str(signature_file),
                    "-rawin",
                    "-in",
                    str(manifest_file),
                ],
                capture_output=True,
                check=False,
            )
            self.assertEqual(verified.returncode, 0, verified.stderr.decode())
            tampered = subprocess.run(
                [
                    "openssl",
                    "pkeyutl",
                    "-verify",
                    "-pubin",
                    "-inkey",
                    str(public_key),
                    "-sigfile",
                    str(signature_file),
                    "-rawin",
                    "-in",
                    str(tampered_file),
                ],
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(tampered.returncode, 0)


if __name__ == "__main__":
    unittest.main()
