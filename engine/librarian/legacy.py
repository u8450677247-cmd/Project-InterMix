"""Idempotent projection of existing Intermix memory into the lattice.

The original tables remain authoritative historical data and are never deleted or
rewritten.  Projection creates globally identified events/atoms beside them.
"""

from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from typing import Any

from .models import EventEnvelope, now_ms
from .store import CONTINUITY_NAMESPACE, ContinuityStore


LEGACY_NODE_ID = "legacy-local"


def _timestamp_ms(value: Any) -> int:
    text = str(value or "").strip()
    if not text:
        return now_ms()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return now_ms()


def _class_for(kind: str) -> str:
    normalized = kind.casefold()
    if "preference" in normalized:
        return "preference"
    if "constraint" in normalized:
        return "constraint"
    if "procedure" in normalized or "workflow" in normalized:
        return "procedure"
    if "project" in normalized:
        return "project_state"
    if "hypothesis" in normalized:
        return "hypothesis"
    if "lesson" in normalized:
        return "lesson"
    if "user" in normalized:
        return "user_model"
    if "self" in normalized or "persona" in normalized:
        return "self_model"
    if "episode" in normalized:
        return "episode"
    return "fact"


def project_existing_memory(store: ContinuityStore) -> dict[str, int]:
    store.register_node(
        LEGACY_NODE_ID,
        "client",
        "Local pre-Lattice history",
        platform="intermix-memory-v3",
        capabilities={"projection_only": True},
    )
    with store.connection() as database:
        tables = {
            str(row[0])
            for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        messages = (
            database.execute(
                """
                SELECT id,session_id,role,speaker,content,created_at,source,metadata_json
                FROM messages ORDER BY id
                """
            ).fetchall()
            if "messages" in tables
            else []
        )
        memories = (
            database.execute(
                """
                SELECT id,kind,memory_key,value,source_message_id,confidence,salience,
                       explicitly_stated,active,created_at,updated_at
                FROM memories ORDER BY id
                """
            ).fetchall()
            if "memories" in tables
            else []
        )

    message_events: dict[int, str] = {}
    maximum_message_id = max((int(row["id"]) for row in messages), default=0)
    events_committed = 0
    events_duplicate = 0
    for row in messages:
        message_id = int(row["id"])
        global_id = str(
            uuid.uuid5(
                CONTINUITY_NAMESPACE,
                f"legacy-message:{row['session_id']}:{message_id}",
            )
        )
        role = str(row["role"] or "runtime").casefold()
        actor = role if role in {"user", "assistant", "system", "tool"} else "runtime"
        envelope = EventEnvelope.from_mapping(
            {
                "global_event_id": global_id,
                "node_id": LEGACY_NODE_ID,
                "node_sequence": message_id,
                "source_time_ms": _timestamp_ms(row["created_at"]),
                "kind": "legacy.message",
                "actor": actor,
                "content": str(row["content"] or "")[: 64 * 1024],
                "payload": {
                    "legacy_message_id": message_id,
                    "session_id": str(row["session_id"] or ""),
                    "speaker": str(row["speaker"] or ""),
                    "source": str(row["source"] or ""),
                },
                "schema_version": 1,
            }
        )
        result = store.ingest_event(envelope)
        events_committed += int(result.committed)
        events_duplicate += int(result.status == "duplicate")
        message_events[message_id] = global_id

    atoms_projected = 0
    atoms_inactive = 0
    for row in memories:
        memory_id = int(row["id"])
        source_message_id = row["source_message_id"]
        source_global_id = message_events.get(int(source_message_id)) if source_message_id else None
        if source_global_id is None:
            source_global_id = str(
                uuid.uuid5(CONTINUITY_NAMESPACE, f"legacy-memory-evidence:{memory_id}")
            )
            evidence_event = EventEnvelope.from_mapping(
                {
                    "global_event_id": source_global_id,
                    "node_id": LEGACY_NODE_ID,
                    "node_sequence": maximum_message_id + memory_id,
                    "source_time_ms": _timestamp_ms(row["created_at"]),
                    "kind": "legacy.memory_import",
                    "actor": "system",
                    "content": "Existing durable memory projected into the Continuity Lattice.",
                    "payload": {"legacy_memory_id": memory_id},
                    "schema_version": 1,
                }
            )
            result = store.ingest_event(evidence_event)
            events_committed += int(result.committed)
            events_duplicate += int(result.status == "duplicate")

        client_id = f"legacy-memory-{memory_id}"
        delta = {
            "schema_version": 1,
            "atoms": [
                {
                    "client_id": client_id,
                    "class": _class_for(str(row["kind"] or "fact")),
                    "domain": "legacy",
                    "subject": str(row["memory_key"] or "")[:512] or None,
                    "predicate": str(row["kind"] or "fact")[:192],
                    "object_text": str(row["value"] or "")[:4096] or None,
                    "canonical_text": str(row["value"] or "")[: 16 * 1024],
                    "epistemic": "user_stated" if int(row["explicitly_stated"] or 0) else "imported",
                    "confidence": float(row["confidence"] or 0.5),
                    "salience": float(row["salience"] or 0.5),
                    "stability": 0.75,
                    "novelty": 0.5,
                    "utility": float(row["salience"] or 0.5),
                }
            ],
            "evidence": [
                {
                    "atom_ref": client_id,
                    "event_global_id": source_global_id,
                    "relation": "source",
                    "weight": 1.0,
                }
            ],
        }
        result = store.apply_memory_delta(
            source_event_global_id=source_global_id,
            actor_node_id=LEGACY_NODE_ID,
            delta=delta,
        )
        atoms_projected += int(result["atoms"])
        if not int(row["active"] or 0):
            atom_global_id = result["atom_global_ids"][client_id]
            with store.connection(write=True) as database:
                database.execute(
                    "UPDATE memory_atom SET status='superseded' WHERE global_id=?",
                    (atom_global_id,),
                )
            atoms_inactive += 1

    return {
        "events_committed": events_committed,
        "events_duplicate": events_duplicate,
        "messages_seen": len(messages),
        "memories_seen": len(memories),
        "atoms_projected": atoms_projected,
        "atoms_inactive": atoms_inactive,
    }
