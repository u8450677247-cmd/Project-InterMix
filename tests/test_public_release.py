from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
TOOLS = ROOT / "tools"
sys.path.insert(0, str(ENGINE))
sys.path.insert(0, str(TOOLS))

from build_release import build_release
from public_release_audit import REQUIRED_EXECUTABLES, REQUIRED_FILES, audit_repository
from runtime_config import load_runtime_config


class RuntimeConfigTests(unittest.TestCase):
    def test_runtime_configuration_is_portable_and_bounded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "config.json"
            config.write_text(
                json.dumps(
                    {
                        "project_name": "Intermix\nTest",
                        "user_name": "Pilot",
                        "assistant_name": "Core",
                        "project_dir": str(root / "project"),
                        "context_tokens": 999999,
                        "librarian_context_tokens": 999999,
                        "dual_model_enabled": False,
                    }
                ),
                encoding="utf-8",
            )
            loaded = load_runtime_config(config)
        self.assertEqual(loaded.project_name, "Intermix Test")
        self.assertEqual(loaded.user_name, "Pilot")
        self.assertEqual(loaded.assistant_name, "Core")
        self.assertEqual(loaded.context_tokens, 32768)
        self.assertEqual(loaded.librarian_context_tokens, 32768)
        self.assertFalse(loaded.dual_model_enabled)
        self.assertTrue(str(loaded.librarian_model_path).endswith("gemma-4-E2B-it.litertlm"))
        self.assertTrue(str(loaded.project_dir).endswith("project"))

    def test_environment_override_wins_without_accepting_provider_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "missing.json"
            with patch.dict(
                os.environ,
                {
                    "INTERMIX_USER_NAME": "Environment Pilot",
                    "INTERMIX_DUAL_MODEL": "off",
                    "INTERMIX_LIBRARIAN_CONTEXT_TOKENS": "4096",
                },
            ):
                loaded = load_runtime_config(config)
        self.assertEqual(loaded.user_name, "Environment Pilot")
        self.assertFalse(loaded.dual_model_enabled)
        self.assertEqual(loaded.librarian_context_tokens, 4096)
        self.assertNotIn("provider", loaded.public_status())


class PublicReleaseAuditTests(unittest.TestCase):
    @staticmethod
    def _minimal_tree(root: Path) -> None:
        for relative in REQUIRED_FILES:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("safe public fixture\n", encoding="utf-8")
        (root / "VERSION").write_text("1.2.3-alpha.1\n", encoding="utf-8")
        for relative in REQUIRED_EXECUTABLES:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists():
                path.write_text("#!/usr/bin/env sh\n", encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def test_audit_accepts_minimal_safe_source_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._minimal_tree(root)
            self.assertEqual(audit_repository(root), [])

    def test_audit_rejects_private_artifacts_and_secret_shapes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._minimal_tree(root)
            private = root / "memory" / "brain.db"
            private.parent.mkdir()
            private.write_bytes(b"SQLite fixture")
            token = "gh" + "p_" + ("A" * 40)
            (root / "accident.txt").write_text(token, encoding="utf-8")
            errors = audit_repository(root)
        joined = "\n".join(errors)
        self.assertIn("private/generated directory", joined)
        self.assertIn("private/generated file type", joined)
        self.assertIn("possible GitHub token", joined)

    def test_release_archive_is_deterministic_and_manifested(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "source"
            first = base / "first"
            second = base / "second"
            root.mkdir()
            self._minimal_tree(root)
            archive_a, checksum_a, manifest_a = build_release(root, first)
            archive_b, checksum_b, manifest_b = build_release(root, second)
            self.assertEqual(archive_a.read_bytes(), archive_b.read_bytes())
            self.assertEqual(checksum_a.read_text(), checksum_b.read_text())
            self.assertEqual(manifest_a, manifest_b)
            with __import__("zipfile").ZipFile(archive_a) as bundle:
                names = bundle.namelist()
            manifest_names = [
                name for name in names if name.endswith("/RELEASE_MANIFEST.json")
            ]
            self.assertEqual(len(manifest_names), 1)


class InstallerContractTests(unittest.TestCase):
    def test_installer_is_valid_bash_and_exposes_safe_dry_run(self):
        result = subprocess.run(
            ["bash", "-n", str(ROOT / "install.sh")],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        help_result = subprocess.run(
            ["bash", str(ROOT / "install.sh"), "--help"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(help_result.returncode, 0, help_result.stderr)
        self.assertIn("--dry-run", help_result.stdout)
        self.assertIn("--librarian-model", help_result.stdout)
        self.assertIn("--dual-model", help_result.stdout)
        self.assertIn("never downloads model weights", help_result.stdout)
        installer = (ROOT / "install.sh").read_text(encoding="utf-8")
        self.assertIn(
            'PYTHONPATH="$BUNDLE_DIR/engine" PYTHONDONTWRITEBYTECODE=1',
            installer,
        )


if __name__ == "__main__":
    unittest.main()
