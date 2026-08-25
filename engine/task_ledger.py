"""Persistent mission ledger for reset-safe workspace autonomy.

The model receives a compact materialized view, while the controller records
the verified event history.  Chat sessions may reset without losing task
progress, and an agent cannot declare success ahead of the executor.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import uuid
from pathlib import Path
from typing import Any

from workspace_state import META_DIR, atomic_write_json, ensure_workspace, utc_now


TASK_SCHEMA_VERSION = 1
TASKS_DIR = META_DIR / "tasks"
ACTIVE_TASK_FILE = META_DIR / "active_task.json"
MAX_EVENT_HISTORY = 32
MAX_FILE_RECORDS = 300

_LEDGER_LOCK = threading.RLock()


def _clean_goal(goal: str) -> str:
    return re.sub(r"\s+", " ", goal.replace("\x00", " ")).strip()[:2000]


def _task_dir(task_id: str) -> Path:
    clean = re.sub(r"[^a-zA-Z0-9_-]", "", task_id)
    if not clean:
        raise ValueError("Invalid task ID")
    return TASKS_DIR / clean


def _state_path(task_id: str) -> Path:
    return _task_dir(task_id) / "state.json"


def _events_path(task_id: str) -> Path:
    return _task_dir(task_id) / "events.jsonl"


def _read_state(task_id: str) -> dict[str, Any] | None:
    try:
        value = json.loads(_state_path(task_id).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _write_state(state: dict[str, Any]) -> dict[str, Any]:
    state["updated_at"] = utc_now()
    state["revision"] = int(state.get("revision", 0)) + 1
    atomic_write_json(_state_path(str(state["id"])), state)
    return state


def _append_event(task_id: str, event: dict[str, Any]) -> None:
    path = _events_path(task_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"time": utc_now(), **event}
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")


def _active_id() -> str:
    try:
        payload = json.loads(ACTIVE_TASK_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ""
    return str(payload.get("id", "")) if isinstance(payload, dict) else ""


def _set_active(task_id: str) -> None:
    atomic_write_json(ACTIVE_TASK_FILE, {"id": task_id, "updated_at": utc_now()})


def create_task(goal: str, *, session_id: str = "") -> dict[str, Any]:
    ensure_workspace()
    TASKS_DIR.mkdir(parents=True, exist_ok=True)
    clean_goal = _clean_goal(goal)
    if not clean_goal:
        raise ValueError("Task goal cannot be empty")
    task_id = uuid.uuid4().hex[:12]
    now = utc_now()
    state: dict[str, Any] = {
        "schema_version": TASK_SCHEMA_VERSION,
        "id": task_id,
        "goal": clean_goal,
        "status": "active",
        "revision": 0,
        "event_seq": 0,
        "created_at": now,
        "updated_at": now,
        "session_ids": [session_id] if session_id else [],
        "acceptance_criteria": [clean_goal[:500]],
        "completed_steps": [],
        "next_action": clean_goal[:600],
        "files": {},
        "last_test": {},
        "last_error": "",
        "attempts": 0,
        "epochs": 0,
        "last_mutation_seq": 0,
        "last_verification_seq": 0,
        "last_failure_signature": "",
        "repeat_failure_count": 0,
        "pending_deletions": [],
    }
    with _LEDGER_LOCK:
        _task_dir(task_id).mkdir(parents=True, exist_ok=True)
        _write_state(state)
        _set_active(task_id)
        _append_event(task_id, {"action": "task_created", "goal": clean_goal})
    return state


def get_task(task_id: str) -> dict[str, Any] | None:
    with _LEDGER_LOCK:
        return _read_state(task_id)


def active_task() -> dict[str, Any] | None:
    task_id = _active_id()
    return get_task(task_id) if task_id else None


def list_tasks(limit: int = 20) -> list[dict[str, Any]]:
    ensure_workspace()
    TASKS_DIR.mkdir(parents=True, exist_ok=True)
    results: list[dict[str, Any]] = []
    for path in TASKS_DIR.glob("*/state.json"):
        try:
            state = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(state, dict):
            results.append(state)
    results.sort(key=lambda item: str(item.get("updated_at", "")), reverse=True)
    return results[: max(1, min(limit, 100))]


def activate_task(task_id: str) -> dict[str, Any]:
    with _LEDGER_LOCK:
        state = _read_state(task_id)
        if not state:
            raise ValueError("Task ID was not found")
        state["status"] = "active"
        state["repeat_failure_count"] = 0
        state["last_failure_signature"] = ""
        _write_state(state)
        _set_active(task_id)
        _append_event(task_id, {"action": "task_resumed"})
        return state


def pause_active_task() -> dict[str, Any] | None:
    with _LEDGER_LOCK:
        state = active_task()
        if not state:
            return None
        state["status"] = "paused"
        _write_state(state)
        _append_event(str(state["id"]), {"action": "task_paused"})
        return state


def get_or_create_task(goal: str, *, session_id: str) -> tuple[dict[str, Any], bool]:
    """Resume an active task, or begin a new mission when none is active."""
    with _LEDGER_LOCK:
        state = active_task()
        if state and state.get("status") == "active":
            sessions = list(state.get("session_ids") or [])
            if session_id and session_id not in sessions:
                sessions.append(session_id)
                state["session_ids"] = sessions[-20:]
            instruction = _clean_goal(goal)
            if instruction and instruction.casefold() != str(state.get("goal", "")).casefold():
                _append_event(str(state["id"]), {"action": "user_instruction", "text": instruction})
                state["next_action"] = instruction[:600]
            _write_state(state)
            return state, False
        return create_task(goal, session_id=session_id), True


def increment_epoch(task_id: str) -> dict[str, Any]:
    with _LEDGER_LOCK:
        state = _read_state(task_id)
        if not state:
            raise ValueError("Task ID was not found")
        state["epochs"] = int(state.get("epochs", 0)) + 1
        _write_state(state)
        return state


def _failure_signature(event: dict[str, Any]) -> str:
    parts = (
        str(event.get("action", "")),
        str(event.get("path", "")),
        str(event.get("result", ""))[-1200:],
        str(event.get("after_hash", "")),
    )
    return hashlib.sha256("\x1f".join(parts).encode("utf-8", "replace")).hexdigest()


def record_events(task_id: str, events: list[dict[str, Any]]) -> dict[str, Any]:
    with _LEDGER_LOCK:
        state = _read_state(task_id)
        if not state:
            raise ValueError("Task ID was not found")
        history = list(state.get("completed_steps") or [])
        files = dict(state.get("files") or {})
        pending = list(state.get("pending_deletions") or [])
        for event in events:
            seq = int(state.get("event_seq", 0)) + 1
            state["event_seq"] = seq
            action = str(event.get("action", "tool"))[:80]
            path = str(event.get("path", ""))[:500]
            result = str(event.get("result", ""))[:1000]
            exit_code = event.get("exit_code")
            record = {
                "seq": seq,
                "action": action,
                "path": path,
                "result": result[:300],
                "exit_code": exit_code,
                "time": utc_now(),
            }
            history.append(record)
            _append_event(task_id, {**event, "seq": seq})

            if action == "write_file" and path:
                state["last_mutation_seq"] = seq
                files[path] = {
                    "status": "present",
                    "hash": event.get("after_hash"),
                    "updated_seq": seq,
                }
            elif action == "delete_file" and path:
                state["last_mutation_seq"] = seq
                files[path] = {"status": "deleted", "hash": None, "updated_seq": seq}
            elif action == "delete_requested" and path:
                request_id = str(event.get("request_id", ""))
                if request_id and request_id not in pending:
                    pending.append(request_id)
            elif action in {"delete_denied", "delete_file"}:
                request_id = str(event.get("request_id", ""))
                pending = [item for item in pending if item != request_id]

            if action == "run_python":
                state["last_test"] = {
                    "path": path,
                    "exit_code": exit_code,
                    "result": result[:1200],
                    "seq": seq,
                    "time": utc_now(),
                }
                if exit_code == 0:
                    state["last_verification_seq"] = seq

            if isinstance(exit_code, int) and exit_code != 0:
                signature = _failure_signature(event)
                if signature == state.get("last_failure_signature"):
                    state["repeat_failure_count"] = int(state.get("repeat_failure_count", 0)) + 1
                else:
                    state["last_failure_signature"] = signature
                    state["repeat_failure_count"] = 1
                state["attempts"] = int(state.get("attempts", 0)) + 1
                state["last_error"] = result[:1600]
                state["next_action"] = f"Repair {path or action} from the verified failure."
            elif exit_code == 0:
                state["repeat_failure_count"] = 0
                state["last_failure_signature"] = ""
                if action == "run_python":
                    state["last_error"] = ""
                    state["next_action"] = "Review acceptance criteria and finish or continue."

        state["files"] = dict(list(files.items())[-MAX_FILE_RECORDS:])
        state["completed_steps"] = history[-MAX_EVENT_HISTORY:]
        state["pending_deletions"] = pending[-100:]
        if int(state.get("repeat_failure_count", 0)) >= 2:
            state["status"] = "blocked"
            state["next_action"] = "Repeated identical failure detected; user guidance is required."
        _write_state(state)
        return state


def completion_is_verified(state: dict[str, Any]) -> tuple[bool, str]:
    files = state.get("files") or {}
    python_changed = any(
        str(path).casefold().endswith(".py") and item.get("status") == "present"
        for path, item in files.items()
    )
    if state.get("pending_deletions"):
        return False, "Deletion approval is still pending."
    if python_changed and int(state.get("last_verification_seq", 0)) <= int(
        state.get("last_mutation_seq", 0)
    ):
        return False, "Python changes have not passed a test after the latest write."
    return True, "verified"


def mark_completed(task_id: str) -> tuple[dict[str, Any], bool, str]:
    with _LEDGER_LOCK:
        state = _read_state(task_id)
        if not state:
            raise ValueError("Task ID was not found")
        verified, reason = completion_is_verified(state)
        if verified:
            state["status"] = "completed"
            state["next_action"] = ""
            _append_event(task_id, {"action": "task_completed", "verified": True})
        else:
            state["next_action"] = reason
            _append_event(
                task_id,
                {"action": "completion_rejected", "verified": False, "reason": reason},
            )
        _write_state(state)
        return state, verified, reason


def format_mission_context(state: dict[str, Any], max_chars: int = 4300) -> str:
    compact = {
        "id": state.get("id"),
        "goal": state.get("goal"),
        "status": state.get("status"),
        "acceptance": list(state.get("acceptance_criteria") or [])[:6],
        "next_action": state.get("next_action"),
        "attempts": state.get("attempts", 0),
        "epoch": state.get("epochs", 0),
        "last_error": str(state.get("last_error", ""))[-1000:],
        "last_test": state.get("last_test") or {},
        "files": dict(list((state.get("files") or {}).items())[-40:]),
        "recent_verified_events": list(state.get("completed_steps") or [])[-10:],
        "pending_deletions": list(state.get("pending_deletions") or [])[-10:],
    }
    encoded = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
    while len(encoded) > max_chars and compact["recent_verified_events"]:
        compact["recent_verified_events"].pop(0)
        encoded = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
    while len(encoded) > max_chars and compact["files"]:
        oldest = next(iter(compact["files"]))
        compact["files"].pop(oldest)
        encoded = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
    if len(encoded) > max_chars:
        compact["last_error"] = str(compact["last_error"])[-300:]
        compact["goal"] = str(compact["goal"])[:800]
        compact["context_compacted"] = True
        encoded = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
    return "[VERIFIED MISSION LEDGER]\n" + encoded


def format_recent_verified_work(limit: int = 2, max_chars: int = 3600) -> str:
    """Return a compact, controller-owned record for cross-session recall.

    This deliberately excludes free-form chat summaries.  Every included file,
    test, and action comes from the executor-backed mission ledger, allowing a
    fresh model conversation to answer broad questions such as "what did we
    verify?" without guessing from semantically similar transcript fragments.
    """
    selected = [
        state
        for state in list_tasks(limit=20)
        if state.get("status") in {"completed", "active", "blocked", "paused"}
    ][: max(1, min(limit, 5))]
    if not selected:
        return ""

    records: list[dict[str, Any]] = []
    for state in selected:
        files = {
            str(path): {
                "status": item.get("status"),
                "sha256": item.get("hash"),
            }
            for path, item in (state.get("files") or {}).items()
            if isinstance(item, dict)
        }
        last_test = state.get("last_test") or {}
        verified_test = (
            {
                "path": last_test.get("path"),
                "exit_code": last_test.get("exit_code"),
                "output": str(last_test.get("result", ""))[:1200],
                "time": last_test.get("time"),
            }
            if last_test.get("exit_code") == 0
            else {}
        )
        verified_actions = [
            {
                "action": event.get("action"),
                "path": event.get("path"),
                "result": event.get("result"),
                "exit_code": event.get("exit_code"),
            }
            for event in list(state.get("completed_steps") or [])[-10:]
            if event.get("exit_code") in {None, 0}
        ]
        records.append(
            {
                "id": state.get("id"),
                "goal": state.get("goal"),
                "status": state.get("status"),
                "updated_at": state.get("updated_at"),
                "files": files,
                "verified_test": verified_test,
                "verified_actions": verified_actions,
            }
        )

    payload: dict[str, Any] = {
        "authority": "controller mission ledger",
        "records": records,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    while len(encoded) > max_chars and len(payload["records"]) > 1:
        payload["records"].pop()
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    while len(encoded) > max_chars and payload["records"][0]["verified_actions"]:
        payload["records"][0]["verified_actions"].pop(0)
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    if len(encoded) > max_chars:
        record = payload["records"][0]
        record["files"] = dict(list(record["files"].items())[-20:])
        record["goal"] = str(record.get("goal", ""))[:600]
        record["context_compacted"] = True
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        while len(encoded) > max_chars and record["files"]:
            record["files"].pop(next(iter(record["files"])))
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        if len(encoded) > max_chars and record["verified_test"]:
            record["verified_test"]["output"] = str(
                record["verified_test"].get("output", "")
            )[:240]
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        if len(encoded) > max_chars:
            record["goal"] = str(record.get("goal", ""))[:240]
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return "[RECENT VERIFIED WORK — CONTROLLER RECORD]\n" + encoded


ensure_workspace()
TASKS_DIR.mkdir(parents=True, exist_ok=True)


__all__ = [
    "TASK_SCHEMA_VERSION",
    "activate_task",
    "active_task",
    "completion_is_verified",
    "create_task",
    "format_mission_context",
    "get_or_create_task",
    "get_task",
    "increment_epoch",
    "list_tasks",
    "mark_completed",
    "pause_active_task",
    "record_events",
]
