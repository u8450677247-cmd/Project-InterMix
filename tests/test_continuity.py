from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from response_policy import select_response_policy


def run_isolated(source: str, timeout: int = 20) -> subprocess.CompletedProcess[str]:
    with tempfile.TemporaryDirectory() as temp:
        env = os.environ.copy()
        env["HOME"] = temp
        env["PYTHONPATH"] = str(ENGINE)
        return subprocess.run(
            [sys.executable, "-c", textwrap.dedent(source)],
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
        )


class ResponsePolicyTests(unittest.TestCase):
    def test_adaptive_modes_and_manual_override(self):
        normal = select_response_policy("How are you?")
        deep = select_response_policy("Give me a comprehensive in-depth explanation")
        brief = select_response_policy("/brief explain this in depth")
        workspace = select_response_policy("Create a Python file", agent_mode=True)
        self.assertEqual(normal.mode, "normal")
        self.assertEqual(normal.sentence_target, 10)
        self.assertEqual(deep.mode, "deep")
        self.assertEqual(brief.mode, "brief")
        self.assertEqual(workspace.mode, "workspace")
        for policy in (normal, deep, brief, workspace):
            self.assertEqual(policy.input_limit + policy.output_reserve, 8000)


class OrderedAgentTests(unittest.TestCase):
    def test_failure_stops_later_generated_actions_then_next_epoch_repairs(self):
        result = run_isolated(
            r"""
            from pathlib import Path
            from agent import execute_agent_tools_detailed
            from workspace_state import WORKSPACE_DIR

            first = '''<write_file path="probe.py">
            import doesnotexist
            </write_file>
            <run_python target="probe.py"/>
            <write_file path="probe.py">
            print("SHOULD_NOT_HAVE_RUN")
            </write_file>'''
            feedback, events = execute_agent_tools_detailed(first)
            assert [event["action"] for event in events] == ["write_file", "run_python"], events
            assert "doesnotexist" in (WORKSPACE_DIR / "probe.py").read_text(), feedback
            assert "Ordered execution paused" in feedback

            second = '''<write_file path="probe.py">
            print("SELF_REPAIR_OK")
            </write_file>
            <run_python target="probe.py"/>
            <task_completed/>'''
            feedback, events = execute_agent_tools_detailed(second)
            assert [event["action"] for event in events] == [
                "write_file", "run_python", "task_completed"
            ], events
            assert events[1]["exit_code"] == 0, feedback
            assert "SELF_REPAIR_OK" in feedback
            print("ordered-repair-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ordered-repair-ok", result.stdout)

    def test_deletion_requires_review_and_hash_revalidation(self):
        result = run_isolated(
            r"""
            from agent import execute_agent_tools_detailed
            from workspace_state import WORKSPACE_DIR, approve_deletion, pending_deletions

            target = WORKSPACE_DIR / "keep_until_approved.txt"
            target.write_text("original", encoding="utf-8")
            feedback, events = execute_agent_tools_detailed(
                '<delete_file path="keep_until_approved.txt"/>'
            )
            assert target.exists(), feedback
            assert events[0]["action"] == "delete_requested", events
            request = pending_deletions()[0]
            target.write_text("changed after review", encoding="utf-8")
            try:
                approve_deletion(request["id"])
            except ValueError as exc:
                assert "changed" in str(exc)
            else:
                raise AssertionError("changed file deletion should be rejected")
            assert target.exists()
            print("deletion-review-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("deletion-review-ok", result.stdout)

    def test_agent_cannot_modify_reserved_control_state(self):
        result = run_isolated(
            r"""
            from agent import execute_agent_tools_detailed

            feedback, events = execute_agent_tools_detailed(
                '<write_file path=".intermix/research_policy.json">{}</write_file>'
            )
            assert events[0]["exit_code"] == 1, (feedback, events)
            assert "reserved" in feedback
            print("control-state-guard-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("control-state-guard-ok", result.stdout)

    def test_cancellation_stops_process_and_remaining_actions(self):
        result = run_isolated(
            r"""
            import threading
            import time
            from agent import (
                cancel_agent_tools, execute_agent_tools_detailed, get_agent_status
            )
            from workspace_state import WORKSPACE_DIR

            (WORKSPACE_DIR / "wait.py").write_text(
                "import time\ntime.sleep(30)\nprint('TOO_LATE')\n",
                encoding="utf-8",
            )
            response = '''<run_python target="wait.py"/>
            <write_file path="after.txt">SHOULD_NOT_EXIST</write_file>'''
            result_box = []

            def run():
                result_box.append(execute_agent_tools_detailed(response))

            worker = threading.Thread(target=run)
            worker.start()
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                status = get_agent_status()
                if status["state"] == "running" and status["action"] == "run_python":
                    break
                time.sleep(0.02)
            assert cancel_agent_tools() is True
            worker.join(5)
            assert not worker.is_alive(), get_agent_status()
            feedback, events = result_box[0]
            assert events[0]["exit_code"] == 130, (feedback, events)
            assert not (WORKSPACE_DIR / "after.txt").exists()
            assert "Cancelled" in feedback
            print("agent-cancel-ok")
            """,
            timeout=12,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("agent-cancel-ok", result.stdout)


class MissionLedgerTests(unittest.TestCase):
    def test_task_survives_session_change_and_requires_post_write_test(self):
        result = run_isolated(
            r"""
            import json
            from task_ledger import (
                format_mission_context, format_recent_verified_work,
                get_or_create_task, mark_completed, record_events
            )

            task, created = get_or_create_task("Build probe.py", session_id="session-one")
            assert created
            task_id = task["id"]
            task = record_events(task_id, [{
                "action": "write_file", "path": "probe.py", "result": "success",
                "exit_code": 0, "before_hash": None, "after_hash": "abc"
            }])
            task, verified, reason = mark_completed(task_id)
            assert not verified and "not passed" in reason
            resumed, created = get_or_create_task("Continue", session_id="session-two")
            assert not created and resumed["id"] == task_id
            resumed = record_events(task_id, [{
                "action": "run_python", "path": "probe.py", "result": "SELF_REPAIR_OK",
                "exit_code": 0, "before_hash": None, "after_hash": "abc"
            }])
            resumed, verified, reason = mark_completed(task_id)
            assert verified and resumed["status"] == "completed", (resumed, reason)
            context = format_mission_context(resumed)
            payload = context.split("\n", 1)[1]
            assert json.loads(payload)["id"] == task_id
            recent = format_recent_verified_work()
            assert "RECENT VERIFIED WORK" in recent
            assert "probe.py" in recent
            assert "SELF_REPAIR_OK" in recent
            assert "session-two" in resumed["session_ids"]
            print("mission-ledger-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("mission-ledger-ok", result.stdout)


class GroundingIntentTests(unittest.TestCase):
    def test_auto_grounding_ignores_local_recall_and_long_design_briefs(self):
        result = run_isolated(
            r"""
            from llm_controller import asks_for_recent_verified_work, should_ground

            assert asks_for_recent_verified_work(
                "What did we verify in the previous project session?"
            )
            assert not should_ground(
                "What did we verify in the previous project session?"
            )
            assert not should_ground(
                "The current trajectory is good. Draft the v1.2.1 release design "
                + "with self-audit and current context language. " * 120
            )
            assert should_ground("What is the current Pixel 10 Pro Android update?")
            assert should_ground("latest Android security update")
            assert should_ground("anything", forced=True)
            print("grounding-intent-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("grounding-intent-ok", result.stdout)


class DocumentationTests(unittest.TestCase):
    def test_incremental_documentation_only_rewrites_on_changes(self):
        result = run_isolated(
            r"""
            from workspace_documenter import update_project_documentation
            from workspace_state import WORKSPACE_DIR

            (WORKSPACE_DIR / "sample.py").write_text(
                "import json\n\nclass Sample:\n    pass\n\ndef run():\n    return 1\n",
                encoding="utf-8",
            )
            first = update_project_documentation()
            second = update_project_documentation()
            document = (WORKSPACE_DIR / "PROJECT_STATE.md").read_text(encoding="utf-8")
            assert first["changed"] is True, first
            assert second["changed"] is False, second
            assert "classes: Sample" in document
            assert "functions: run" in document
            print("incremental-docs-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("incremental-docs-ok", result.stdout)


class ResearchPolicyTests(unittest.TestCase):
    def test_locked_source_policy_excludes_general_web(self):
        result = run_isolated(
            r"""
            from research_scout import ensure_policy, discovered_sources

            policy = ensure_policy()
            sources = policy["source_policy"]
            assert sources["official_repositories_and_documentation"] is True
            assert sources["verified_registries_and_releases"] is True
            assert sources["community_issues_and_discussions"] is True
            assert sources["general_web_results"] is False
            assert policy["run_condition"] == "app_open_and_idle"
            assert discovered_sources()["github_repository"] == ""
            print("research-policy-ok")
            """
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("research-policy-ok", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
