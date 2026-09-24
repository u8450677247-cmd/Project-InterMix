"""The four-GiB service path must work without a model or full installer."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "tools" / "install_librarian_only.sh"


class LibrarianOnlyInstallTests(unittest.TestCase):
    def test_model_free_install_keeps_service_disabled_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            target = home / "librarian"
            environment = {
                **os.environ,
                "HOME": str(home),
                "INTERMIX_CONFIG_FILE": str(home / "other-config.json"),
                "INTERMIX_MEMORY_DB": str(home / "other.db"),
                "INTERMIX_ARCHIVE_DIR": str(home / "other-archive"),
            }
            command = [
                "bash", str(INSTALLER), "--project-dir", str(target),
                "--node-id", "librarian-xcover-a",
            ]
            dry_run = subprocess.run(
                [*command, "--dry-run"], env=environment, capture_output=True, text=True
            )
            self.assertEqual(dry_run.returncode, 0, dry_run.stderr)
            self.assertFalse(target.exists())

            installed = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertEqual(installed.returncode, 0, installed.stderr)
            self.assertFalse((target / "models").exists())
            self.assertFalse((target / "memory" / "sovereign.db").exists())
            self.assertFalse((target / "service").exists())
            self.assertFalse((target / "engine" / "librarian" / "__pycache__").exists())

            launcher = str(target / "bin" / "intermix-librarian")
            initialized = subprocess.run(
                [launcher, "init"], env=environment, capture_output=True, text=True
            )
            self.assertEqual(initialized.returncode, 0, initialized.stderr)
            self.assertTrue(json.loads(initialized.stdout)["initialized"])
            self.assertFalse((home / "other.db").exists())
            health = subprocess.run(
                [launcher, "health"], env=environment, capture_output=True, text=True
            )
            self.assertEqual(health.returncode, 0, health.stderr)
            self.assertEqual(json.loads(health.stdout)["database"]["quick_check"], "ok")
            checkpoint = subprocess.run(
                [launcher, "snapshot", "--reason", "offline XCover pilot"],
                env=environment, capture_output=True, text=True,
            )
            self.assertEqual(checkpoint.returncode, 0, checkpoint.stderr)
            snapshot = json.loads(checkpoint.stdout)
            recovered = target / "recovery" / "new.db"
            restored = subprocess.run(
                [
                    launcher, "restore", snapshot["database_path"],
                    snapshot["manifest_path"], str(recovered),
                    "--manifest-sha256", snapshot["manifest_sha256"],
                ],
                env=environment, capture_output=True, text=True,
            )
            self.assertEqual(restored.returncode, 0, restored.stderr)
            self.assertTrue(recovered.is_file())

            repeated = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertNotEqual(repeated.returncode, 0)
            self.assertTrue((target / "memory" / "sovereign.db").exists())

    def test_supervisor_installer_rejects_insecure_bind_before_side_effects(self):
        for flags in (["--host", "0.0.0.0"], ["--trusted-overlay"]):
            with self.subTest(flags=flags):
                attempt = subprocess.run(
                    ["bash", str(ROOT / "tools" / "install_librarian_service.sh"), *flags],
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(attempt.returncode, 0)
                self.assertIn("error:", attempt.stderr)
