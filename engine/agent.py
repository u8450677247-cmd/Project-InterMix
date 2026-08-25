"""Ordered, observable workspace tools for Project Intermix.

Generated actions execute in the exact order emitted. A failed program stops
the current batch so the next stateless agent epoch must observe real feedback
before attempting a repair. Deletes are staged for explicit user review.
"""

from __future__ import annotations

import os
import re
import signal
import subprocess
import threading
import time
from dataclasses import asdict, dataclass
from typing import Any

from workspace_state import (
    MAX_READ_BYTES,
    WORKSPACE_DIR,
    file_hash,
    iter_workspace_files,
    read_text,
    relative_path,
    request_deletion,
    safe_path,
    write_text,
)


MAX_ACTIONS = 12
RUN_TIMEOUT_SECONDS = 20
MAX_SEARCH_RESULTS = 40


@dataclass
class AgentEvent:
    action: str
    path: str = ""
    result: str = ""
    exit_code: int | None = None
    before_hash: str | None = None
    after_hash: str | None = None
    request_id: str | None = None


@dataclass(frozen=True)
class ToolAction:
    name: str
    path: str = ""
    content: str = ""
    query: str = ""


class AgentRuntime:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._cancel = threading.Event()
        self._process: subprocess.Popen[str] | None = None
        self._state = "idle"
        self._action = ""
        self._path = ""
        self._started = 0.0
        self._actions_completed = 0
        self._last_error = ""

    def begin(self) -> None:
        with self._lock:
            self._cancel.clear()
            self._process = None
            self._state = "running"
            self._action = ""
            self._path = ""
            self._started = time.monotonic()
            self._actions_completed = 0
            self._last_error = ""

    def set_action(self, action: str, path: str = "") -> None:
        with self._lock:
            self._action = action
            self._path = path

    def attach_process(self, process: subprocess.Popen[str] | None) -> None:
        with self._lock:
            self._process = process

    def action_completed(self) -> None:
        with self._lock:
            self._actions_completed += 1

    @property
    def cancelled(self) -> bool:
        return self._cancel.is_set()

    def cancel(self) -> bool:
        self._cancel.set()
        with self._lock:
            process = self._process
            active = self._state == "running"
        if process is not None and process.poll() is None:
            _terminate_process(process)
        return active

    def finish(self, error: str = "") -> None:
        with self._lock:
            self._process = None
            self._state = "cancelled" if self._cancel.is_set() else ("error" if error else "idle")
            self._last_error = error[:1000]
            self._action = ""
            self._path = ""

    def status(self) -> dict[str, Any]:
        with self._lock:
            elapsed = time.monotonic() - self._started if self._state == "running" else 0.0
            return {
                "state": self._state,
                "action": self._action,
                "path": self._path,
                "elapsed_seconds": round(elapsed, 2),
                "actions_completed": self._actions_completed,
                "cancelled": self._cancel.is_set(),
                "last_error": self._last_error,
            }


AGENT_RUNTIME = AgentRuntime()


ACTION_PATTERN = re.compile(
    r"<write_file\b(?P<write_attrs>[^>]*)>(?P<write_body>.*?)</write_file>"
    r"|<(?P<self_name>read_file|run_python|delete_file|search_files|list_files|task_completed)"
    r"\b(?P<self_attrs>[^>]*)/>",
    re.IGNORECASE | re.DOTALL,
)
ATTRIBUTE_PATTERN = re.compile(
    r"(?P<name>[a-zA-Z_][\w-]*)\s*=\s*(['\"])(?P<value>.*?)\2",
    re.DOTALL,
)


def _attributes(raw: str) -> dict[str, str]:
    return {
        match.group("name").casefold(): match.group("value")
        for match in ATTRIBUTE_PATTERN.finditer(raw)
    }


def parse_tool_actions(response: str) -> list[ToolAction]:
    actions: list[ToolAction] = []
    for match in ACTION_PATTERN.finditer(response):
        if match.group("write_attrs") is not None:
            attrs = _attributes(match.group("write_attrs") or "")
            actions.append(
                ToolAction(
                    "write_file",
                    path=attrs.get("path", ""),
                    content=match.group("write_body") or "",
                )
            )
            continue
        name = str(match.group("self_name") or "").casefold()
        attrs = _attributes(match.group("self_attrs") or "")
        actions.append(
            ToolAction(
                name,
                path=attrs.get("path", attrs.get("target", "")),
                query=attrs.get("query", ""),
            )
        )
    return actions[:MAX_ACTIONS]


def _terminate_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (OSError, ProcessLookupError):
        try:
            process.terminate()
        except ProcessLookupError:
            return


def _run_python(target: str) -> tuple[str, AgentEvent]:
    path = safe_path(target, must_exist=True)
    if not path.is_file() or path.suffix.casefold() != ".py":
        raise ValueError("run_python requires an existing .py file")
    relative = relative_path(path)
    process = subprocess.Popen(
        ["python3", str(path)],
        cwd=str(WORKSPACE_DIR),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env={
            "PATH": os.environ.get("PATH", ""),
            "LANG": os.environ.get("LANG", "C.UTF-8"),
            "PYTHONPATH": str(WORKSPACE_DIR),
        },
        start_new_session=True,
    )
    AGENT_RUNTIME.attach_process(process)
    deadline = time.monotonic() + RUN_TIMEOUT_SECONDS
    cancelled = False
    timed_out = False
    stdout = ""
    stderr = ""
    try:
        while True:
            remaining = deadline - time.monotonic()
            if AGENT_RUNTIME.cancelled:
                cancelled = True
                _terminate_process(process)
            if remaining <= 0 and process.poll() is None:
                timed_out = True
                _terminate_process(process)
            try:
                stdout, stderr = process.communicate(timeout=0.20)
                break
            except subprocess.TimeoutExpired:
                if cancelled or timed_out:
                    try:
                        process.kill()
                    except ProcessLookupError:
                        pass
                continue
    finally:
        AGENT_RUNTIME.attach_process(None)

    out = stdout.strip()
    err = stderr.strip()
    if cancelled:
        result = "Cancelled by user."
        code = 130
    elif timed_out:
        result = f"Timed out after {RUN_TIMEOUT_SECONDS} seconds."
        code = 124
    else:
        code = int(process.returncode or 0)
        result = out or err or "Process completed without output."
    feedback = [f"[Exit {code}] {relative}"]
    if out:
        feedback.append("STDOUT:\n" + out[:12000])
    if err:
        feedback.append("STDERR:\n" + err[:12000])
    if not out and not err:
        feedback.append(result)
    event = AgentEvent(
        "run_python",
        relative,
        result[:4000],
        code,
        after_hash=file_hash(path),
    )
    return "\n".join(feedback), event


def _search_files(query: str) -> tuple[str, AgentEvent]:
    clean = query.strip()
    if not clean:
        raise ValueError("search_files requires a non-empty query")
    needle = clean.casefold()
    matches: list[str] = []
    for path in iter_workspace_files():
        try:
            if path.stat().st_size > MAX_READ_BYTES:
                continue
            raw = path.read_bytes()
            if b"\x00" in raw:
                continue
            for line_number, line in enumerate(raw.decode("utf-8", "replace").splitlines(), 1):
                if needle in line.casefold():
                    matches.append(
                        f"{relative_path(path)}:{line_number}: {line.strip()[:300]}"
                    )
                    if len(matches) >= MAX_SEARCH_RESULTS:
                        break
        except OSError:
            continue
        if len(matches) >= MAX_SEARCH_RESULTS:
            break
    result = "\n".join(matches) if matches else "No matching workspace text."
    return (
        "[Workspace Search]\n" + result,
        AgentEvent("search_files", "", result[:4000], 0),
    )


def execute_agent_tools_detailed(ai_response: str) -> tuple[str, list[dict[str, Any]]]:
    feedback: list[str] = []
    events: list[AgentEvent] = []
    actions = parse_tool_actions(ai_response)
    if not actions:
        return "", []

    AGENT_RUNTIME.begin()
    terminal_error = ""
    try:
        for action in actions:
            if AGENT_RUNTIME.cancelled:
                event = AgentEvent("cancelled", action.path, "Cancelled by user.", 130)
                events.append(event)
                feedback.append("[Cancelled] Remaining workspace actions were not executed.")
                break
            AGENT_RUNTIME.set_action(action.name, action.path or action.query)
            try:
                if action.name == "write_file":
                    if not action.path:
                        raise ValueError("write_file requires a relative path")
                    content = action.content.strip("\n") + "\n"
                    raw_event = write_text(action.path, content, source="agent")
                    event = AgentEvent(
                        "write_file",
                        str(raw_event["path"]),
                        "success",
                        0,
                        raw_event.get("before_hash"),
                        raw_event.get("after_hash"),
                    )
                    feedback.append(f"[Success] Wrote {event.path}")
                elif action.name == "read_file":
                    data, _, reason = read_text(action.path)
                    path = safe_path(action.path, must_exist=True)
                    relative = relative_path(path)
                    suffix = f"\n[{reason}]" if reason else ""
                    feedback.append(f"[File {relative}]\n{data}{suffix}")
                    event = AgentEvent("read_file", relative, f"read {len(data)} characters", 0)
                elif action.name == "list_files":
                    files = [relative_path(path) for path in iter_workspace_files(limit=200)]
                    listing = "\n".join(files) if files else "(workspace empty)"
                    feedback.append("[Workspace Files]\n" + listing)
                    event = AgentEvent("list_files", "", f"listed {len(files)} files", 0)
                elif action.name == "search_files":
                    search_feedback, event = _search_files(action.query)
                    feedback.append(search_feedback)
                elif action.name == "delete_file":
                    request = request_deletion(action.path)
                    event = AgentEvent(
                        "delete_requested",
                        str(request["path"]),
                        "awaiting user review",
                        None,
                        request.get("expected_hash"),
                        request_id=str(request["id"]),
                    )
                    feedback.append(
                        f"[Approval Required] Delete {event.path}\n"
                        f"Approve with /approve {event.request_id} or deny with /deny {event.request_id}."
                    )
                elif action.name == "run_python":
                    run_feedback, event = _run_python(action.path)
                    feedback.append(run_feedback)
                elif action.name == "task_completed":
                    event = AgentEvent("task_completed", "", "completion requested", 0)
                    feedback.append("[Completion Requested] Verification is being checked.")
                else:
                    continue
                events.append(event)
                AGENT_RUNTIME.action_completed()
                if isinstance(event.exit_code, int) and event.exit_code != 0:
                    terminal_error = event.result
                    feedback.append(
                        "[Repair Loop] Ordered execution paused at the verified failure; "
                        "remaining generated actions were discarded."
                    )
                    break
                if action.name == "delete_file":
                    break
            except Exception as exc:
                terminal_error = f"{type(exc).__name__}: {exc}"
                event = AgentEvent(action.name, action.path, terminal_error, 1)
                events.append(event)
                feedback.append(f"[{action.name} Error] {action.path}: {exc}")
                feedback.append(
                    "[Repair Loop] Ordered execution paused at the verified failure."
                )
                break

        if len(actions) >= MAX_ACTIONS:
            feedback.append(f"[Safety Limit] Maximum {MAX_ACTIONS} tool actions reached.")
    finally:
        AGENT_RUNTIME.finish(terminal_error)
    return "\n".join(feedback), [asdict(event) for event in events]


def cancel_agent_tools() -> bool:
    return AGENT_RUNTIME.cancel()


def get_agent_status() -> dict[str, Any]:
    return AGENT_RUNTIME.status()


def execute_agent_tools(ai_response: str) -> str:
    feedback, _ = execute_agent_tools_detailed(ai_response)
    return feedback


__all__ = [
    "AGENT_RUNTIME",
    "MAX_ACTIONS",
    "WORKSPACE_DIR",
    "cancel_agent_tools",
    "execute_agent_tools",
    "execute_agent_tools_detailed",
    "get_agent_status",
    "parse_tool_actions",
]
