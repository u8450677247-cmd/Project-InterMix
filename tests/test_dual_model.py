from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from memory_store import MemoryStore
from memory_protocol import OPEN_MARKER
from model_router import (
    LIBRARIAN_ROLE,
    REASONING_ROLE,
    build_librarian_handoff_prompt,
    select_model_route,
)
from persona_manager import (
    capture_explicit_persona,
    current_persona,
    persona_history,
    undo_persona,
)
from resident_engine import EngineProfile, ResidentEngineError, ResidentEngineManager
from sanctuary import request_sanctuary, sanctuary_status

import llm_controller


class ModelRouterTests(unittest.TestCase):
    def test_conversation_prefers_librarian_and_missing_asset_falls_back(self):
        routed = select_model_route(
            "How was your day?",
            librarian_available=True,
        )
        self.assertEqual(routed.effective_role, LIBRARIAN_ROLE)
        self.assertFalse(routed.handoff_required)

        fallback = select_model_route(
            "How was your day?",
            librarian_available=False,
        )
        self.assertEqual(fallback.requested_role, LIBRARIAN_ROLE)
        self.assertEqual(fallback.effective_role, REASONING_ROLE)
        self.assertIn("unavailable", fallback.reason)

    def test_difficult_current_and_agent_work_use_reasoning_handoff(self):
        for kwargs in (
            {"grounding_required": True},
            {"agent_mode": True},
            {"creation_mode": True},
        ):
            routed = select_model_route(
                "Please evaluate this architecture in depth.",
                librarian_available=True,
                **kwargs,
            )
            self.assertEqual(routed.effective_role, REASONING_ROLE)
            self.assertTrue(routed.handoff_required)

    def test_handoff_is_bounded_and_non_authoritative(self):
        prompt = build_librarian_handoff_prompt(
            "Compare these options.",
            [{"speaker": "Operator", "content": "Earlier context " * 1000}],
        )
        self.assertLess(len(prompt), 9000)
        self.assertIn("Do not answer the user", prompt)
        self.assertIn("Do not invent facts", prompt)
        self.assertIn("REASONING FOCUS", prompt)
        self.assertIn(OPEN_MARKER, prompt)


class ControllerHandoffTests(unittest.TestCase):
    def test_e2b_owns_memory_delta_before_e4b_reasoning(self):
        async def run():
            temporary = tempfile.TemporaryDirectory()
            self.addCleanup(temporary.cleanup)
            store = MemoryStore(Path(temporary.name) / "memory.db")
            calls = []

            async def fake_model(
                prompt: str,
                model_role: str = REASONING_ROLE,
                max_output_tokens: int | None = None,
            ):
                calls.append((model_role, prompt))
                if model_role == LIBRARIAN_ROLE:
                    visible = (
                        "INTENT\nCompare designs.\nRELEVANT CONTEXT\nLocal project.\n"
                        "UNCERTAINTIES\nNone.\nREASONING FOCUS\nTradeoffs."
                    )
                    hidden = json.dumps(
                        {
                            "memories": [
                                {
                                    "operation": "upsert",
                                    "kind": "user_goal",
                                    "key": "design_goal",
                                    "value": "User wants the architecture compared in depth.",
                                    "explicitly_stated": True,
                                }
                            ]
                        }
                    )
                else:
                    visible = "The architecture comparison is ready."
                    hidden = ""
                yield "token", visible
                yield "_model_meta", json.dumps(
                    {
                        "backend": "fake",
                        "return_code": 0,
                        "hidden": hidden,
                        "visible": visible,
                        "model_role": model_role,
                    }
                )

            config = SimpleNamespace(
                dual_model_enabled=True,
                user_name="Operator",
                assistant_name="Intermix Core",
            )
            with (
                patch.object(llm_controller, "STORE", store),
                patch.object(llm_controller, "CONFIG", config),
                patch.object(
                    llm_controller,
                    "LIBRARIAN_PROFILE",
                    SimpleNamespace(available=True),
                ),
                patch.object(llm_controller, "_stream_model", new=fake_model),
            ):
                events = []
                async for event in llm_controller.stream_inference(
                    "Analyze this architecture in depth and compare its tradeoffs."
                ):
                    events.append(event)
            return calls, events, store

        calls, events, store = asyncio.run(run())
        self.assertEqual([role for role, _ in calls], [LIBRARIAN_ROLE, REASONING_ROLE])
        self.assertIn(OPEN_MARKER, calls[0][1])
        self.assertNotIn(OPEN_MARKER, calls[1][1])
        visible = "".join(value for kind, value in events if kind == "token")
        self.assertEqual(visible, "The architecture comparison is ready.")
        memories = store.search_memories("architecture compared")
        self.assertEqual(len(memories), 1)
        self.assertEqual(memories[0]["memory_key"], "design_goal")


class ResidentModelSwitchTests(unittest.TestCase):
    def test_switch_closes_previous_engine_before_loading_next(self):
        state = {
            "opened": [],
            "open_count": 0,
            "max_open": 0,
        }

        class FakeBackend:
            @staticmethod
            def GPU():
                return "fake-gpu"

        class FakeConversation:
            def __init__(self, label):
                self.label = label

            def send_message_async(self, prompt):
                yield {"content": [{"type": "text", "text": f"{self.label}:{prompt}"}]}

            def close(self):
                return None

        class FakeEngine:
            def __init__(self, model_path, **kwargs):
                self.label = Path(model_path).stem
                state["opened"].append(self.label)
                state["open_count"] += 1
                state["max_open"] = max(state["max_open"], state["open_count"])

            def create_conversation(self):
                return FakeConversation(self.label)

            def close(self):
                state["open_count"] -= 1

        class FakeModule:
            Backend = FakeBackend
            Engine = FakeEngine

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reasoning = root / "reasoning.litertlm"
            librarian = root / "librarian.litertlm"
            reasoning.write_bytes(b"reasoning")
            librarian.write_bytes(b"librarian")
            profiles = {
                REASONING_ROLE: EngineProfile(
                    REASONING_ROLE, "E4B", str(reasoning), str(root), 8000
                ),
                LIBRARIAN_ROLE: EngineProfile(
                    LIBRARIAN_ROLE, "E2B", str(librarian), str(root), 8000
                ),
            }
            manager = ResidentEngineManager(
                str(reasoning),
                cache_dir=str(root),
                profiles=profiles,
                request_timeout=5,
                module_loader=lambda: FakeModule,
            )

            async def run_sequence():
                output = []
                async for event in manager.stream("chat", profile=LIBRARIAN_ROLE):
                    output.append(event)
                async for event in manager.stream("reason", profile=REASONING_ROLE):
                    output.append(event)
                return output

            events = asyncio.run(run_sequence())
            status = manager.status()
            manager.shutdown(wait=True)

        self.assertEqual(state["opened"], ["librarian", "reasoning"])
        self.assertEqual(state["max_open"], 1)
        self.assertEqual(state["open_count"], 0)
        self.assertEqual(status["active_profile"], REASONING_ROLE)
        self.assertIn(("token", "librarian:chat"), events)
        self.assertIn(("token", "reasoning:reason"), events)

    def test_librarian_failures_do_not_disable_reasoning_profile(self):
        class FakeBackend:
            @staticmethod
            def GPU():
                return "fake-gpu"

        class FakeConversation:
            def send_message_async(self, prompt):
                yield {"content": [{"type": "text", "text": "reasoned"}]}

        class FakeEngine:
            def __init__(self, model_path, **kwargs):
                if "librarian" in model_path:
                    raise RuntimeError("bad optional asset")

            def create_conversation(self):
                return FakeConversation()

            def close(self):
                return None

        class FakeModule:
            Backend = FakeBackend
            Engine = FakeEngine

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reasoning = root / "reasoning.litertlm"
            librarian = root / "librarian.litertlm"
            reasoning.write_bytes(b"reasoning")
            librarian.write_bytes(b"librarian")
            profiles = {
                REASONING_ROLE: EngineProfile(
                    REASONING_ROLE, "E4B", str(reasoning), str(root), 8000
                ),
                LIBRARIAN_ROLE: EngineProfile(
                    LIBRARIAN_ROLE, "E2B", str(librarian), str(root), 8000
                ),
            }
            manager = ResidentEngineManager(
                str(reasoning),
                cache_dir=str(root),
                profiles=profiles,
                request_timeout=5,
                module_loader=lambda: FakeModule,
            )

            async def consume(role):
                output = []
                async for event in manager.stream("prompt", profile=role):
                    output.append(event)
                return output

            for _ in range(2):
                with self.assertRaises(ResidentEngineError):
                    asyncio.run(consume(LIBRARIAN_ROLE))
            self.assertFalse(manager.can_attempt(profile=LIBRARIAN_ROLE))
            self.assertTrue(manager.can_attempt(profile=REASONING_ROLE))
            reasoning_events = asyncio.run(consume(REASONING_ROLE))
            manager.shutdown(wait=True)

        self.assertIn(("token", "reasoned"), reasoning_events)


class PersonaAndSanctuaryTests(unittest.TestCase):
    def test_explicit_persona_change_has_history_and_undo(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = MemoryStore(Path(temporary) / "memory.db")
            session = store.create_session("Persona")
            message_id = store.append_message(
                session["id"], "user", "Please answer concisely."
            )
            revisions = capture_explicit_persona(
                store,
                "Please answer concisely.",
                source_message_id=message_id,
            )
            self.assertEqual(len(revisions), 1)
            self.assertEqual(
                current_persona(store)["communication_preferences"],
                ["Please answer concisely."],
            )
            self.assertEqual(len(persona_history(store)), 1)
            undone = undo_persona(store)
            self.assertIsNotNone(undone)
            self.assertEqual(current_persona(store)["communication_preferences"], [])
            self.assertTrue(persona_history(store)[0].undone)

    def test_persona_does_not_infer_traits_from_ordinary_disclosure(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = MemoryStore(Path(temporary) / "memory.db")
            session = store.create_session("Persona")
            message_id = store.append_message(
                session["id"], "user", "I felt overwhelmed today."
            )
            revisions = capture_explicit_persona(
                store,
                "I felt overwhelmed today.",
                source_message_id=message_id,
            )
            self.assertEqual(revisions, [])
            self.assertEqual(current_persona(store)["communication_preferences"], [])

    def test_termux_sanctuary_fails_closed_without_encryption(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = MemoryStore(Path(temporary) / "memory.db")
            status = request_sanctuary(store, enabled=True)
            self.assertFalse(status["available"])
            self.assertFalse(status["active"])
            self.assertFalse(status["raw_conversation_capture"])
            self.assertEqual(status["encryption_backend"], "none")
            self.assertEqual(store.get_setting("sanctuary_mode"), "off")
            self.assertEqual(status, sanctuary_status(store))


if __name__ == "__main__":
    unittest.main(verbosity=2)
