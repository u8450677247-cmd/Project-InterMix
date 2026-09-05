from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from generation_guard import GenerationRuntime, RepetitionWatchdog
from memory_store import MemoryStore
from resident_engine import ResidentEngineManager

import llm_controller


class RepetitionWatchdogTests(unittest.TestCase):
    def test_single_word_runaway_is_detected(self):
        guard = RepetitionWatchdog()
        decision = guard.feed("the " * 30)
        self.assertTrue(decision.triggered)
        self.assertEqual(decision.reason, "repetition_loop")
        self.assertEqual(decision.repeated_text, "the")

    def test_repeated_phrase_and_split_control_token_are_detected(self):
        phrase_guard = RepetitionWatchdog()
        phrase = "the engine is ready " * 8
        self.assertTrue(phrase_guard.feed(phrase).triggered)

        token_guard = RepetitionWatchdog()
        self.assertFalse(token_guard.feed("Visible response <|assis").triggered)
        decision = token_guard.feed("tant|> hidden transport text")
        self.assertTrue(decision.triggered)
        self.assertEqual(decision.reason, "control_token_leak")

        memory_guard = RepetitionWatchdog()
        decision = memory_guard.feed("Useful answer. [MEMORY DELTA: N1='50']")
        self.assertTrue(decision.triggered)
        self.assertEqual(decision.reason, "memory_protocol_leak")

    def test_duplicated_long_block_is_detected(self):
        guard = RepetitionWatchdog()
        block = " ".join(f"word{index}" for index in range(20))
        decision = guard.feed((block + " ") * 3)
        self.assertTrue(decision.triggered)
        self.assertEqual(decision.reason, "repetition_loop")

    def test_invalid_stream_character_is_detected(self):
        guard = RepetitionWatchdog()
        decision = guard.feed("Readable text followed by a broken byte: \ufffd")
        self.assertTrue(decision.triggered)
        self.assertEqual(decision.reason, "stream_artifact")

    def test_normal_repetition_remains_below_conservative_threshold(self):
        guard = RepetitionWatchdog()
        prose = (
            "Test one path, test another path, and test the final path. "
            "The result should preserve deliberate emphasis without looping."
        )
        self.assertFalse(guard.feed(prose).triggered)

    def test_runtime_cancel_has_explicit_incomplete_payload(self):
        runtime = GenerationRuntime()
        request_id = runtime.begin()
        self.assertTrue(runtime.cancel("user", "Stopped from the cockpit."))
        payload = runtime.stop_payload()
        self.assertEqual(payload["request_id"], request_id)
        self.assertEqual(payload["reason"], "user")
        self.assertTrue(payload["incomplete"])
        runtime.finish()
        self.assertEqual(runtime.status()["state"], "stopped")


class ResidentCancellationTests(unittest.TestCase):
    def test_cancel_stops_stream_discards_engine_and_honors_output_limit(self):
        state = {
            "closed": 0,
            "engine_closed": 0,
            "max_output_tokens": None,
            "chunks": 0,
        }

        class FakeBackend:
            @staticmethod
            def GPU():
                return "fake-gpu"

        class FakeConversation:
            def send_message_async(self, prompt):
                for _ in range(1000):
                    state["chunks"] += 1
                    time.sleep(0.002)
                    yield {"content": [{"type": "text", "text": "token "}]}

            def close(self):
                state["closed"] += 1

        class FakeEngine:
            def __init__(self, *args, **kwargs):
                pass

            def create_conversation(self, *, max_output_tokens=None):
                state["max_output_tokens"] = max_output_tokens
                return FakeConversation()

            def close(self):
                state["engine_closed"] += 1

        class FakeModule:
            Backend = FakeBackend
            Engine = FakeEngine

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model = root / "model.litertlm"
            model.write_bytes(b"fake")
            manager = ResidentEngineManager(
                str(model),
                cache_dir=str(root),
                context_tokens=128,
                request_timeout=5,
                module_loader=lambda: FakeModule,
            )

            async def run():
                first_token = asyncio.Event()
                events = []

                async def consume():
                    async for event in manager.stream(
                        "prompt",
                        max_output_tokens=500,
                    ):
                        events.append(event)
                        if event[0] == "token":
                            first_token.set()

                task = asyncio.create_task(consume())
                await asyncio.wait_for(first_token.wait(), timeout=2)
                self.assertTrue(manager.cancel_active("user"))
                await asyncio.wait_for(task, timeout=1)
                for _ in range(100):
                    if not manager.status()["loaded"]:
                        break
                    await asyncio.sleep(0.01)
                return events, manager.status()

            events, status = asyncio.run(run())
            manager.shutdown(wait=True)

        self.assertEqual(state["max_output_tokens"], 128)
        self.assertLess(state["chunks"], 1000)
        self.assertEqual(state["closed"], 1)
        self.assertEqual(state["engine_closed"], 1)
        self.assertFalse(status["loaded"])
        self.assertTrue(any(kind == "token" for kind, _ in events))


class ControllerQuarantineTests(unittest.TestCase):
    def test_corrupted_partial_is_visible_but_not_committed_or_learned(self):
        async def run():
            temporary = tempfile.TemporaryDirectory()
            self.addCleanup(temporary.cleanup)
            store = MemoryStore(Path(temporary.name) / "memory.db")

            async def fake_raw(
                prompt: str,
                model_role: str = "reasoning",
                max_output_tokens: int | None = None,
            ):
                for _ in range(40):
                    yield "token", "the "
                yield "_backend_meta", json.dumps(
                    {"backend": "fake", "return_code": 0}
                )

            with (
                patch.object(llm_controller, "STORE", store),
                patch.object(llm_controller, "_stream_raw_backend", new=fake_raw),
            ):
                events = []
                async for event in llm_controller.stream_inference(
                    "Write a creative fictional scene."
                ):
                    events.append(event)
            return store, events

        store, events = asyncio.run(run())
        stopped = [json.loads(value) for kind, value in events if kind == "generation_stopped"]
        self.assertEqual(len(stopped), 1)
        self.assertEqual(stopped[0]["reason"], "repetition_loop")
        self.assertTrue("".join(value for kind, value in events if kind == "token"))

        session = store.get_active_session(create=False)
        self.assertIsNotNone(session)
        messages = store.get_messages(str(session["id"]), limit=20, ascending=True)
        self.assertEqual([item["role"] for item in messages], ["user", "system"])
        self.assertEqual(messages[-1]["source"], "generation_guard")
        self.assertNotIn("assistant", [item["role"] for item in messages])
        self.assertEqual(store.status()["memories"], 0)

    def test_user_cancel_emits_stop_event_and_quarantines_partial(self):
        async def run():
            temporary = tempfile.TemporaryDirectory()
            self.addCleanup(temporary.cleanup)
            store = MemoryStore(Path(temporary.name) / "memory.db")
            first_token = asyncio.Event()
            events = []

            async def fake_raw(
                prompt: str,
                model_role: str = "reasoning",
                max_output_tokens: int | None = None,
            ):
                for index in range(100):
                    await asyncio.sleep(0.005)
                    yield "token", f"progress{index} "

            async def consume():
                async for event in llm_controller.stream_inference(
                    "Write a long fictional scene."
                ):
                    events.append(event)
                    if event[0] == "token":
                        first_token.set()

            with (
                patch.object(llm_controller, "STORE", store),
                patch.object(llm_controller, "_stream_raw_backend", new=fake_raw),
            ):
                task = asyncio.create_task(consume())
                await asyncio.wait_for(first_token.wait(), timeout=2)
                self.assertTrue(llm_controller.cancel_active_operations())
                await asyncio.wait_for(task, timeout=1)
            return store, events

        store, events = asyncio.run(run())
        stopped = [json.loads(value) for kind, value in events if kind == "generation_stopped"]
        self.assertEqual(len(stopped), 1)
        self.assertEqual(stopped[0]["reason"], "user")
        session = store.get_active_session(create=False)
        messages = store.get_messages(str(session["id"]), limit=20, ascending=True)
        self.assertEqual([item["role"] for item in messages], ["user", "system"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
