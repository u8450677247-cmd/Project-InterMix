from __future__ import annotations

import json
import hashlib
import stat
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

import provider_vault
from librarian.store import ContinuityStore
from run_librarian_daily_flight import run_daily_flight


class LibrarianDailyFlightTests(unittest.TestCase):
    def test_daily_flight_is_live_safe_idempotent_and_share_safe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "live" / "sovereign.db"
            store = ContinuityStore(database)
            store.register_node("cortex-primary", "cortex", "CORTEX-PRIMARY", platform="test")
            store.register_node("archive-ds215j", "archive", "DS215j", platform="test")
            vault = root / "private" / "providers.env"
            provider_vault._write_values(
                {"BRAVE_SEARCH_API_KEY": "secret-flight-value"},
                vault,
            )

            arguments = {
                "database_path": database,
                "report_directory": root / "reports",
                "snapshot_directory": root / "snapshots",
                "provider_vault_path": vault,
                "archive_root": root / "archive",
                "snapshot_live": True,
                "flight_day": "2026-09-19",
            }
            first, first_path = run_daily_flight(**arguments)
            second, second_path = run_daily_flight(**arguments)

            self.assertTrue(first["passed"])
            self.assertTrue(first["shadow_flight"]["passed"])
            self.assertEqual(first["shadow_flight"]["ingest"], "committed")
            self.assertEqual(first["shadow_flight"]["duplicate_replay"], "duplicate")
            self.assertTrue(first["shadow_flight"]["context_contains_probe_evidence"])
            self.assertEqual(first["grounding"]["configured_count"], 1)
            self.assertEqual(first["grounding"]["live_queries_executed"], 0)
            self.assertEqual(
                first["live_authority"]["snapshot"]["snapshot_id"],
                second["live_authority"]["snapshot"]["snapshot_id"],
            )
            self.assertNotEqual(first_path, second_path)
            self.assertEqual(ContinuityStore(database, initialize=False).counts()["snapshots"], 1)
            self.assertEqual(stat.S_IMODE(first_path.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(first_path.parent.stat().st_mode), 0o700)
            self.assertNotIn("secret-flight-value", first_path.read_text(encoding="utf-8"))
            self.assertTrue(json.loads(second_path.read_text(encoding="utf-8"))["passed"])
            digest = hashlib.sha256(first_path.read_bytes()).hexdigest()
            checksum = first_path.with_name(first_path.name + ".sha256")
            self.assertEqual(stat.S_IMODE(checksum.stat().st_mode), 0o600)
            self.assertEqual(checksum.read_text(encoding="ascii"), f"{digest}  {first_path.name}\n")

    def test_opt_in_canary_records_metrics_without_result_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "live.db"
            ContinuityStore(database)
            canary = {
                "status": "passed",
                "canary_profile": "python-docs-v1",
                "requests_used": 2,
                "providers_attempted": ["brave", "tavily"],
                "provider_errors": [],
                "result_count": 4,
                "relevant_result_count": 3,
                "distinct_source_count": 2,
                "elapsed_seconds": 0.25,
            }
            with patch(
                "run_librarian_daily_flight.run_provider_canary",
                return_value=canary,
            ):
                report, report_path = run_daily_flight(
                    database_path=database,
                    report_directory=root / "reports",
                    snapshot_directory=root / "snapshots",
                    provider_vault_path=root / "missing.env",
                    snapshot_live=False,
                    provider_canary=True,
                    canary_providers=2,
                    flight_day="2026-09-20",
                )

            self.assertTrue(report["passed"])
            self.assertEqual(report["grounding"]["live_queries_executed"], 2)
            self.assertEqual(report["grounding"]["canary"], canary)
            saved = report_path.read_text(encoding="utf-8")
            self.assertNotIn("snippet", saved)
            self.assertNotIn("url", saved)


if __name__ == "__main__":
    unittest.main(verbosity=2)
