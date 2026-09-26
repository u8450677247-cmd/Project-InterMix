from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import asyncio
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from memory_migrate import apply_migration, load_txt, migration_report
from memory_protocol import (
    CLOSE_MARKER,
    OPEN_MARKER,
    HiddenMemoryFilter,
    apply_memory_payload,
    parse_memory_payload,
)
from memory_store import MemoryStore
from prompt_builder import INPUT_LIMIT_TOKENS, build_prompt
from resident_engine import ResidentEngineError, ResidentEngineManager


class MemoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self.temp.name) / "sovereign.db")
        self.store = MemoryStore(self.db_path)
        self.session = self.store.create_session("Test Session")

    def tearDown(self):
        self.temp.cleanup()

    def test_schema_and_fts(self):
        user_id = self.store.append_message(
            self.session["id"], "user", "I build unusual mobile intelligence systems."
        )
        self.store.append_message(
            self.session["id"], "assistant", "That architecture is locally sovereign."
        )
        memory_id = self.store.upsert_memory(
            kind="user_preference",
            memory_key="architecture_style",
            value="Prefers modular local-first Python architecture",
            source_message_id=user_id,
            confidence=0.9,
            salience=0.8,
            explicitly_stated=True,
        )
        self.assertGreater(memory_id, 0)
        self.assertTrue(self.store.search_messages("mobile intelligence"))
        memories = self.store.search_memories("Python architecture")
        self.assertEqual(memories[0]["id"], memory_id)

    def test_memory_conflict_preserves_revision(self):
        message_id = self.store.append_message(self.session["id"], "user", "I prefer cyan.")
        old_id = self.store.upsert_memory(
            kind="user_preference",
            memory_key="accent_color",
            value="Cyan",
            source_message_id=message_id,
            explicitly_stated=True,
        )
        new_id = self.store.upsert_memory(
            kind="user_preference",
            memory_key="accent_color",
            value="Cyan and purple",
            source_message_id=message_id,
            explicitly_stated=True,
        )
        self.assertNotEqual(old_id, new_id)
        with self.store.connection(readonly=True) as db:
            old = db.execute("SELECT active FROM memories WHERE id=?", (old_id,)).fetchone()
            revisions = db.execute(
                "SELECT COUNT(*) FROM memory_revisions WHERE memory_id=?", (new_id,)
            ).fetchone()[0]
        self.assertEqual(old["active"], 0)
        self.assertEqual(revisions, 1)

    def test_hidden_protocol_across_chunk_boundaries(self):
        payload = (
            '{"memories":[{"operation":"upsert","kind":"user_preference",'
            '"key":"color","value":"cyan","explicitly_stated":true}],'
            '"session":{"title":"Colors","summary":"Discussing colors."}}'
        )
        stream = "Visible answer." + OPEN_MARKER + payload + CLOSE_MARKER
        filter_ = HiddenMemoryFilter()
        visible = []
        for index in range(0, len(stream), 3):
            visible.append(filter_.feed(stream[index:index + 3]))
        final = filter_.finish()
        visible.append(final.visible)
        self.assertEqual("".join(visible), "Visible answer.")
        parsed = parse_memory_payload(final.hidden)
        self.assertEqual(parsed["memories"][0]["value"], "cyan")

    def test_sensitive_inference_is_rejected(self):
        message_id = self.store.append_message(self.session["id"], "user", "I had a difficult day.")
        payload = {
            "memories": [
                {
                    "operation": "upsert",
                    "kind": "health_context",
                    "key": "diagnosis",
                    "value": "An inferred diagnosis",
                    "explicitly_stated": False,
                    "sensitive": True,
                }
            ]
        }
        result = apply_memory_payload(
            self.store,
            payload,
            session_id=self.session["id"],
            source_message_id=message_id,
        )
        self.assertEqual(result["stored"], 0)
        self.assertEqual(self.store.status()["memories"], 0)

    def test_partial_session_delta_preserves_unspecified_fields(self):
        self.store.update_session(
            self.session["id"],
            summary="Existing durable checkpoint.",
            current_task="Preserve the architecture.",
            decisions=["Use SQLite FTS5."],
            active_project="Project Intermix",
        )
        message_id = self.store.append_message(
            self.session["id"], "user", "Keep the tone focused."
        )
        result = apply_memory_payload(
            self.store,
            {"session": {"tone_state": "focused"}},
            session_id=self.session["id"],
            source_message_id=message_id,
        )
        updated = self.store.get_session(self.session["id"])
        self.assertEqual(result["session_updated"], 1)
        self.assertEqual(updated["tone_state"], "focused")
        self.assertEqual(updated["summary"], "Existing durable checkpoint.")
        self.assertEqual(updated["current_task"], "Preserve the architecture.")
        self.assertEqual(updated["decisions"], ["Use SQLite FTS5."])
        self.assertEqual(updated["active_project"], "Project Intermix")

    def test_hard_fact_requires_matching_web_provenance(self):
        message_id = self.store.append_message(self.session["id"], "user", "Check the current release.")
        payload = {
            "memories": [
                {
                    "operation": "upsert",
                    "kind": "hard_fact",
                    "key": "current_release",
                    "value": "The current release is 7.2.",
                    "source_urls": ["https://example.test/releases"],
                    "freshness_days": 2,
                }
            ]
        }
        rejected = apply_memory_payload(
            self.store,
            payload,
            session_id=self.session["id"],
            source_message_id=message_id,
        )
        self.assertEqual(rejected["stored"], 0)
        accepted = apply_memory_payload(
            self.store,
            payload,
            session_id=self.session["id"],
            source_message_id=message_id,
            grounded_evidence="Source: https://example.test/releases\nEvidence: version 7.2",
        )
        self.assertEqual(accepted["stored"], 1)
        memory = self.store.search_memories("current release")[0]
        self.assertEqual(memory["kind"], "hard_fact")
        self.assertIsNotNone(memory["expires_at"])

    def test_prompt_never_exceeds_input_budget(self):
        for index in range(90):
            role = "user" if index % 2 == 0 else "assistant"
            self.store.append_message(
                self.session["id"],
                role,
                f"Historical turn {index}: " + ("context material " * 80),
            )
        current_id = self.store.append_message(
            self.session["id"], "user", "Continue the mobile memory architecture."
        )
        prompt = build_prompt(
            self.store,
            session_id=self.session["id"],
            current_user_message_id=current_id,
            user_text="Continue the mobile memory architecture.",
            web_data="Evidence " * 2000,
            agent_instructions="Tools " * 1000,
        )
        self.assertLessEqual(prompt.estimated_tokens, INPUT_LIMIT_TOKENS)
        self.assertTrue(prompt.compacted)

    def test_forced_grounding_contract_is_mandatory_and_budgeted(self):
        current_id = self.store.append_message(
            self.session["id"], "user", "What is the current release?"
        )
        evidence = """Query: current release
[1] Current release notes
Source: https://example.test/releases
Evidence: Version 7.2 is the current release.
Provider: DuckDuckGo HTML
"""
        prompt = build_prompt(
            self.store,
            session_id=self.session["id"],
            current_user_message_id=current_id,
            user_text="What is the current release?",
            web_data=evidence,
            grounding_required=True,
        )
        self.assertIn("[FORCED GROUNDING CONTRACT]", prompt.text)
        self.assertIn("uncited answer will be discarded", prompt.text)
        self.assertLessEqual(prompt.estimated_tokens, INPUT_LIMIT_TOKENS)

    def test_reasoning_handoff_can_delegate_memory_protocol(self):
        current_id = self.store.append_message(
            self.session["id"], "user", "Please compare the two designs."
        )
        prompt = build_prompt(
            self.store,
            session_id=self.session["id"],
            current_user_message_id=current_id,
            user_text="Please compare the two designs.",
            include_memory_protocol=False,
        )
        self.assertNotIn("<MEMORY_UPDATE>", prompt.text)
        self.assertNotIn("protocol", prompt.report["blocks"])

    def test_prompt_telemetry_is_scoped_to_the_active_session(self):
        self.store.record_prompt_report(
            self.session["id"],
            2877,
            True,
            {"input_limit": 6800, "response_mode": "normal"},
        )
        fresh = self.store.create_session("Fresh Session")
        self.assertIsNone(self.store.latest_prompt_report(fresh["id"]))
        previous = self.store.latest_prompt_report(self.session["id"])
        self.assertEqual(previous["input_tokens"], 2877)


class MigrationTests(unittest.TestCase):
    def test_txt_and_legacy_sqlite_import_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            db_path = temp_path / "sovereign.db"
            convo = temp_path / "convo.txt"
            convo.write_text(
                "\n[2026-08-20 10:00:00] Operator:\n   Remember the cyan cockpit.\n"
                "\n[2026-08-20 10:00:02] Intermix Core:\n   The cyan cockpit is remembered.\n",
                encoding="utf-8",
            )
            db = sqlite3.connect(db_path)
            db.execute("CREATE TABLE memory (time TEXT, role TEXT, text TEXT)")
            db.execute(
                "INSERT INTO memory VALUES(?,?,?)",
                ("10:00:03", "User", "The SQLite matrix contains a newer message."),
            )
            db.commit()
            db.close()

            dry = migration_report(str(db_path), str(convo))
            self.assertEqual(dry["importable"], 3)
            first = apply_migration(str(db_path), str(convo))
            second = apply_migration(str(db_path), str(convo))
            self.assertEqual(first["imported"], 3)
            self.assertEqual(second["imported"], 0)
            store = MemoryStore(db_path)
            self.assertEqual(store.status()["messages"], 3)


class ResidentEngineTests(unittest.TestCase):
    def test_engine_is_reused_across_stateless_conversations(self):
        counts = {"engines": 0, "conversations": 0, "closed": 0}

        class FakeBackend:
            @staticmethod
            def GPU():
                return "fake-gpu"

        class FakeConversation:
            def send_message_async(self, prompt):
                yield {"content": [{"type": "text", "text": f"reply:{prompt}"}]}

            def close(self):
                counts["closed"] += 1

        class FakeEngine:
            def __init__(self, *args, **kwargs):
                counts["engines"] += 1

            def create_conversation(self):
                counts["conversations"] += 1
                return FakeConversation()

            def close(self):
                counts["closed"] += 1

        class FakeModule:
            Backend = FakeBackend
            Engine = FakeEngine

        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "model.litertlm"
            model.write_bytes(b"test")
            manager = ResidentEngineManager(
                str(model),
                cache_dir=temp,
                context_tokens=8000,
                idle_seconds=60,
                request_timeout=5,
                module_loader=lambda: FakeModule,
            )

            async def collect(prompt):
                events = []
                async for event in manager.stream(prompt):
                    events.append(event)
                return events

            first = asyncio.run(collect("one"))
            second = asyncio.run(collect("two"))
            manager.shutdown(wait=True)

        self.assertEqual(counts["engines"], 1)
        self.assertEqual(counts["conversations"], 2)
        self.assertIn(("token", "reply:one"), first)
        self.assertIn(("token", "reply:two"), second)

    def test_resident_initialization_error_is_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "model.litertlm"
            model.write_bytes(b"test")

            def fail_loader():
                raise RuntimeError("native backend unavailable")

            manager = ResidentEngineManager(
                str(model),
                cache_dir=temp,
                request_timeout=5,
                module_loader=fail_loader,
            )

            async def consume():
                async for _ in manager.stream("hello"):
                    pass

            with self.assertRaises(ResidentEngineError):
                asyncio.run(consume())
            manager.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
