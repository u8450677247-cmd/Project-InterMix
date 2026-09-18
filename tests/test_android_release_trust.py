from __future__ import annotations

import base64
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from verify_android_release_trust import (  # noqa: E402
    ED25519_SPKI_PREFIX,
    main,
    validate_release_trust,
)


class AndroidReleaseTrustTests(unittest.TestCase):
    def setUp(self) -> None:
        public_der = ED25519_SPKI_PREFIX + bytes(range(32))
        self.public_key = base64.b64encode(public_der).decode("ascii")
        self.signer = "1cf20cb2114d38a7951161b92c9770be0c4d096595759c28f624f426ac620d79"

    def test_valid_public_origin_key_and_signer_produce_bounded_provenance(self):
        summary = validate_release_trust(
            "https://updates.example.test/intermix/",
            self.public_key,
            self.signer,
        )
        self.assertEqual(summary.schema, 1)
        self.assertEqual(summary.release_origin_url, "https://updates.example.test/intermix/")
        self.assertRegex(summary.manifest_public_key_sha256, r"^[0-9a-f]{64}$")
        self.assertEqual(summary.apk_signer_sha256, self.signer)

    def test_non_public_or_ambiguous_origins_are_rejected(self):
        rejected = (
            "http://updates.example.test/intermix/",
            "https://localhost/intermix/",
            "https://nas.local/intermix/",
            "https://192.0.2.10/intermix/",
            "https://[2001:db8::1]/intermix/",
            "https://updates.example.test:8443/intermix/",
            "https://operator@updates.example.test/intermix/",
            "https://updates.example.test/intermix%2fescape/",
            "https://updates.example.test/intermix/?device=1",
            "https://updates.example.test/intermix/#fragment",
        )
        for origin in rejected:
            with self.subTest(origin=origin):
                with self.assertRaises(ValueError):
                    validate_release_trust(origin, self.public_key, self.signer)

    def test_key_and_certificate_pins_must_be_canonical(self):
        invalid_keys = (
            "",
            "not-base64",
            base64.b64encode(b"not-an-ed25519-key").decode("ascii"),
            self.public_key.rstrip("="),
        )
        for key in invalid_keys:
            with self.subTest(key=key):
                with self.assertRaises(ValueError):
                    validate_release_trust(
                        "https://updates.example.test/intermix/",
                        key,
                        self.signer,
                    )
        for signer in ("", "a" * 63, "A" * 64, "aa:" * 31 + "aa"):
            with self.subTest(signer=signer):
                with self.assertRaises(ValueError):
                    validate_release_trust(
                        "https://updates.example.test/intermix/",
                        self.public_key,
                        signer,
                    )

    def test_cli_writes_only_public_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            summary_path = Path(temporary) / "release-trust.json"
            with contextlib.redirect_stdout(io.StringIO()):
                result = main(
                    [
                        "--origin",
                        "https://updates.example.test/intermix/",
                        "--manifest-public-key-b64",
                        self.public_key,
                        "--apk-cert-sha256",
                        self.signer,
                        "--summary",
                        str(summary_path),
                    ]
                )
            self.assertEqual(result, 0)
            payload = json.loads(summary_path.read_text(encoding="utf-8"))
            self.assertEqual(set(payload), {
                "schema",
                "release_origin_url",
                "manifest_public_key_sha256",
                "apk_signer_sha256",
            })
            self.assertNotIn(self.public_key, summary_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
