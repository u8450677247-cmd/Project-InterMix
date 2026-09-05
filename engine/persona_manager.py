"""Inspectable, reversible evolution of explicit communication preferences."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from memory_store import MemoryStore, utc_now


PERSONA_PROFILE_KEY = "persona_profile_v1"
PERSONA_CAPTURE_KEY = "persona_auto_capture"
MAX_PREFERENCES = 16

_EXPLICIT_STYLE = re.compile(
    r"\b(?:"
    r"i\s+prefer\s+(?:you|your|answers?|responses?|the\s+assistant)\b"
    r"|please\s+(?:answer|respond|write|format|speak|call\s+me)\b"
    r"|(?:do\s+not|don['’]t)\s+(?:call\s+me|be\s+so|use|write|respond)\b"
    r"|keep\s+(?:answers?|responses?)\s+(?:short|brief|detailed|concise)\b"
    r")",
    flags=re.IGNORECASE,
)


@dataclass(frozen=True)
class PersonaRevision:
    revision_id: int
    reason: str
    created_at: str
    undone: bool


def _default_profile() -> dict[str, Any]:
    return {"schema": 1, "communication_preferences": []}


def current_persona(store: MemoryStore) -> dict[str, Any]:
    raw = store.get_setting(PERSONA_PROFILE_KEY, "")
    try:
        profile = json.loads(raw) if raw else _default_profile()
    except (TypeError, ValueError):
        profile = _default_profile()
    if not isinstance(profile, dict):
        profile = _default_profile()
    preferences = profile.get("communication_preferences", [])
    if not isinstance(preferences, list):
        preferences = []
    return {
        "schema": 1,
        "communication_preferences": [
            str(item).strip()[:360]
            for item in preferences[:MAX_PREFERENCES]
            if str(item).strip()
        ],
    }


def evolve_persona(
    store: MemoryStore,
    preference: str,
    *,
    reason: str,
    source_message_id: int | None = None,
) -> int | None:
    clean = re.sub(r"\s+", " ", preference.replace("\x00", " ")).strip()[:360]
    if not clean:
        return None
    before = current_persona(store)
    existing = before["communication_preferences"]
    if any(item.casefold() == clean.casefold() for item in existing):
        return None
    after = {
        "schema": 1,
        "communication_preferences": [*existing, clean][-MAX_PREFERENCES:],
    }
    before_json = json.dumps(before, ensure_ascii=False, sort_keys=True)
    after_json = json.dumps(after, ensure_ascii=False, sort_keys=True)
    now = utc_now()
    with store.connection() as db:
        db.execute(
            "INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
            (PERSONA_PROFILE_KEY, after_json, now),
        )
        cursor = db.execute(
            "INSERT INTO persona_revisions(before_json,after_json,reason,source_message_id,created_at) "
            "VALUES(?,?,?,?,?)",
            (before_json, after_json, reason[:240], source_message_id, now),
        )
        revision_id = int(cursor.lastrowid)
    return revision_id


def capture_explicit_persona(
    store: MemoryStore,
    text: str,
    *,
    source_message_id: int,
) -> list[int]:
    """Capture only explicit instructions about how the assistant should communicate."""
    if store.get_setting(PERSONA_CAPTURE_KEY, "on").casefold() == "off":
        return []
    clean = text.replace("\x00", " ")
    sentences = re.split(r"(?<=[.!?])\s+|\n+", clean)
    revisions: list[int] = []
    for sentence in sentences[:24]:
        candidate = re.sub(r"\s+", " ", sentence).strip()
        if not candidate or not _EXPLICIT_STYLE.search(candidate):
            continue
        revision_id = evolve_persona(
            store,
            candidate,
            reason="explicit user communication preference",
            source_message_id=source_message_id,
        )
        if revision_id is not None:
            revisions.append(revision_id)
        if len(revisions) >= 4:
            break
    return revisions


def undo_persona(store: MemoryStore) -> PersonaRevision | None:
    now = utc_now()
    with store.connection() as db:
        row = db.execute(
            "SELECT * FROM persona_revisions WHERE undone_at IS NULL ORDER BY id DESC LIMIT 1"
        ).fetchone()
        if not row:
            return None
        before_json = str(row["before_json"])
        db.execute(
            "INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
            (PERSONA_PROFILE_KEY, before_json, now),
        )
        db.execute("UPDATE persona_revisions SET undone_at=? WHERE id=?", (now, row["id"]))
    return PersonaRevision(int(row["id"]), str(row["reason"]), str(row["created_at"]), True)


def persona_history(store: MemoryStore, limit: int = 20) -> list[PersonaRevision]:
    with store.connection(readonly=True) as db:
        rows = db.execute(
            "SELECT id,reason,created_at,undone_at FROM persona_revisions "
            "ORDER BY id DESC LIMIT ?",
            (max(1, min(limit, 100)),),
        ).fetchall()
    return [
        PersonaRevision(
            revision_id=int(row["id"]),
            reason=str(row["reason"]),
            created_at=str(row["created_at"]),
            undone=bool(row["undone_at"]),
        )
        for row in rows
    ]


def persona_prompt_block(store: MemoryStore) -> str:
    preferences = current_persona(store)["communication_preferences"]
    if not preferences:
        return ""
    lines = "\n".join(f"- {item}" for item in preferences[-10:])
    return (
        "[REVERSIBLE PERSONA PREFERENCES]\n"
        "These are explicit communication preferences, not psychological diagnoses or immutable identity facts.\n"
        + lines
    )


__all__ = [
    "PERSONA_CAPTURE_KEY",
    "PersonaRevision",
    "capture_explicit_persona",
    "current_persona",
    "evolve_persona",
    "persona_history",
    "persona_prompt_block",
    "undo_persona",
]
