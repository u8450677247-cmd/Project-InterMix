from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

import idle_reporter
import llm_controller
import temporal_refresh
import web_search
from freshness_policy import assess_freshness
from memory_store import MemoryStore
from numeric_integrity import build_numeric_ledger
from response_policy import select_response_policy
from second_brain import capture_explicit_events, retrieve_second_brain_context


class NumericIntegrityTests(unittest.TestCase):
    def test_exact_anchor_and_arithmetic_validation(self):
        ledger = build_numeric_ledger("17:17 on the clock; interpret its numerology")
        self.assertTrue(ledger.strict)
        self.assertEqual(ledger.anchors[0].raw, "17:17")
        self.assertEqual(ledger.anchors[0].digits, (1, 7, 1, 7))
        self.assertEqual(ledger.anchors[0].digit_sum, 16)
        self.assertEqual(ledger.anchors[0].digital_root, 7)
        bad = ledger.validate("At 1:1777, 1+7+7+7+7=2222.")
        self.assertFalse(bad.valid)
        good = ledger.validate("At 17:17, 1 + 7 + 1 + 7 = 16, whose digital root is 7.")
        self.assertTrue(good.valid, good.violations)

    def test_controller_hides_bad_numeric_draft_and_repairs_once(self):
        async def run() -> tuple[str, MemoryStore]:
            temp = tempfile.TemporaryDirectory()
            self.addCleanup(temp.cleanup)
            store = MemoryStore(Path(temp.name) / "brain.db")
            calls = {"count": 0}

            async def fake_stream(prompt: str):
                calls["count"] += 1
                if calls["count"] == 1:
                    answer = "The sequence is 1:1777 and 1+7+7+7+7=2222."
                    hidden = '{"memories":[{"operation":"upsert","kind":"user_fact","key":"bad","value":"1:1777","explicitly_stated":true}]}'
                else:
                    self.assertIn("NUMERIC REPAIR EPOCH", prompt)
                    answer = "At 17:17, the exact digit sum is 1 + 7 + 1 + 7 = 16, with digital root 7. ✨"
                    hidden = ""
                yield "token", answer
                yield "_model_meta", json.dumps(
                    {"backend": "fake", "return_code": 0, "hidden": hidden, "visible": answer}
                )

            with (
                patch.object(llm_controller, "STORE", store),
                patch.object(llm_controller, "_stream_model", new=fake_stream),
            ):
                events = []
                async for event in llm_controller.stream_inference(
                    "17:17 on the clock; explain its numerology"
                ):
                    events.append(event)
            visible = "".join(value for kind, value in events if kind == "token")
            self.assertEqual(calls["count"], 2)
            return visible, store

        visible, store = asyncio.run(run())
        self.assertIn("17:17", visible)
        self.assertIn("= 16", visible)
        self.assertNotIn("1:1777", visible)
        self.assertFalse(store.search_memories("1:1777"))


class SecondBrainTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = MemoryStore(Path(self.temp.name) / "brain.db")
        self.session = self.store.create_session("Second Brain")

    def tearDown(self):
        self.temp.cleanup()

    def test_explicit_private_timeline_is_query_gated(self):
        text = "I feel anxious today. At work I have a deadline Friday. I feel this UI is elegant."
        message_id = self.store.append_message(self.session["id"], "user", text)
        events = capture_explicit_events(
            self.store,
            text,
            session_id=self.session["id"],
            source_message_id=message_id,
        )
        private = [event for event in events if event.sensitive]
        self.assertEqual([event.content for event in private], ["I feel anxious today."])
        unrelated = retrieve_second_brain_context(self.store, "Write Python code for the project")
        self.assertNotIn("anxious", unrelated)
        related = retrieve_second_brain_context(self.store, "How has my mental health been?")
        self.assertIn("I feel anxious today.", related)
        self.assertIn("not as a diagnosis", related)

    def test_sensitive_capture_can_be_disabled(self):
        self.store.set_setting("sensitive_memory_mode", "off")
        message_id = self.store.append_message(self.session["id"], "user", "I feel stressed today.")
        events = capture_explicit_events(
            self.store,
            "I feel stressed today.",
            session_id=self.session["id"],
            source_message_id=message_id,
        )
        self.assertFalse(any(event.sensitive for event in events))


class FreshnessAndSearchTests(unittest.TestCase):
    def setUp(self):
        with web_search._PROVIDER_LOCK:
            web_search._PROVIDER_STATE.clear()

    def test_stable_and_evolving_topics_take_different_paths(self):
        self.assertFalse(assess_freshness("What does 17:17 mean in numerology?").search)
        self.assertFalse(assess_freshness("Calculate 17 + 17").search)
        tech = assess_freshness("How do Textual workers behave?")
        self.assertTrue(tech.search)
        self.assertEqual(tech.risk, "volatile_tech")
        self.assertIn("official documentation", tech.optimized_query)

    def test_configured_search_wave_combines_independent_sources(self):
        brave_item = {
            "title": "Android security update release notes",
            "url": "https://source-one.test/android-update",
            "snippet": "Latest Android security update information.",
            "provider": "Brave Search API",
            "published": "",
        }
        tavily_item = {
            "title": "Android update bulletin",
            "url": "https://source-two.test/android-bulletin",
            "snippet": "Android update release bulletin and security patch.",
            "provider": "Tavily Search API",
            "published": "",
        }
        with (
            patch.dict(os.environ, {"BRAVE_SEARCH_API_KEY": "x", "TAVILY_API_KEY": "y"}, clear=False),
            patch.object(web_search, "_brave", new=lambda query, limit: [brave_item]),
            patch.object(web_search, "_tavily", new=lambda query, limit: [tavily_item]),
            patch.object(web_search, "_duckduckgo_html") as duck,
        ):
            result = web_search.search_web("latest Android security update", max_results=2)
        self.assertIn("source-one.test", result)
        self.assertIn("source-two.test", result)
        duck.assert_not_called()

    def test_idle_refresh_saves_verified_evidence_and_provenance(self):
        with tempfile.TemporaryDirectory() as td:
            store = MemoryStore(Path(td) / "brain.db")
            watch_id = store.upsert_fact_watch("current Pixel update", cadence_seconds=900)
            with store.connection() as db:
                db.execute("UPDATE fact_watchlist SET next_check_at='2000-01-01T00:00:00+00:00' WHERE id=?", (watch_id,))
            evidence = """Query: current Pixel update
[1] Pixel update bulletin
Source: https://support.example.test/pixel-update
Evidence: Current Pixel update and security patch bulletin.
Provider: Brave Search API
"""
            with patch.object(temporal_refresh, "search_web", return_value=evidence):
                result = temporal_refresh.refresh_due_facts(store, limit=1)
            self.assertEqual(result["refreshed"], 1)
            self.assertIn("support.example.test", store.get_cached_web("current Pixel update"))
            events = store.list_memory_events(domain="knowledge")
            self.assertEqual(events[0]["event_type"], "evidence_refresh")


class CreationAndReportTests(unittest.TestCase):
    def test_creation_mode_uses_full_physical_window(self):
        policy = select_response_policy("/create Write a high-lore video script")
        self.assertEqual(policy.mode, "create")
        self.assertEqual(policy.input_limit + policy.output_reserve, 8000)
        self.assertGreaterEqual(policy.output_reserve, 4000)

    def test_idle_report_omits_private_text_by_default(self):
        with tempfile.TemporaryDirectory() as td:
            store = MemoryStore(Path(td) / "brain.db")
            session = store.create_session("Report Test")
            message_id = store.append_message(session["id"], "user", "I feel anxious today.")
            capture_explicit_events(
                store,
                "I feel anxious today.",
                session_id=session["id"],
                source_message_id=message_id,
            )
            previous = idle_reporter.REPORT_DIR
            idle_reporter.REPORT_DIR = Path(td) / "reports"
            try:
                result = idle_reporter.generate_idle_report(store, force=True)
            finally:
                idle_reporter.REPORT_DIR = previous
            markdown = Path(result["markdown"]).read_text(encoding="utf-8")
            self.assertNotIn("I feel anxious", markdown)
            self.assertIn("intentionally omitted", markdown)
            pdf = Path(result["pdf"]).read_bytes()
            self.assertTrue(pdf.startswith(b"%PDF-1.4"))
            self.assertTrue(pdf.endswith(b"%%EOF\n"))


class SchemaUpgradeTests(unittest.TestCase):
    def test_additive_v1_to_v2_upgrade_preserves_existing_rows(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "brain.db"
            original = MemoryStore(path)
            session = original.create_session("Existing Clarity Session")
            message_id = original.append_message(
                session["id"],
                "user",
                "Preserve this message through the additive upgrade.",
            )
            with original.connection() as db:
                for trigger in ("memory_events_ai", "memory_events_ad", "memory_events_au"):
                    db.execute(f"DROP TRIGGER IF EXISTS {trigger}")
                for table in (
                    "memory_events_fts",
                    "memory_relations",
                    "memory_entities",
                    "fact_watchlist",
                    "report_runs",
                    "memory_events",
                ):
                    db.execute(f"DROP TABLE IF EXISTS {table}")
                db.execute(
                    "UPDATE schema_meta SET value='1' WHERE key='schema_version'"
                )

            upgraded = MemoryStore(path)
            messages = upgraded.get_messages(session["id"], limit=10)
            self.assertEqual([item["id"] for item in messages], [message_id])
            self.assertEqual(upgraded.status()["schema_version"], 2)
            with upgraded.connection(readonly=True) as db:
                tables = {
                    row[0]
                    for row in db.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    ).fetchall()
                }
            self.assertTrue(
                {"memory_events", "memory_entities", "memory_relations", "fact_watchlist", "report_runs"}
                <= tables
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
