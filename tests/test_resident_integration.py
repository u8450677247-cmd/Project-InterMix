from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"


class ResidentIntegrationTests(unittest.TestCase):
    def test_workspace_mission_repairs_across_fresh_conversations(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            fake_modules = home / "fake_modules"
            fake_modules.mkdir()
            model_dir = home / "project-intermix" / "models"
            model_dir.mkdir(parents=True)
            (home / "project-intermix" / "memory").mkdir(parents=True)
            (model_dir / "gemma-4-E4B-it.litertlm").write_bytes(b"fake-model")

            fake_modules.joinpath("litert_lm.py").write_text(
                textwrap.dedent(
                    '''
                    CONVERSATION_COUNT = 0

                    class Backend:
                        @staticmethod
                        def GPU():
                            return "gpu"

                    class Conversation:
                        def __init__(self, index):
                            self.index = index

                        def send_message_async(self, prompt):
                            if self.index == 1:
                                response = (
                                    '<write_file path="self_repair.py">\\n'
                                    'import doesnotexist\\n'
                                    '</write_file>\\n'
                                    '<run_python target="self_repair.py"/>\\n'
                                    '<write_file path="self_repair.py">\\n'
                                    'print("INVALID_SHORTCUT")\\n'
                                    '</write_file>'
                                )
                            else:
                                assert "VERIFIED MISSION LEDGER" in prompt
                                assert "doesnotexist" in prompt
                                response = (
                                    '<write_file path="self_repair.py">\\n'
                                    'print("SELF_REPAIR_OK")\\n'
                                    '</write_file>\\n'
                                    '<run_python target="self_repair.py"/>\\n'
                                    '<task_completed/>'
                                )
                            yield {"content": [{"type": "text", "text": response}]}

                        def close(self):
                            pass

                    class Engine:
                        def __init__(self, *args, **kwargs):
                            pass

                        def create_conversation(self):
                            global CONVERSATION_COUNT
                            CONVERSATION_COUNT += 1
                            return Conversation(CONVERSATION_COUNT)

                        def close(self):
                            pass
                    '''
                ),
                encoding="utf-8",
            )

            driver = textwrap.dedent(
                '''
                import asyncio
                import json
                import litert_lm
                import llm_controller
                from task_ledger import active_task
                from workspace_state import WORKSPACE_DIR

                async def run():
                    events = []
                    async for event in llm_controller.stream_inference(
                        "/workspace Build and verify self_repair.py"
                    ):
                        events.append(event)
                    visible = "".join(value for kind, value in events if kind == "token")
                    assert "<write_file" not in visible, visible
                    assert "Mission epoch 1 prepared" in visible, visible
                    transcript = llm_controller.recent_transcript(limit=20)
                    assert not any(
                        "<write_file" in item.get("content", "")
                        for item in transcript
                        if item.get("role") == "assistant"
                    ), transcript
                    feedback = "\\n".join(value for kind, value in events if kind == "execution")
                    assert "ModuleNotFoundError" in feedback, feedback
                    assert "SELF_REPAIR_OK" in feedback, feedback
                    assert (WORKSPACE_DIR / "self_repair.py").read_text().strip() == 'print("SELF_REPAIR_OK")'
                    task = active_task()
                    assert task and task["status"] == "completed", task
                    task_events = [
                        json.loads(value)
                        for kind, value in events
                        if kind == "task"
                    ]
                    assert sum(
                        item.get("status") == "completed" for item in task_events
                    ) == 1, task_events
                    assert litert_lm.CONVERSATION_COUNT == 2, litert_lm.CONVERSATION_COUNT

                asyncio.run(run())
                llm_controller.RESIDENT_ENGINE.shutdown(wait=True)
                print("workspace-mission-repair-ok")
                '''
            )
            env = os.environ.copy()
            env["HOME"] = str(home)
            env["PYTHONPATH"] = os.pathsep.join((str(fake_modules), str(ENGINE)))
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("workspace-mission-repair-ok", result.stdout)

    def test_cross_session_recall_uses_verified_controller_record(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            fake_modules = home / "fake_modules"
            fake_modules.mkdir()
            model_dir = home / "project-intermix" / "models"
            model_dir.mkdir(parents=True)
            (home / "project-intermix" / "memory").mkdir(parents=True)
            (model_dir / "gemma-4-E4B-it.litertlm").write_bytes(b"fake-model")

            fake_modules.joinpath("litert_lm.py").write_text(
                textwrap.dedent(
                    '''
                    class Backend:
                        @staticmethod
                        def GPU():
                            return "gpu"

                    class Conversation:
                        def send_message_async(self, prompt):
                            assert "RECENT VERIFIED WORK — CONTROLLER RECORD" in prompt, prompt
                            assert "continuity_probe.py" in prompt, prompt
                            assert "CONTINUITY_OK" in prompt, prompt
                            assert "v1.4.1" in prompt, prompt
                            yield {"content": [{"type": "text", "text": (
                                "We built and ran `continuity_probe.py`; its verified "
                                "output was `CONTINUITY_OK`, and the mission completed. ✅"
                            )}]}

                        def close(self):
                            pass

                    class Engine:
                        def __init__(self, *args, **kwargs):
                            pass

                        def create_conversation(self):
                            return Conversation()

                        def close(self):
                            pass
                    '''
                ),
                encoding="utf-8",
            )

            driver = textwrap.dedent(
                '''
                import asyncio
                import llm_controller
                from task_ledger import create_task, mark_completed, record_events

                task = create_task(
                    "Create continuity_probe.py, run it, verify the output, and complete the task.",
                    session_id="previous-session",
                )
                task = record_events(task["id"], [{
                    "action": "write_file", "path": "continuity_probe.py",
                    "result": "success", "exit_code": 0,
                    "before_hash": None, "after_hash": "abc123"
                }, {
                    "action": "run_python", "path": "continuity_probe.py",
                    "result": "CONTINUITY_OK", "exit_code": 0,
                    "before_hash": "abc123", "after_hash": "abc123"
                }])
                task, verified, reason = mark_completed(task["id"])
                assert verified, (task, reason)

                store = llm_controller.get_store()
                store.create_session("Continuity Recall Test")

                async def run():
                    events = []
                    async for event in llm_controller.stream_inference(
                        "What did we verify in the previous project session?"
                    ):
                        events.append(event)
                    text = "".join(value for kind, value in events if kind == "token")
                    assert "continuity_probe.py" in text, text
                    assert "CONTINUITY_OK" in text, text
                    assert not [value for kind, value in events if kind == "grounding"], events

                asyncio.run(run())
                llm_controller.RESIDENT_ENGINE.shutdown(wait=True)
                print("verified-cross-session-recall-ok")
                '''
            )
            env = os.environ.copy()
            env["HOME"] = str(home)
            env["PYTHONPATH"] = os.pathsep.join((str(fake_modules), str(ENGINE)))
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=20,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("verified-cross-session-recall-ok", result.stdout)

    def test_forced_web_discards_uncited_draft_and_accepts_cited_answer(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            fake_modules = home / "fake_modules"
            fake_modules.mkdir()
            model_dir = home / "project-intermix" / "models"
            model_dir.mkdir(parents=True)
            (home / "project-intermix" / "memory").mkdir(parents=True)
            (model_dir / "gemma-4-E4B-it.litertlm").write_bytes(b"fake-model")

            fake_modules.joinpath("litert_lm.py").write_text(
                textwrap.dedent(
                    '''
                    ENGINE_COUNT = 0
                    CONVERSATION_COUNT = 0

                    class Backend:
                        @staticmethod
                        def GPU():
                            return "gpu"

                    class Conversation:
                        def __init__(self, index):
                            self.index = index

                        def send_message_async(self, prompt):
                            response = (
                                "A Pixel 10 Pro update exists, according to my internal knowledge."
                                if self.index == 1
                                else "The supplied evidence reports a Pixel 10 Pro August security update [1]."
                            )
                            yield {"content": [{"type": "text", "text": response}]}

                        def close(self):
                            pass

                    class Engine:
                        def __init__(self, *args, **kwargs):
                            global ENGINE_COUNT
                            ENGINE_COUNT += 1

                        def create_conversation(self):
                            global CONVERSATION_COUNT
                            CONVERSATION_COUNT += 1
                            return Conversation(CONVERSATION_COUNT)

                        def close(self):
                            pass
                    '''
                ),
                encoding="utf-8",
            )

            driver = textwrap.dedent(
                '''
                import asyncio
                import litert_lm
                import llm_controller

                evidence = """Query: current Pixel 10 Pro Android update
                [1] Pixel 10 Pro receives August 2026 security update
                Source: https://support.example.test/pixel-10-pro-august-2026
                Evidence: The Pixel 10 Pro security update carries build BP4A.260824.001.
                Provider: DuckDuckGo HTML
                """
                llm_controller.search_web = lambda query: evidence

                async def collect():
                    events = []
                    async for event in llm_controller.stream_inference(
                        "/web current Pixel 10 Pro Android update",
                        force_web=True,
                    ):
                        events.append(event)
                    return events

                async def run():
                    rejected = await collect()
                    assert not [value for kind, value in rejected if kind == "token"], rejected
                    errors = [value for kind, value in rejected if kind == "error"]
                    assert errors and "rejected the model draft" in errors[-1], rejected

                    accepted = await collect()
                    text = "".join(value for kind, value in accepted if kind == "token")
                    assert text == "The supplied evidence reports a Pixel 10 Pro August security update [1].", accepted
                    assert not [value for kind, value in accepted if kind == "error"], accepted
                    assert any(
                        kind == "phase" and "Source-linked draft verified" in value
                        for kind, value in accepted
                    )
                    assert litert_lm.ENGINE_COUNT == 1
                    assert litert_lm.CONVERSATION_COUNT == 2
                    assert llm_controller.get_store().status()["messages"] == 4

                asyncio.run(run())
                llm_controller.RESIDENT_ENGINE.shutdown(wait=True)
                print("forced-web-citation-contract-ok")
                '''
            )
            env = os.environ.copy()
            env["HOME"] = str(home)
            env["PYTHONPATH"] = os.pathsep.join((str(fake_modules), str(ENGINE)))
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=20,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("forced-web-citation-contract-ok", result.stdout)

    def test_forced_web_failure_stops_before_inference(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            fake_modules = home / "fake_modules"
            fake_modules.mkdir()
            (home / "project-intermix" / "memory").mkdir(parents=True)
            fake_modules.joinpath("litert_lm.py").write_text(
                "ENGINE_COUNT = 0\n",
                encoding="utf-8",
            )

            driver = textwrap.dedent(
                '''
                import asyncio
                import litert_lm
                import llm_controller

                llm_controller.search_web = lambda query: "No current web results were available."

                async def run():
                    events = []
                    async for event_type, payload in llm_controller.stream_inference(
                        "/web current Pixel release",
                        force_web=True,
                    ):
                        events.append((event_type, payload))
                    assert not [payload for kind, payload in events if kind == "token"], events
                    errors = [payload for kind, payload in events if kind == "error"]
                    assert errors and "No speculative answer" in errors[0], events
                    assert litert_lm.ENGINE_COUNT == 0
                    status = llm_controller.get_store().status()
                    assert status["messages"] == 2, status

                asyncio.run(run())
                print("forced-web-guard-ok")
                '''
            )
            env = os.environ.copy()
            env["HOME"] = str(home)
            env["PYTHONPATH"] = os.pathsep.join((str(fake_modules), str(ENGINE)))
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=20,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("forced-web-guard-ok", result.stdout)

    def test_controller_streams_and_hides_protocol_through_resident_backend(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            fake_modules = home / "fake_modules"
            fake_modules.mkdir()
            model_dir = home / "project-intermix" / "models"
            model_dir.mkdir(parents=True)
            (home / "project-intermix" / "memory").mkdir(parents=True)
            (model_dir / "gemma-4-E4B-it.litertlm").write_bytes(b"fake-model")

            fake_modules.joinpath("litert_lm.py").write_text(
                textwrap.dedent(
                    '''
                    ENGINE_COUNT = 0

                    class Backend:
                        @staticmethod
                        def GPU():
                            return "gpu"

                    class Conversation:
                        def send_message_async(self, prompt):
                            output = (
                                "Resident integration works."
                                "<MEMORY_UPDATE>"
                                '{"memories":[{"operation":"upsert","kind":"user_preference",'
                                '"key":"integration_path","value":"Prefers resident inference",'
                                '"explicitly_stated":true}],'
                                '"session":{"title":"Resident Test","summary":"Resident path verified.",'
                                '"current_task":"","open_loops":[],"decisions":[],"tone_state":"calm",'
                                '"active_project":"Project Intermix"}}'
                                "</MEMORY_UPDATE>"
                            )
                            for index in range(0, len(output), 7):
                                yield {"content": [{"type": "text", "text": output[index:index + 7]}]}

                        def close(self):
                            pass

                    class Engine:
                        def __init__(self, *args, **kwargs):
                            global ENGINE_COUNT
                            ENGINE_COUNT += 1

                        def create_conversation(self):
                            return Conversation()

                        def close(self):
                            pass
                    '''
                ),
                encoding="utf-8",
            )

            driver = textwrap.dedent(
                '''
                import asyncio
                import litert_lm
                from llm_controller import RESIDENT_ENGINE, get_store, stream_inference

                async def run():
                    visible = []
                    events = []
                    async for event_type, payload in stream_inference(
                        "Remember explicitly that I prefer resident inference."
                    ):
                        events.append(event_type)
                        if event_type == "token":
                            visible.append(payload)
                    text = "".join(visible)
                    assert text == "Resident integration works.", text
                    assert "MEMORY_UPDATE" not in text
                    assert "phase" in events
                    status = get_store().status()
                    assert status["messages"] == 2, status
                    assert status["memories"] == 1, status
                    assert litert_lm.ENGINE_COUNT == 1

                asyncio.run(run())
                RESIDENT_ENGINE.shutdown(wait=True)
                print("resident-integration-ok")
                '''
            )
            env = os.environ.copy()
            env["HOME"] = str(home)
            env["PYTHONPATH"] = os.pathsep.join((str(fake_modules), str(ENGINE)))
            result = subprocess.run(
                [sys.executable, "-c", driver],
                capture_output=True,
                text=True,
                timeout=20,
                env=env,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("resident-integration-ok", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
