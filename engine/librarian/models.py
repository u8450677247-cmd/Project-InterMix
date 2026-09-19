"""Typed, bounded contracts shared by Librarian storage and transport."""

from __future__ import annotations

import hashlib
import json
import re
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence


EVENT_SCHEMA_VERSION = 1
DELTA_SCHEMA_VERSION = 1
MAX_EVENT_BYTES = 64 * 1024
MAX_BATCH_EVENTS = 128
MAX_DELTA_BYTES = 128 * 1024
MAX_CONTEXT_TOKENS = 8_000
MAX_QUERY_CHARACTERS = 4_096

ACTORS = frozenset({"user", "assistant", "system", "tool", "runtime"})
NODE_ROLES = frozenset({"cortex", "librarian", "archive", "client", "builder"})
ATOM_CLASSES = frozenset(
    {
        "episode",
        "fact",
        "procedure",
        "constraint",
        "preference",
        "self_model",
        "user_model",
        "project_state",
        "hypothesis",
        "lesson",
    }
)
EPISTEMIC_TYPES = frozenset(
    {"user_stated", "observed", "tool_verified", "inferred", "model_synthesized", "imported"}
)
EVIDENCE_RELATIONS = frozenset({"source", "supports", "challenges", "verifies", "corrects"})
EDGE_RELATIONS = frozenset(
    {
        "supports",
        "contradicts",
        "supersedes",
        "derived_from",
        "caused_by",
        "part_of",
        "about",
        "depends_on",
        "goal_supports",
        "same_as",
        "related_to",
    }
)
FRICTION_TYPES = frozenset(
    {
        "contradiction",
        "uncertainty",
        "runtime_failure",
        "prediction_error",
        "goal_conflict",
        "missing_evidence",
        "behavior_regression",
    }
)
GOAL_STATUSES = frozenset({"active", "blocked", "paused", "completed", "abandoned"})

NODE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
KIND_RE = re.compile(r"^[a-z][a-z0-9._-]{0,63}$")
REF_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
STATE_KEY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$")
ULID_RE = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$", re.IGNORECASE)


class ValidationError(ValueError):
    """A remote or model-proposed value failed deterministic validation."""


def now_ms() -> int:
    return int(time.time() * 1000)


def canonical_json(value: Any) -> str:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as failure:
        raise ValidationError("value is not canonical JSON") from failure


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", "strict")).hexdigest()


def bounded_text(value: Any, field_name: str, maximum: int, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{field_name} must be text")
    if "\x00" in value:
        raise ValidationError(f"{field_name} contains a NUL character")
    clean = value.strip()
    if not clean and not allow_empty:
        raise ValidationError(f"{field_name} is required")
    if len(clean.encode("utf-8")) > maximum:
        raise ValidationError(f"{field_name} exceeds {maximum} UTF-8 bytes")
    return clean


def bounded_identifier(value: Any, field_name: str, pattern: re.Pattern[str] = REF_RE) -> str:
    clean = bounded_text(value, field_name, 192)
    if not pattern.fullmatch(clean):
        raise ValidationError(f"{field_name} has an invalid format")
    return clean


def bounded_number(value: Any, field_name: str, *, minimum: float = 0.0, maximum: float = 1.0) -> float:
    if isinstance(value, bool):
        raise ValidationError(f"{field_name} must be numeric")
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValidationError(f"{field_name} must be numeric") from failure
    if not minimum <= number <= maximum:
        raise ValidationError(f"{field_name} must be between {minimum} and {maximum}")
    return number


def validate_global_id(value: Any, field_name: str = "global_event_id") -> str:
    clean = bounded_text(value, field_name, 80)
    try:
        uuid.UUID(clean)
    except ValueError:
        if not ULID_RE.fullmatch(clean):
            raise ValidationError(f"{field_name} must be a UUID or ULID") from None
    return clean


def reject_unknown(mapping: Mapping[str, Any], allowed: set[str], field_name: str) -> None:
    unknown = sorted(set(mapping) - allowed)
    if unknown:
        raise ValidationError(f"{field_name} contains unsupported fields: {', '.join(unknown)}")


def _integer(value: Any, field_name: str, minimum: int = 0) -> int:
    if isinstance(value, bool):
        raise ValidationError(f"{field_name} must be an integer")
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, str) and re.fullmatch(r"[+-]?\d+", value.strip()):
        parsed = int(value)
    else:
        raise ValidationError(f"{field_name} must be an integer")
    if parsed < minimum:
        raise ValidationError(f"{field_name} must be at least {minimum}")
    return parsed


@dataclass(frozen=True)
class EventEnvelope:
    global_event_id: str
    node_id: str
    node_sequence: int
    source_time_ms: int
    kind: str
    actor: str
    content: str
    payload: Mapping[str, Any] = field(default_factory=dict)
    parent_ids: tuple[str, ...] = ()
    schema_version: int = EVENT_SCHEMA_VERSION
    receive_time_ms: int | None = None
    claimed_payload_hash: str | None = None

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any]) -> "EventEnvelope":
        if not isinstance(raw, Mapping):
            raise ValidationError("event must be an object")
        reject_unknown(
            raw,
            {
                "global_event_id",
                "global_id",
                "node_id",
                "origin_node_id",
                "node_sequence",
                "origin_seq",
                "source_time_ms",
                "source_time",
                "receive_time_ms",
                "receive_time",
                "kind",
                "actor",
                "content",
                "payload",
                "payload_hash",
                "parent_ids",
                "parent_event_ids",
                "schema_version",
            },
            "event",
        )
        global_id = raw.get("global_event_id", raw.get("global_id"))
        node_id = raw.get("node_id", raw.get("origin_node_id"))
        sequence = raw.get("node_sequence", raw.get("origin_seq"))
        source_time = raw.get("source_time_ms", raw.get("source_time"))
        receive_time = raw.get("receive_time_ms", raw.get("receive_time"))
        parents = raw.get("parent_ids", raw.get("parent_event_ids", []))
        if not isinstance(parents, Sequence) or isinstance(parents, (str, bytes)):
            raise ValidationError("parent_ids must be a list")
        if len(parents) > 32:
            raise ValidationError("parent_ids exceeds 32 entries")
        payload = raw.get("payload", {})
        if not isinstance(payload, Mapping):
            raise ValidationError("payload must be an object")
        envelope = cls(
            global_event_id=validate_global_id(global_id),
            node_id=bounded_identifier(node_id, "node_id", NODE_ID_RE),
            node_sequence=_integer(sequence, "node_sequence", 1),
            source_time_ms=_integer(source_time, "source_time_ms", 0),
            receive_time_ms=None if receive_time is None else _integer(receive_time, "receive_time_ms", 0),
            kind=bounded_identifier(raw.get("kind"), "kind", KIND_RE),
            actor=bounded_text(raw.get("actor"), "actor", 16).casefold(),
            content=bounded_text(raw.get("content", ""), "content", MAX_EVENT_BYTES, allow_empty=True),
            payload=dict(payload),
            parent_ids=tuple(validate_global_id(item, "parent_id") for item in parents),
            schema_version=_integer(raw.get("schema_version", EVENT_SCHEMA_VERSION), "schema_version", 1),
            claimed_payload_hash=(
                bounded_text(raw["payload_hash"], "payload_hash", 64).casefold()
                if raw.get("payload_hash") is not None
                else None
            ),
        )
        envelope.validate()
        return envelope

    def validate(self) -> None:
        if self.actor not in ACTORS:
            raise ValidationError(f"actor must be one of {', '.join(sorted(ACTORS))}")
        if self.schema_version != EVENT_SCHEMA_VERSION:
            raise ValidationError(
                f"unsupported event schema {self.schema_version}; expected {EVENT_SCHEMA_VERSION}"
            )
        raw_size = len(self.content.encode("utf-8")) + len(canonical_json(self.payload).encode("utf-8"))
        if raw_size > MAX_EVENT_BYTES:
            raise ValidationError(f"event content and payload exceed {MAX_EVENT_BYTES} bytes")
        expected = self.payload_hash
        if self.claimed_payload_hash is not None:
            if not re.fullmatch(r"[0-9a-f]{64}", self.claimed_payload_hash):
                raise ValidationError("payload_hash must be one lowercase SHA-256 digest")
            if self.claimed_payload_hash != expected:
                raise ValidationError("payload_hash does not match canonical event bytes")

    @property
    def canonical_payload(self) -> dict[str, Any]:
        return {
            "actor": self.actor,
            "content": self.content,
            "global_event_id": self.global_event_id,
            "kind": self.kind,
            "node_id": self.node_id,
            "node_sequence": self.node_sequence,
            "parent_ids": list(self.parent_ids),
            "payload": self.payload,
            "schema_version": self.schema_version,
            "source_time_ms": self.source_time_ms,
        }

    @property
    def payload_hash(self) -> str:
        return sha256_text(canonical_json(self.canonical_payload))

    def as_mapping(self) -> dict[str, Any]:
        result = dict(self.canonical_payload)
        result["payload_hash"] = self.payload_hash
        if self.receive_time_ms is not None:
            result["receive_time_ms"] = self.receive_time_ms
        return result


@dataclass(frozen=True)
class IngestResult:
    status: str
    global_event_id: str
    event_id: int | None
    committed: bool
    conflict_id: int | None = None

    def as_mapping(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "global_event_id": self.global_event_id,
            "event_id": self.event_id,
            "committed": self.committed,
            "conflict_id": self.conflict_id,
        }
