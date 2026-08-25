"""Bounded maintenance coordinator used only while the cockpit is open and idle."""

from __future__ import annotations

import shutil
import threading
from datetime import datetime, timezone
from typing import Any

from memory_store import DEFAULT_DB, MemoryStore
from idle_reporter import generate_idle_report
from research_scout import cancel_research, ensure_policy, research_status, scan_research
from temporal_refresh import refresh_due_facts
from workspace_documenter import update_project_documentation
from workspace_state import META_DIR, WORKSPACE_DIR, atomic_write_json, read_json, utc_now


MAINTENANCE_STATE = META_DIR / "maintenance_state.json"
MINIMUM_FREE_BYTES = 8 * 1024 * 1024 * 1024


class IdleMaintenance:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._cancel = threading.Event()
        self._state = "idle"
        self._phase = ""
        self._last_result: dict[str, Any] = {}

    def cancel(self) -> None:
        self._cancel.set()
        cancel_research()

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {
                "state": self._state,
                "phase": self._phase,
                "last_result": self._last_result,
            }

    @staticmethod
    def _research_due(policy: dict[str, Any], force: bool) -> bool:
        if force:
            return True
        status = research_status()
        raw = status.get("last_scan")
        if not raw:
            return True
        try:
            last = datetime.fromisoformat(str(raw))
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
        except ValueError:
            return True
        elapsed = (datetime.now(timezone.utc) - last).total_seconds()
        return elapsed >= int(policy.get("minimum_interval_seconds", 21600))

    def run(self, *, force_research: bool = False) -> dict[str, Any]:
        with self._lock:
            if self._state == "running":
                return {"status": "already_running"}
            self._state = "running"
            self._phase = "documentation"
            self._cancel.clear()
        result: dict[str, Any] = {"started_at": utc_now()}
        try:
            result["documentation"] = update_project_documentation()
            if self._cancel.is_set():
                result["status"] = "cancelled"
                return result
            free_bytes = shutil.disk_usage(WORKSPACE_DIR).free
            result["free_bytes"] = free_bytes
            policy = ensure_policy()
            if free_bytes < MINIMUM_FREE_BYTES:
                result["temporal_refresh"] = {"status": "skipped_low_storage"}
                result["research"] = {"status": "skipped_low_storage"}
            else:
                store = MemoryStore(DEFAULT_DB)
                if store.get_setting("auto_fact_watch", "on").casefold() == "off":
                    result["temporal_refresh"] = {"status": "disabled"}
                else:
                    with self._lock:
                        self._phase = "freshness"
                    result["temporal_refresh"] = refresh_due_facts(
                        store,
                        cancel_event=self._cancel,
                        limit=2,
                    )
                if self._cancel.is_set():
                    result["status"] = "cancelled"
                    return result
                with self._lock:
                    self._phase = "reporting"
                result["report"] = generate_idle_report(
                    store,
                    cancel_event=self._cancel,
                )
                if self._cancel.is_set():
                    result["status"] = "cancelled"
                    return result
                if self._research_due(policy, force_research):
                    with self._lock:
                        self._phase = "research"
                    result["research"] = scan_research()
                else:
                    result["research"] = {"status": "not_due"}
            result["status"] = "cancelled" if self._cancel.is_set() else "complete"
            return result
        except Exception as exc:
            result["status"] = "error"
            result["error"] = f"{type(exc).__name__}: {exc}"
            return result
        finally:
            result["finished_at"] = utc_now()
            atomic_write_json(MAINTENANCE_STATE, result)
            with self._lock:
                self._last_result = result
                self._state = "idle"
                self._phase = ""


IDLE_MAINTENANCE = IdleMaintenance()


def run_idle_maintenance(*, force_research: bool = False) -> dict[str, Any]:
    return IDLE_MAINTENANCE.run(force_research=force_research)


def cancel_idle_maintenance() -> None:
    IDLE_MAINTENANCE.cancel()


def maintenance_status() -> dict[str, Any]:
    status = IDLE_MAINTENANCE.status()
    if not status["last_result"]:
        persisted = read_json(MAINTENANCE_STATE, {})
        if isinstance(persisted, dict):
            status["last_result"] = persisted
    return status


__all__ = [
    "IDLE_MAINTENANCE",
    "cancel_idle_maintenance",
    "maintenance_status",
    "run_idle_maintenance",
]
