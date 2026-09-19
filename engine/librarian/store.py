"""Single-authority SQLite implementation for LIBRARIAN-01."""

from __future__ import annotations

import json
import math
import os
import re
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

from .models import (
    ATOM_CLASSES,
    DELTA_SCHEMA_VERSION,
    EDGE_RELATIONS,
    EPISTEMIC_TYPES,
    EVIDENCE_RELATIONS,
    FRICTION_TYPES,
    GOAL_STATUSES,
    KIND_RE,
    MAX_CONTEXT_TOKENS,
    MAX_DELTA_BYTES,
    MAX_QUERY_CHARACTERS,
    NODE_ID_RE,
    NODE_ROLES,
    REF_RE,
    STATE_KEY_RE,
    EventEnvelope,
    IngestResult,
    ValidationError,
    bounded_identifier,
    bounded_number,
    bounded_text,
    canonical_json,
    now_ms,
    reject_unknown,
    sha256_text,
    validate_global_id,
)


LATTICE_SCHEMA_VERSION = "0.3.0-ordinary-service"
LATTICE_USER_VERSION = 300
CONTINUITY_NAMESPACE = uuid.UUID("fdf95895-0be1-4d83-b8b4-c86d10767f9b")
DEFAULT_LIBRARIAN_NODE = "librarian-01"
MAX_LIST_ITEMS = 128

JOB_KINDS = frozenset(
    {
        "embed_atom",
        "fts_maintenance",
        "deduplicate_candidates",
        "cluster_episode",
        "detect_contradiction",
        "hash_object",
        "snapshot_db",
        "nas_push",
        "nas_verify",
        "integrity_check",
        "release_check",
        "release_verify",
        "retention_gc",
        "context_compile",
        "llm_consolidate",
        "llm_resolve_friction",
    }
)


def _fts_query(text: str) -> str:
    terms: list[str] = []
    seen: set[str] = set()
    for term in re.findall(r"[\w-]+", text.casefold(), flags=re.UNICODE):
        term = term.strip("-_")
        if len(term) < 2 or term in seen:
            continue
        seen.add(term)
        terms.append(term.replace('"', '""'))
        if len(terms) >= 14:
            break
    return " OR ".join(f'"{term}"' for term in terms)


def _estimate_tokens(value: Any) -> int:
    return max(1, math.ceil(len(canonical_json(value).encode("utf-8")) / 3.2))


def _as_list(value: Any, field_name: str, maximum: int = MAX_LIST_ITEMS) -> list[Any]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValidationError(f"{field_name} must be a list")
    if len(value) > maximum:
        raise ValidationError(f"{field_name} exceeds {maximum} entries")
    return value


def _optional_text(value: Any, field_name: str, maximum: int) -> str | None:
    if value is None:
        return None
    return bounded_text(value, field_name, maximum, allow_empty=True) or None


def _optional_int(value: Any, field_name: str, minimum: int = 0) -> int | None:
    if value is None:
        return None
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


def _optional_float(
    value: Any,
    field_name: str,
    *,
    minimum: float = -273.0,
    maximum: float = 10_000.0,
) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValidationError(f"{field_name} must be numeric")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as failure:
        raise ValidationError(f"{field_name} must be numeric") from failure
    if not math.isfinite(parsed) or not minimum <= parsed <= maximum:
        raise ValidationError(f"{field_name} must be between {minimum} and {maximum}")
    return parsed


def apply_transactional_migration(db_path: str | os.PathLike[str], migration_sql: str) -> None:
    """Apply a migration atomically; exposed so rollback behavior is testable."""

    database = sqlite3.connect(str(db_path), timeout=30, isolation_level=None)
    try:
        database.execute("PRAGMA foreign_keys = ON")
        database.execute("PRAGMA busy_timeout = 30000")
        database.executescript(f"BEGIN IMMEDIATE;\n{migration_sql}\nCOMMIT;")
    except Exception:
        if database.in_transaction:
            database.execute("ROLLBACK")
        raise
    finally:
        database.close()


class ContinuityStore:
    """Additive Continuity Lattice over an existing Intermix SQLite path."""

    def __init__(
        self,
        db_path: str | os.PathLike[str],
        *,
        node_id: str = DEFAULT_LIBRARIAN_NODE,
        initialize: bool = True,
    ) -> None:
        self.db_path = str(Path(db_path).expanduser().resolve(strict=False))
        self.node_id = bounded_identifier(node_id, "node_id", NODE_ID_RE)
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._write_lock = threading.RLock()
        if initialize:
            self.initialize()

    def _connect(self, *, readonly: bool = False) -> sqlite3.Connection:
        if readonly:
            uri = Path(self.db_path).resolve().as_uri() + "?mode=ro"
            database = sqlite3.connect(uri, uri=True, timeout=30)
        else:
            database = sqlite3.connect(self.db_path, timeout=30)
        database.row_factory = sqlite3.Row
        database.execute("PRAGMA foreign_keys = ON")
        database.execute("PRAGMA busy_timeout = 30000")
        if not readonly:
            database.execute("PRAGMA synchronous = FULL")
        return database

    @contextmanager
    def connection(self, *, write: bool = False) -> Iterator[sqlite3.Connection]:
        lock = self._write_lock if write else _NullLock()
        with lock:
            database = self._connect(readonly=not write)
            try:
                if write:
                    database.execute("BEGIN IMMEDIATE")
                yield database
                if write:
                    database.commit()
            except Exception:
                if write:
                    database.rollback()
                raise
            finally:
                database.close()

    def initialize(self) -> None:
        with self._write_lock:
            probe = sqlite3.connect(self.db_path, timeout=30)
            try:
                probe.execute("PRAGMA journal_mode = WAL")
                probe.execute("PRAGMA synchronous = FULL")
                alterations = self._legacy_upgrade_sql(probe)
            finally:
                probe.close()

            schema_path = Path(__file__).with_name("schema.sql")
            schema = schema_path.read_text(encoding="utf-8")
            metadata = f"""
                INSERT INTO lattice_meta(key,value) VALUES('schema_name','Project Intermix Continuity Lattice')
                ON CONFLICT(key) DO UPDATE SET value=excluded.value;
                INSERT INTO lattice_meta(key,value) VALUES('schema_version','{LATTICE_SCHEMA_VERSION}')
                ON CONFLICT(key) DO UPDATE SET value=excluded.value;
                PRAGMA user_version = {LATTICE_USER_VERSION};
            """
            apply_transactional_migration(self.db_path, alterations + "\n" + schema + "\n" + metadata)
            self.register_node(
                self.node_id,
                "librarian",
                "LIBRARIAN-01",
                platform="python-ordinary-service",
                capabilities={
                    "events": True,
                    "fts": True,
                    "snapshots": True,
                    "embeddings_required": False,
                },
            )

    @staticmethod
    def _legacy_upgrade_sql(database: sqlite3.Connection) -> str:
        """Make supplied v0.1/v0.2 SQL artifacts safely repeatable."""

        table_names = {
            str(row[0])
            for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        upgrades: dict[str, list[tuple[str, str]]] = {
            "event_log": [
                ("global_id", "TEXT"),
                ("origin_node_id", "TEXT"),
                ("origin_seq", "INTEGER"),
                ("source_time_ms", "INTEGER"),
                ("receive_time_ms", "INTEGER"),
                ("payload_json", "TEXT NOT NULL DEFAULT '{}'"),
                ("payload_sha256", "TEXT"),
                ("parent_global_ids_json", "TEXT NOT NULL DEFAULT '[]'"),
                ("schema_version", "INTEGER NOT NULL DEFAULT 1"),
            ],
            "node_heartbeat": [
                ("last_request_id", "TEXT"),
                ("last_payload_sha256", "TEXT"),
                ("schema_version", "INTEGER NOT NULL DEFAULT 1"),
            ],
            "memory_atom": [
                ("global_id", "TEXT"),
                ("origin_node_id", "TEXT"),
            ],
            "friction_event": [
                ("global_id", "TEXT"),
                ("logical_key", "TEXT"),
            ],
            "goal": [
                ("global_id", "TEXT"),
                ("source_atom_id", "INTEGER REFERENCES memory_atom(id)"),
            ],
            "state_register": [
                ("source_event_id", "INTEGER REFERENCES event_log(id)"),
            ],
            "replication_outbox": [
                ("payload_json", "TEXT NOT NULL DEFAULT '{}'"),
            ],
            "snapshot_catalog": [
                ("request_id", "TEXT"),
                ("generation", "INTEGER"),
                ("lattice_schema_version", "TEXT"),
                ("parent_snapshot_hash", "TEXT"),
                ("manifest_sha256", "TEXT"),
                ("manifest_path", "TEXT"),
            ],
        }
        statements: list[str] = []
        for table, columns in upgrades.items():
            if table not in table_names:
                continue
            existing = {
                str(row[1]) for row in database.execute(f'PRAGMA table_info("{table}")')
            }
            for name, declaration in columns:
                if name not in existing:
                    statements.append(f'ALTER TABLE "{table}" ADD COLUMN "{name}" {declaration};')
        return "\n".join(statements)

    def schema_version(self) -> str:
        with self.connection() as database:
            row = database.execute(
                "SELECT value FROM lattice_meta WHERE key='schema_version'"
            ).fetchone()
        return str(row[0]) if row else "missing"

    def register_node(
        self,
        node_id: str,
        role: str,
        display_name: str,
        *,
        platform: str = "",
        capabilities: Mapping[str, Any] | None = None,
        public_key: str | None = None,
        enabled: bool = True,
    ) -> None:
        node_id = bounded_identifier(node_id, "node_id", NODE_ID_RE)
        role = bounded_text(role, "node_role", 32).casefold()
        if role not in NODE_ROLES:
            raise ValidationError(f"node_role must be one of {', '.join(sorted(NODE_ROLES))}")
        display_name = bounded_text(display_name, "display_name", 120)
        platform = bounded_text(platform, "platform", 120, allow_empty=True)
        capabilities_json = canonical_json(dict(capabilities or {}))
        if len(capabilities_json.encode("utf-8")) > 16 * 1024:
            raise ValidationError("capabilities exceed 16384 bytes")
        public_key = _optional_text(public_key, "public_key", 4096)
        timestamp = now_ms()
        with self.connection(write=True) as database:
            existing = database.execute(
                "SELECT node_role FROM node_registry WHERE node_id=?", (node_id,)
            ).fetchone()
            if existing and str(existing[0]) != role:
                raise ValidationError("an existing node role cannot be silently changed")
            database.execute(
                """
                INSERT INTO node_registry(
                    node_id,node_role,display_name,platform,capabilities_json,public_key,
                    first_seen_ms,last_seen_ms,enabled
                ) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(node_id) DO UPDATE SET
                    display_name=excluded.display_name,
                    platform=excluded.platform,
                    capabilities_json=excluded.capabilities_json,
                    public_key=COALESCE(excluded.public_key,node_registry.public_key),
                    last_seen_ms=excluded.last_seen_ms,
                    enabled=excluded.enabled
                """,
                (
                    node_id,
                    role,
                    display_name,
                    platform,
                    capabilities_json,
                    public_key,
                    timestamp,
                    timestamp,
                    int(enabled),
                ),
            )
            database.execute(
                "INSERT OR IGNORE INTO node_sequence(node_id,next_event_seq,updated_at_ms) VALUES(?,1,?)",
                (node_id, timestamp),
            )

    def node(self, node_id: str) -> dict[str, Any] | None:
        node_id = bounded_identifier(node_id, "node_id", NODE_ID_RE)
        with self.connection() as database:
            row = database.execute("SELECT * FROM node_registry WHERE node_id=?", (node_id,)).fetchone()
        if not row:
            return None
        result = dict(row)
        result["capabilities"] = json.loads(result.pop("capabilities_json"))
        result["enabled"] = bool(result["enabled"])
        return result

    def allocate_sequence(self, node_id: str) -> int:
        node_id = bounded_identifier(node_id, "node_id", NODE_ID_RE)
        timestamp = now_ms()
        with self.connection(write=True) as database:
            row = database.execute(
                "SELECT next_event_seq FROM node_sequence WHERE node_id=?", (node_id,)
            ).fetchone()
            if not row:
                raise ValidationError("node is not registered")
            sequence = int(row[0])
            database.execute(
                "UPDATE node_sequence SET next_event_seq=?,updated_at_ms=? WHERE node_id=?",
                (sequence + 1, timestamp, node_id),
            )
        return sequence

    def emit_event(
        self,
        *,
        node_id: str,
        actor: str,
        kind: str,
        content: str,
        payload: Mapping[str, Any] | None = None,
        parent_ids: Sequence[str] = (),
        source_time_ms: int | None = None,
    ) -> tuple[EventEnvelope, IngestResult]:
        sequence = self.allocate_sequence(node_id)
        envelope = EventEnvelope.from_mapping(
            {
                "global_event_id": str(uuid.uuid4()),
                "node_id": node_id,
                "node_sequence": sequence,
                "source_time_ms": source_time_ms if source_time_ms is not None else now_ms(),
                "actor": actor,
                "kind": kind,
                "content": content,
                "payload": dict(payload or {}),
                "parent_ids": list(parent_ids),
                "schema_version": 1,
            }
        )
        return envelope, self.ingest_event(envelope)

    def ingest_event(self, event: EventEnvelope | Mapping[str, Any]) -> IngestResult:
        envelope = event if isinstance(event, EventEnvelope) else EventEnvelope.from_mapping(event)
        envelope.validate()
        # Receipt order belongs to the authority. A remote envelope may carry an
        # informational receive time from another replica, but cannot choose ours.
        received = now_ms()
        with self.connection(write=True) as database:
            node = database.execute(
                "SELECT enabled FROM node_registry WHERE node_id=?", (envelope.node_id,)
            ).fetchone()
            if not node or not int(node[0]):
                raise ValidationError("event origin is not a registered enabled node")

            existing = database.execute(
                "SELECT id,payload_sha256 FROM event_log WHERE global_id=?",
                (envelope.global_event_id,),
            ).fetchone()
            if existing:
                if str(existing[1]) == envelope.payload_hash:
                    return IngestResult(
                        "duplicate", envelope.global_event_id, int(existing[0]), False
                    )
                conflict_id = self._insert_replication_conflict(
                    database,
                    object_type="event",
                    logical_key=f"global:{envelope.global_event_id}",
                    local_global_id=envelope.global_event_id,
                    remote_global_id=envelope.global_event_id,
                    description="The same global event ID arrived with different canonical bytes.",
                )
                return IngestResult(
                    "conflict", envelope.global_event_id, int(existing[0]), False, conflict_id
                )

            sequence_owner = database.execute(
                "SELECT id,global_id,payload_sha256 FROM event_log "
                "WHERE origin_node_id=? AND origin_seq=?",
                (envelope.node_id, envelope.node_sequence),
            ).fetchone()
            if sequence_owner:
                conflict_id = self._insert_replication_conflict(
                    database,
                    object_type="event",
                    logical_key=f"sequence:{envelope.node_id}:{envelope.node_sequence}",
                    local_global_id=str(sequence_owner[1]),
                    remote_global_id=envelope.global_event_id,
                    description="One origin sequence was reused for a different event.",
                )
                return IngestResult(
                    "conflict", envelope.global_event_id, int(sequence_owner[0]), False, conflict_id
                )

            cursor = database.execute(
                """
                INSERT INTO event_log(
                    global_id,origin_node_id,origin_seq,ts_ms,source_time_ms,receive_time_ms,
                    session_id,actor,kind,content,payload_json,payload_sha256,
                    parent_global_ids_json,schema_version
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    envelope.global_event_id,
                    envelope.node_id,
                    envelope.node_sequence,
                    envelope.source_time_ms,
                    envelope.source_time_ms,
                    received,
                    str(envelope.payload.get("session_id", ""))[:160],
                    envelope.actor,
                    envelope.kind,
                    envelope.content,
                    canonical_json(envelope.payload),
                    envelope.payload_hash,
                    canonical_json(list(envelope.parent_ids)),
                    envelope.schema_version,
                ),
            )
            database.execute(
                "UPDATE node_registry SET last_seen_ms=? WHERE node_id=?",
                (received, envelope.node_id),
            )
            database.execute(
                """
                INSERT INTO node_sequence(node_id,next_event_seq,updated_at_ms)
                VALUES(?,?,?)
                ON CONFLICT(node_id) DO UPDATE SET
                  next_event_seq=MAX(node_sequence.next_event_seq,excluded.next_event_seq),
                  updated_at_ms=excluded.updated_at_ms
                """,
                (envelope.node_id, envelope.node_sequence + 1, received),
            )
            return IngestResult(
                "committed", envelope.global_event_id, int(cursor.lastrowid), True
            )

    def ingest_batch(self, events: Sequence[Mapping[str, Any]]) -> list[IngestResult]:
        if not isinstance(events, Sequence) or isinstance(events, (str, bytes)):
            raise ValidationError("events must be a list")
        if len(events) > 128:
            raise ValidationError("event batch exceeds 128 entries")
        envelopes = [EventEnvelope.from_mapping(item) for item in events]
        return [self.ingest_event(item) for item in envelopes]

    @staticmethod
    def _insert_replication_conflict(
        database: sqlite3.Connection,
        *,
        object_type: str,
        logical_key: str,
        local_global_id: str | None,
        remote_global_id: str | None,
        description: str,
    ) -> int:
        cursor = database.execute(
            """
            INSERT INTO replication_conflict(
                created_at_ms,object_type,logical_key,local_global_id,
                remote_global_id,description
            ) VALUES(?,?,?,?,?,?)
            """,
            (
                now_ms(),
                object_type,
                logical_key,
                local_global_id,
                remote_global_id,
                description,
            ),
        )
        return int(cursor.lastrowid)

    def event(self, global_event_id: str) -> dict[str, Any] | None:
        global_event_id = validate_global_id(global_event_id)
        with self.connection() as database:
            row = database.execute(
                "SELECT * FROM event_log WHERE global_id=?", (global_event_id,)
            ).fetchone()
        return self._event_row(row) if row else None

    @staticmethod
    def _event_row(row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        result["payload"] = json.loads(result.pop("payload_json") or "{}")
        result["parent_ids"] = json.loads(result.pop("parent_global_ids_json") or "[]")
        return result

    def apply_memory_delta(
        self,
        *,
        source_event_global_id: str,
        actor_node_id: str,
        delta: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Validate and atomically commit a model-proposed structured delta."""

        source_event_global_id = validate_global_id(source_event_global_id)
        actor_node_id = bounded_identifier(actor_node_id, "actor_node_id", NODE_ID_RE)
        if not isinstance(delta, Mapping):
            raise ValidationError("memory delta must be an object")
        encoded = canonical_json(delta)
        if len(encoded.encode("utf-8")) > MAX_DELTA_BYTES:
            raise ValidationError(f"memory delta exceeds {MAX_DELTA_BYTES} bytes")
        reject_unknown(
            delta,
            {
                "schema_version",
                "atoms",
                "evidence",
                "edges",
                "frictions",
                "state_updates",
                "goal_updates",
            },
            "memory delta",
        )
        schema_version = _optional_int(delta.get("schema_version", 1), "schema_version", 1)
        if schema_version != DELTA_SCHEMA_VERSION:
            raise ValidationError(
                f"unsupported memory delta schema {schema_version}; expected {DELTA_SCHEMA_VERSION}"
            )
        atoms = _as_list(delta.get("atoms"), "atoms", 64)
        evidence = _as_list(delta.get("evidence"), "evidence", 256)
        edges = _as_list(delta.get("edges"), "edges", 256)
        frictions = _as_list(delta.get("frictions"), "frictions", 64)
        state_updates = _as_list(delta.get("state_updates"), "state_updates", 64)
        goal_updates = _as_list(delta.get("goal_updates"), "goal_updates", 64)

        with self.connection(write=True) as database:
            source_event = database.execute(
                "SELECT id,origin_node_id FROM event_log WHERE global_id=?",
                (source_event_global_id,),
            ).fetchone()
            if not source_event:
                raise ValidationError("source event is not committed")
            actor = database.execute(
                "SELECT node_role,enabled FROM node_registry WHERE node_id=?", (actor_node_id,)
            ).fetchone()
            if not actor or not int(actor[1]):
                raise ValidationError("actor node is not registered and enabled")
            if actor_node_id != str(source_event[1]) and str(actor[0]) != "librarian":
                raise ValidationError("actor node cannot mutate continuity for another node's event")

            source_event_id = int(source_event[0])
            timestamp = now_ms()
            atom_refs: dict[str, int] = {}
            atom_globals: dict[str, str] = {}
            for raw in atoms:
                if not isinstance(raw, Mapping):
                    raise ValidationError("each atom must be an object")
                reject_unknown(
                    raw,
                    {
                        "client_id",
                        "class",
                        "domain",
                        "subject",
                        "predicate",
                        "object_text",
                        "canonical_text",
                        "epistemic",
                        "confidence",
                        "salience",
                        "stability",
                        "novelty",
                        "utility",
                        "valid_from_ms",
                        "valid_until_ms",
                        "expires_at_ms",
                    },
                    "atom",
                )
                client_id = bounded_identifier(raw.get("client_id"), "atom.client_id", REF_RE)
                if client_id in atom_refs:
                    raise ValidationError("atom client_id values must be unique")
                atom_class = bounded_text(raw.get("class"), "atom.class", 32).casefold()
                if atom_class not in ATOM_CLASSES:
                    raise ValidationError("atom.class is not supported")
                epistemic = bounded_text(raw.get("epistemic"), "atom.epistemic", 32).casefold()
                if epistemic not in EPISTEMIC_TYPES:
                    raise ValidationError("atom.epistemic is not supported")
                canonical_text_value = bounded_text(
                    raw.get("canonical_text"), "atom.canonical_text", 16 * 1024
                )
                global_id = str(
                    uuid.uuid5(
                        CONTINUITY_NAMESPACE,
                        f"atom:{source_event_global_id}:{client_id}",
                    )
                )
                values = (
                    global_id,
                    actor_node_id,
                    timestamp,
                    timestamp,
                    atom_class,
                    _optional_text(raw.get("domain"), "atom.domain", 160),
                    _optional_text(raw.get("subject"), "atom.subject", 512),
                    _optional_text(raw.get("predicate"), "atom.predicate", 192),
                    _optional_text(raw.get("object_text"), "atom.object_text", 4096),
                    canonical_text_value,
                    epistemic,
                    bounded_number(raw.get("confidence", 0.5), "atom.confidence"),
                    bounded_number(raw.get("salience", 0.5), "atom.salience"),
                    bounded_number(raw.get("stability", 0.5), "atom.stability"),
                    bounded_number(raw.get("novelty", 0.5), "atom.novelty"),
                    bounded_number(raw.get("utility", 0.5), "atom.utility"),
                    _optional_int(raw.get("valid_from_ms"), "atom.valid_from_ms"),
                    _optional_int(raw.get("valid_until_ms"), "atom.valid_until_ms"),
                    _optional_int(raw.get("expires_at_ms"), "atom.expires_at_ms"),
                )
                existing = database.execute(
                    """
                    SELECT id,origin_node_id,class,domain,subject,predicate,object_text,
                           canonical_text,epistemic,confidence,salience,stability,
                           novelty,utility,valid_from_ms,valid_until_ms,expires_at_ms
                    FROM memory_atom WHERE global_id=?
                    """,
                    (global_id,),
                ).fetchone()
                if existing:
                    expected = (values[1], values[4], *values[5:])
                    if tuple(existing[1:]) != expected:
                        raise ValidationError("replayed atom client_id changed immutable meaning")
                    atom_id = int(existing[0])
                else:
                    cursor = database.execute(
                        """
                        INSERT INTO memory_atom(
                            global_id,origin_node_id,created_at_ms,updated_at_ms,class,
                            domain,subject,predicate,object_text,canonical_text,epistemic,
                            confidence,salience,stability,novelty,utility,
                            valid_from_ms,valid_until_ms,expires_at_ms
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        values,
                    )
                    atom_id = int(cursor.lastrowid)
                atom_refs[client_id] = atom_id
                atom_globals[client_id] = global_id

            evidence_refs: set[str] = set()
            for raw in evidence:
                if not isinstance(raw, Mapping):
                    raise ValidationError("each evidence entry must be an object")
                reject_unknown(
                    raw,
                    {"atom_ref", "event_global_id", "relation", "weight"},
                    "evidence",
                )
                atom_ref = bounded_identifier(raw.get("atom_ref"), "evidence.atom_ref", REF_RE)
                atom_id = self._resolve_atom_ref(database, atom_ref, atom_refs)
                event_global_id = validate_global_id(raw.get("event_global_id"), "evidence.event_global_id")
                event_row = database.execute(
                    "SELECT id FROM event_log WHERE global_id=?", (event_global_id,)
                ).fetchone()
                if not event_row:
                    raise ValidationError("evidence references an unknown event")
                relation = bounded_text(raw.get("relation", "source"), "evidence.relation", 32)
                if relation not in EVIDENCE_RELATIONS:
                    raise ValidationError("evidence.relation is not supported")
                weight = bounded_number(raw.get("weight", 1.0), "evidence.weight")
                existing_evidence = database.execute(
                    """
                    SELECT weight FROM atom_evidence
                    WHERE atom_id=? AND event_id=? AND relation=?
                    """,
                    (atom_id, int(event_row[0]), relation),
                ).fetchone()
                if existing_evidence and float(existing_evidence[0]) != weight:
                    raise ValidationError("replayed evidence changed its immutable weight")
                database.execute(
                    "INSERT OR IGNORE INTO atom_evidence(atom_id,event_id,relation,weight) VALUES(?,?,?,?)",
                    (
                        atom_id,
                        int(event_row[0]),
                        relation,
                        weight,
                    ),
                )
                evidence_refs.add(atom_ref)

            missing_evidence = sorted(set(atom_refs) - evidence_refs)
            if missing_evidence:
                raise ValidationError(
                    "every new atom needs evidence; missing: " + ", ".join(missing_evidence)
                )

            for raw in edges:
                if not isinstance(raw, Mapping):
                    raise ValidationError("each edge must be an object")
                reject_unknown(raw, {"from_ref", "to_ref", "relation", "weight"}, "edge")
                from_id = self._resolve_atom_ref(database, raw.get("from_ref"), atom_refs)
                to_id = self._resolve_atom_ref(database, raw.get("to_ref"), atom_refs)
                if from_id == to_id:
                    raise ValidationError("an atom cannot have an edge to itself")
                relation = bounded_text(raw.get("relation"), "edge.relation", 32)
                if relation not in EDGE_RELATIONS:
                    raise ValidationError("edge.relation is not supported")
                database.execute(
                    """
                    INSERT INTO memory_edge(
                        from_atom_id,to_atom_id,relation,weight,created_at_ms,source_event_id
                    ) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(from_atom_id,to_atom_id,relation) DO UPDATE SET
                      weight=MAX(memory_edge.weight,excluded.weight)
                    """,
                    (
                        from_id,
                        to_id,
                        relation,
                        bounded_number(raw.get("weight", 0.5), "edge.weight"),
                        timestamp,
                        source_event_id,
                    ),
                )

            explicit_friction_ids: list[int] = []
            for index, raw in enumerate(frictions):
                explicit_friction_ids.append(
                    self._apply_friction(
                        database,
                        raw,
                        index=index,
                        source_event_global_id=source_event_global_id,
                        source_event_id=source_event_id,
                        atom_refs=atom_refs,
                        timestamp=timestamp,
                    )
                )

            state_applied = 0
            state_conflicts: list[int] = []
            for raw in state_updates:
                outcome, friction_id = self._apply_state_update(
                    database,
                    raw,
                    source_event_global_id=source_event_global_id,
                    source_event_id=source_event_id,
                    atom_refs=atom_refs,
                    timestamp=timestamp,
                )
                state_applied += int(outcome)
                if friction_id is not None:
                    state_conflicts.append(friction_id)

            goals_applied = 0
            for raw in goal_updates:
                self._apply_goal_update(
                    database,
                    raw,
                    source_event_global_id=source_event_global_id,
                    source_event_id=source_event_id,
                    atom_refs=atom_refs,
                    timestamp=timestamp,
                )
                goals_applied += 1

            return {
                "schema_version": DELTA_SCHEMA_VERSION,
                "source_event_global_id": source_event_global_id,
                "atoms": len(atom_refs),
                "atom_global_ids": atom_globals,
                "evidence": len(evidence),
                "edges": len(edges),
                "frictions": explicit_friction_ids,
                "state_updates_applied": state_applied,
                "state_conflicts": state_conflicts,
                "goal_updates_applied": goals_applied,
            }

    @staticmethod
    def _resolve_atom_ref(
        database: sqlite3.Connection,
        raw_ref: Any,
        local_refs: Mapping[str, int],
    ) -> int:
        ref = bounded_identifier(raw_ref, "atom reference", REF_RE)
        if ref in local_refs:
            return int(local_refs[ref])
        try:
            global_id = validate_global_id(ref, "atom reference")
        except ValidationError:
            raise ValidationError(f"unknown atom reference: {ref}") from None
        row = database.execute(
            "SELECT id FROM memory_atom WHERE global_id=?", (global_id,)
        ).fetchone()
        if not row:
            raise ValidationError(f"unknown atom reference: {ref}")
        return int(row[0])

    def _apply_friction(
        self,
        database: sqlite3.Connection,
        raw: Any,
        *,
        index: int,
        source_event_global_id: str,
        source_event_id: int,
        atom_refs: Mapping[str, int],
        timestamp: int,
    ) -> int:
        if not isinstance(raw, Mapping):
            raise ValidationError("each friction entry must be an object")
        reject_unknown(
            raw,
            {"client_id", "type", "logical_key", "atom_a_ref", "atom_b_ref", "description", "severity"},
            "friction",
        )
        friction_type = bounded_text(raw.get("type"), "friction.type", 32)
        if friction_type not in FRICTION_TYPES:
            raise ValidationError("friction.type is not supported")
        client_id = bounded_identifier(
            raw.get("client_id", f"friction-{index}"), "friction.client_id", REF_RE
        )
        global_id = str(
            uuid.uuid5(CONTINUITY_NAMESPACE, f"friction:{source_event_global_id}:{client_id}")
        )
        atom_a = (
            self._resolve_atom_ref(database, raw.get("atom_a_ref"), atom_refs)
            if raw.get("atom_a_ref") is not None
            else None
        )
        atom_b = (
            self._resolve_atom_ref(database, raw.get("atom_b_ref"), atom_refs)
            if raw.get("atom_b_ref") is not None
            else None
        )
        logical_key = _optional_text(raw.get("logical_key"), "friction.logical_key", 384)
        description = bounded_text(raw.get("description"), "friction.description", 4096)
        severity = bounded_number(raw.get("severity", 0.5), "friction.severity")
        existing = database.execute(
            """
            SELECT id,type,logical_key,atom_a_id,atom_b_id,source_event_id,description,severity
            FROM friction_event WHERE global_id=?
            """,
            (global_id,),
        ).fetchone()
        expected = (
            friction_type,
            logical_key,
            atom_a,
            atom_b,
            source_event_id,
            description,
            severity,
        )
        if existing and tuple(existing[1:]) != expected:
            raise ValidationError("replayed friction client_id changed immutable meaning")
        database.execute(
            """
            INSERT OR IGNORE INTO friction_event(
                global_id,created_at_ms,type,logical_key,atom_a_id,atom_b_id,
                source_event_id,description,severity
            ) VALUES(?,?,?,?,?,?,?,?,?)
            """,
            (
                global_id,
                timestamp,
                friction_type,
                logical_key,
                atom_a,
                atom_b,
                source_event_id,
                description,
                severity,
            ),
        )
        row = database.execute(
            "SELECT id FROM friction_event WHERE global_id=?", (global_id,)
        ).fetchone()
        return int(row[0])

    def _apply_state_update(
        self,
        database: sqlite3.Connection,
        raw: Any,
        *,
        source_event_global_id: str,
        source_event_id: int,
        atom_refs: Mapping[str, int],
        timestamp: int,
    ) -> tuple[bool, int | None]:
        if not isinstance(raw, Mapping):
            raise ValidationError("each state update must be an object")
        reject_unknown(
            raw,
            {
                "scope",
                "key",
                "value",
                "confidence",
                "source_atom_ref",
                "expires_at_ms",
                "resolve_friction_id",
            },
            "state update",
        )
        scope = bounded_identifier(raw.get("scope"), "state.scope", STATE_KEY_RE)
        key = bounded_identifier(raw.get("key"), "state.key", STATE_KEY_RE)
        value_json = canonical_json(raw.get("value"))
        if len(value_json.encode("utf-8")) > 16 * 1024:
            raise ValidationError("state.value exceeds 16384 bytes")
        source_atom_id = self._resolve_atom_ref(database, raw.get("source_atom_ref"), atom_refs)
        confidence = bounded_number(raw.get("confidence", 1.0), "state.confidence")
        expires_at = _optional_int(raw.get("expires_at_ms"), "state.expires_at_ms")
        current = database.execute(
            "SELECT value_json,source_atom_id FROM state_register WHERE scope=? AND key=?",
            (scope, key),
        ).fetchone()
        if current and str(current[0]) != value_json:
            resolution_id = _optional_int(
                raw.get("resolve_friction_id"), "state.resolve_friction_id", 1
            )
            if resolution_id is not None:
                friction = database.execute(
                    "SELECT status,logical_key FROM friction_event WHERE id=?",
                    (resolution_id,),
                ).fetchone()
                if (
                    not friction
                    or str(friction[0]) not in {"open", "investigating"}
                    or str(friction[1] or "") != f"state:{scope}:{key}"
                ):
                    raise ValidationError("state resolution does not match an open friction record")
                database.execute(
                    """
                    UPDATE friction_event SET status='resolved',resolution_atom_id=?,resolved_at_ms=?
                    WHERE id=?
                    """,
                    (source_atom_id, timestamp, resolution_id),
                )
            else:
                friction_global = str(
                    uuid.uuid5(
                        CONTINUITY_NAMESPACE,
                        f"state-conflict:{source_event_global_id}:{scope}:{key}:{source_atom_id}",
                    )
                )
                database.execute(
                    """
                    INSERT OR IGNORE INTO friction_event(
                        global_id,created_at_ms,type,logical_key,atom_a_id,atom_b_id,
                        source_event_id,description,severity
                    ) VALUES(?,?,'contradiction',?,?,?,?,?,0.75)
                    """,
                    (
                        friction_global,
                        timestamp,
                        f"state:{scope}:{key}",
                        int(current[1]),
                        source_atom_id,
                        source_event_id,
                        f"Conflicting proposed values for state {scope}.{key}; materialized state was preserved.",
                    ),
                )
                friction_id = int(
                    database.execute(
                        "SELECT id FROM friction_event WHERE global_id=?", (friction_global,)
                    ).fetchone()[0]
                )
                return False, friction_id

        database.execute(
            """
            INSERT INTO state_register(
                scope,key,value_json,confidence,source_atom_id,source_event_id,updated_at_ms,expires_at_ms
            ) VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(scope,key) DO UPDATE SET
                value_json=excluded.value_json,
                confidence=excluded.confidence,
                source_atom_id=excluded.source_atom_id,
                source_event_id=excluded.source_event_id,
                updated_at_ms=excluded.updated_at_ms,
                expires_at_ms=excluded.expires_at_ms
            """,
            (
                scope,
                key,
                value_json,
                confidence,
                source_atom_id,
                source_event_id,
                timestamp,
                expires_at,
            ),
        )
        return True, None

    def _apply_goal_update(
        self,
        database: sqlite3.Connection,
        raw: Any,
        *,
        source_event_global_id: str,
        source_event_id: int,
        atom_refs: Mapping[str, int],
        timestamp: int,
    ) -> None:
        if not isinstance(raw, Mapping):
            raise ValidationError("each goal update must be an object")
        reject_unknown(
            raw,
            {
                "client_id",
                "global_id",
                "parent_global_id",
                "title",
                "description",
                "status",
                "priority",
                "progress",
                "deadline_ms",
                "source_atom_ref",
            },
            "goal update",
        )
        client_id = bounded_identifier(raw.get("client_id", "goal"), "goal.client_id", REF_RE)
        global_id = (
            validate_global_id(raw.get("global_id"), "goal.global_id")
            if raw.get("global_id") is not None
            else str(uuid.uuid5(CONTINUITY_NAMESPACE, f"goal:{source_event_global_id}:{client_id}"))
        )
        source_atom_id = self._resolve_atom_ref(database, raw.get("source_atom_ref"), atom_refs)
        parent_id = None
        if raw.get("parent_global_id") is not None:
            parent_global = validate_global_id(raw.get("parent_global_id"), "goal.parent_global_id")
            parent = database.execute(
                "SELECT id FROM goal WHERE global_id=?", (parent_global,)
            ).fetchone()
            if not parent:
                raise ValidationError("goal parent is unknown")
            parent_id = int(parent[0])
        status = bounded_text(raw.get("status", "active"), "goal.status", 32)
        if status not in GOAL_STATUSES:
            raise ValidationError("goal.status is not supported")
        title = bounded_text(raw.get("title"), "goal.title", 512)
        existing = database.execute(
            "SELECT title FROM goal WHERE global_id=?", (global_id,)
        ).fetchone()
        if existing and str(existing[0]) != title:
            raise ValidationError("an existing goal title cannot be silently redefined")
        database.execute(
            """
            INSERT INTO goal(
                global_id,parent_goal_id,created_at_ms,updated_at_ms,title,description,
                status,priority,progress,source_event_id,source_atom_id,deadline_ms
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(global_id) DO UPDATE SET
                parent_goal_id=excluded.parent_goal_id,
                updated_at_ms=excluded.updated_at_ms,
                description=excluded.description,
                status=excluded.status,
                priority=excluded.priority,
                progress=excluded.progress,
                source_event_id=excluded.source_event_id,
                source_atom_id=excluded.source_atom_id,
                deadline_ms=excluded.deadline_ms
            """,
            (
                global_id,
                parent_id,
                timestamp,
                timestamp,
                title,
                _optional_text(raw.get("description"), "goal.description", 4096),
                status,
                bounded_number(raw.get("priority", 0.5), "goal.priority"),
                bounded_number(raw.get("progress", 0.0), "goal.progress"),
                source_event_id,
                source_atom_id,
                _optional_int(raw.get("deadline_ms"), "goal.deadline_ms"),
            ),
        )

    def search_memories(self, query: str, *, limit: int = 32) -> list[dict[str, Any]]:
        query = bounded_text(query, "query", MAX_QUERY_CHARACTERS, allow_empty=True)
        limit = max(1, min(int(limit), 100))
        fts = _fts_query(query)
        with self.connection() as database:
            if fts:
                rows = database.execute(
                    """
                    SELECT m.*,bm25(memory_fts) AS lexical_rank
                    FROM memory_fts JOIN memory_atom m ON m.id=memory_fts.rowid
                    WHERE memory_fts MATCH ? AND m.status='active'
                      AND (m.expires_at_ms IS NULL OR m.expires_at_ms>?)
                    ORDER BY lexical_rank ASC,m.salience DESC,m.utility DESC
                    LIMIT ?
                    """,
                    (fts, now_ms(), limit),
                ).fetchall()
            else:
                rows = database.execute(
                    """
                    SELECT m.*,NULL AS lexical_rank FROM memory_atom m
                    WHERE m.status='active'
                      AND (m.expires_at_ms IS NULL OR m.expires_at_ms>?)
                    ORDER BY m.salience DESC,m.utility DESC,m.updated_at_ms DESC
                    LIMIT ?
                    """,
                    (now_ms(), limit),
                ).fetchall()
            return [self._memory_row(database, row) for row in rows]

    @staticmethod
    def _memory_row(database: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        evidence = database.execute(
            """
            SELECT e.global_id,ae.relation,ae.weight
            FROM atom_evidence ae JOIN event_log e ON e.id=ae.event_id
            WHERE ae.atom_id=? ORDER BY ae.weight DESC,e.receive_time_ms DESC
            """,
            (int(row["id"]),),
        ).fetchall()
        result["evidence"] = [
            {"event_global_id": str(item[0]), "relation": str(item[1]), "weight": float(item[2])}
            for item in evidence
        ]
        return result

    def list_state(self, *, scope: str | None = None, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        parameters: list[Any] = [now_ms()]
        clause = ""
        if scope is not None:
            scope = bounded_identifier(scope, "scope", STATE_KEY_RE)
            clause = " AND s.scope=?"
            parameters.append(scope)
        parameters.append(limit)
        with self.connection() as database:
            rows = database.execute(
                """
                SELECT s.*,m.global_id AS source_atom_global_id,e.global_id AS source_event_global_id
                FROM state_register s
                JOIN memory_atom m ON m.id=s.source_atom_id
                LEFT JOIN event_log e ON e.id=s.source_event_id
                WHERE (s.expires_at_ms IS NULL OR s.expires_at_ms>?)
                """ + clause + " ORDER BY s.scope,s.key LIMIT ?",
                parameters,
            ).fetchall()
        return [self._decode_state_row(row) for row in rows]

    @staticmethod
    def _decode_state_row(row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        result["value"] = json.loads(result.pop("value_json"))
        return result

    def list_goals(self, *, active_only: bool = True, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        where = "WHERE g.status IN ('active','blocked','paused')" if active_only else ""
        with self.connection() as database:
            rows = database.execute(
                f"""
                SELECT g.*,a.global_id AS source_atom_global_id,e.global_id AS source_event_global_id
                FROM goal g
                LEFT JOIN memory_atom a ON a.id=g.source_atom_id
                LEFT JOIN event_log e ON e.id=g.source_event_id
                {where}
                ORDER BY g.priority DESC,g.updated_at_ms DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]

    def list_friction(self, *, open_only: bool = True, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        where = "WHERE f.status IN ('open','investigating')" if open_only else ""
        with self.connection() as database:
            rows = database.execute(
                f"""
                SELECT f.*,a.global_id AS atom_a_global_id,b.global_id AS atom_b_global_id,
                       e.global_id AS source_event_global_id
                FROM friction_event f
                LEFT JOIN memory_atom a ON a.id=f.atom_a_id
                LEFT JOIN memory_atom b ON b.id=f.atom_b_id
                LEFT JOIN event_log e ON e.id=f.source_event_id
                {where}
                ORDER BY f.severity DESC,f.created_at_ms DESC LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]

    def recent_events(self, *, limit: int = 32) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 100))
        with self.connection() as database:
            rows = database.execute(
                "SELECT * FROM event_log ORDER BY receive_time_ms DESC,id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._event_row(row) for row in rows]

    def compile_context(
        self,
        *,
        query: str,
        token_budget: int = 2_400,
        session_id: str = "",
    ) -> dict[str, Any]:
        query = bounded_text(query, "query", MAX_QUERY_CHARACTERS, allow_empty=True)
        token_budget = max(256, min(int(token_budget), MAX_CONTEXT_TOKENS))
        session_id = bounded_text(session_id, "session_id", 160, allow_empty=True)
        packet: dict[str, Any] = {
            "schema_version": 1,
            "generated_at_ms": now_ms(),
            "query": query,
            "session_id": session_id,
            "token_budget": token_budget,
            "sections": {
                "state": [],
                "goals": [],
                "friction": [],
                "recent_events": [],
                "memories": [],
            },
            "embeddings_used": False,
            "truncated": False,
        }

        candidates: list[tuple[str, dict[str, Any]]] = []
        for item in self.list_state(limit=32):
            candidates.append(
                (
                    "state",
                    {
                        "scope": item["scope"],
                        "key": item["key"],
                        "value": item["value"],
                        "confidence": item["confidence"],
                        "source_atom_global_id": item["source_atom_global_id"],
                        "source_event_global_id": item["source_event_global_id"],
                    },
                )
            )
        for item in self.list_goals(limit=16):
            candidates.append(
                (
                    "goals",
                    {
                        "global_id": item["global_id"],
                        "title": item["title"],
                        "description": item["description"],
                        "status": item["status"],
                        "priority": item["priority"],
                        "progress": item["progress"],
                        "source_atom_global_id": item["source_atom_global_id"],
                    },
                )
            )
        for item in self.list_friction(limit=16):
            candidates.append(
                (
                    "friction",
                    {
                        "id": item["id"],
                        "global_id": item["global_id"],
                        "type": item["type"],
                        "logical_key": item["logical_key"],
                        "description": item["description"],
                        "severity": item["severity"],
                        "atom_a_global_id": item["atom_a_global_id"],
                        "atom_b_global_id": item["atom_b_global_id"],
                    },
                )
            )
        for item in reversed(self.recent_events(limit=20)):
            candidates.append(
                (
                    "recent_events",
                    {
                        "global_id": item["global_id"],
                        "node_id": item["origin_node_id"],
                        "kind": item["kind"],
                        "actor": item["actor"],
                        "content": str(item["content"])[:2_000],
                        "source_time_ms": item["source_time_ms"],
                    },
                )
            )
        for item in self.search_memories(query, limit=48):
            candidates.append(
                (
                    "memories",
                    {
                        "global_id": item["global_id"],
                        "class": item["class"],
                        "canonical_text": item["canonical_text"],
                        "epistemic": item["epistemic"],
                        "confidence": item["confidence"],
                        "salience": item["salience"],
                        "evidence": item["evidence"][:8],
                    },
                )
            )

        for section, item in candidates:
            packet["sections"][section].append(item)
            if _estimate_tokens(packet) > token_budget:
                packet["sections"][section].pop()
                packet["truncated"] = True
        packet["approx_tokens"] = 0
        packet["approx_tokens"] = _estimate_tokens(packet)
        while _estimate_tokens(packet) > token_budget:
            removed = False
            for section in ("memories", "recent_events", "friction", "goals", "state"):
                if packet["sections"][section]:
                    packet["sections"][section].pop()
                    packet["truncated"] = True
                    removed = True
                    break
            if not removed:
                break
            packet["approx_tokens"] = _estimate_tokens(packet)
        packet["approx_tokens"] = _estimate_tokens(packet)
        return packet

    def record_context_snapshot(
        self,
        *,
        session_id: str,
        reason: str,
        summary: str,
        packet: Mapping[str, Any],
    ) -> int:
        session_id = bounded_text(session_id, "session_id", 160)
        reason = bounded_text(reason, "reason", 256)
        summary = bounded_text(summary, "summary", 16 * 1024)
        sections = packet.get("sections", {}) if isinstance(packet, Mapping) else {}
        memory_ids = [item.get("global_id") for item in sections.get("memories", [])]
        goal_ids = [item.get("global_id") for item in sections.get("goals", [])]
        friction_ids = [item.get("global_id") for item in sections.get("friction", [])]
        state = sections.get("state", [])
        with self.connection(write=True) as database:
            parent = database.execute(
                "SELECT id FROM context_snapshot WHERE session_id=? ORDER BY id DESC LIMIT 1",
                (session_id,),
            ).fetchone()
            cursor = database.execute(
                """
                INSERT INTO context_snapshot(
                    created_at_ms,session_id,reason,summary,state_json,memory_ids_json,
                    goal_ids_json,friction_ids_json,approx_tokens,parent_snapshot_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    now_ms(),
                    session_id,
                    reason,
                    summary,
                    canonical_json(state),
                    canonical_json(memory_ids),
                    canonical_json(goal_ids),
                    canonical_json(friction_ids),
                    _estimate_tokens(packet),
                    int(parent[0]) if parent else None,
                ),
            )
            return int(cursor.lastrowid)

    def record_heartbeat(self, node_id: str, heartbeat: Mapping[str, Any]) -> dict[str, Any]:
        node_id = bounded_identifier(node_id, "node_id", NODE_ID_RE)
        if not isinstance(heartbeat, Mapping):
            raise ValidationError("heartbeat must be an object")
        reject_unknown(
            heartbeat,
            {
                "observed_at_ms",
                "status",
                "battery_pct",
                "temperature_c",
                "free_ram_mib",
                "free_storage_mib",
                "current_job",
                "details",
                "request_id",
                "schema_version",
            },
            "heartbeat",
        )
        request_id = (
            validate_global_id(heartbeat.get("request_id"), "heartbeat.request_id")
            if heartbeat.get("request_id") is not None
            else None
        )
        schema_version = _optional_int(
            heartbeat.get("schema_version", 1), "heartbeat.schema_version", 1
        )
        if schema_version != 1:
            raise ValidationError("unsupported heartbeat schema; expected 1")
        if request_id is not None and heartbeat.get("observed_at_ms") is None:
            raise ValidationError("idempotent heartbeat requests need observed_at_ms")
        status = bounded_text(heartbeat.get("status", "ok"), "heartbeat.status", 32)
        if status not in {"ok", "degraded", "offline", "maintenance"}:
            raise ValidationError("heartbeat.status is not supported")
        details = heartbeat.get("details", {})
        if not isinstance(details, Mapping):
            raise ValidationError("heartbeat.details must be an object")
        details_json = canonical_json(details)
        if len(details_json.encode("utf-8")) > 16 * 1024:
            raise ValidationError("heartbeat.details exceeds 16384 bytes")
        observed = _optional_int(heartbeat.get("observed_at_ms", now_ms()), "observed_at_ms")
        battery_pct = (
            bounded_number(heartbeat["battery_pct"], "battery_pct", minimum=0, maximum=100)
            if heartbeat.get("battery_pct") is not None
            else None
        )
        temperature_c = _optional_float(
            heartbeat.get("temperature_c"),
            "temperature_c",
            minimum=-20,
            maximum=150,
        )
        free_ram_mib = _optional_int(heartbeat.get("free_ram_mib"), "free_ram_mib")
        free_storage_mib = _optional_int(
            heartbeat.get("free_storage_mib"), "free_storage_mib"
        )
        current_job = _optional_text(heartbeat.get("current_job"), "current_job", 512)
        payload_sha256 = sha256_text(
            canonical_json(
                {
                    "observed_at_ms": observed,
                    "status": status,
                    "battery_pct": battery_pct,
                    "temperature_c": temperature_c,
                    "free_ram_mib": free_ram_mib,
                    "free_storage_mib": free_storage_mib,
                    "current_job": current_job,
                    "details": details,
                    "schema_version": schema_version,
                }
            )
        )
        with self.connection(write=True) as database:
            node = database.execute(
                "SELECT 1 FROM node_registry WHERE node_id=? AND enabled=1", (node_id,)
            ).fetchone()
            if not node:
                raise ValidationError("heartbeat node is not registered and enabled")
            if request_id is not None:
                previous = database.execute(
                    """
                    SELECT payload_sha256 FROM request_receipt
                    WHERE node_id=? AND operation='heartbeat' AND request_id=?
                    """,
                    (node_id, request_id),
                ).fetchone()
                if previous:
                    if str(previous[0]) != payload_sha256:
                        raise ValidationError("heartbeat request ID was replayed with different bytes")
                    return {"node_id": node_id, "status": "duplicate", "request_id": request_id}
            database.execute(
                """
                INSERT INTO node_heartbeat(
                    node_id,observed_at_ms,status,battery_pct,temperature_c,free_ram_mib,
                    free_storage_mib,current_job,details_json,last_request_id,
                    last_payload_sha256,schema_version
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(node_id) DO UPDATE SET
                    observed_at_ms=excluded.observed_at_ms,status=excluded.status,
                    battery_pct=excluded.battery_pct,temperature_c=excluded.temperature_c,
                    free_ram_mib=excluded.free_ram_mib,
                    free_storage_mib=excluded.free_storage_mib,
                    current_job=excluded.current_job,details_json=excluded.details_json,
                    last_request_id=excluded.last_request_id,
                    last_payload_sha256=excluded.last_payload_sha256,
                    schema_version=excluded.schema_version
                """,
                (
                    node_id,
                    observed,
                    status,
                    battery_pct,
                    temperature_c,
                    free_ram_mib,
                    free_storage_mib,
                    current_job,
                    details_json,
                    request_id,
                    payload_sha256,
                    schema_version,
                ),
            )
            database.execute(
                "UPDATE node_registry SET last_seen_ms=? WHERE node_id=?", (now_ms(), node_id)
            )
            if request_id is not None:
                database.execute(
                    """
                    INSERT INTO request_receipt(
                        node_id,operation,request_id,payload_sha256,committed_at_ms
                    ) VALUES(?,'heartbeat',?,?,?)
                    """,
                    (node_id, request_id, payload_sha256, now_ms()),
                )
        return {"node_id": node_id, "status": "committed", "request_id": request_id}

    def enqueue_job(
        self,
        *,
        kind: str,
        payload: Mapping[str, Any],
        preferred_role: str = "librarian",
        priority: float = 0.5,
        source_event_global_id: str | None = None,
        job_id: str | None = None,
        not_before_ms: int | None = None,
        min_free_ram_mib: int = 0,
        max_temp_c: float | None = None,
        requires_charging: bool = False,
    ) -> str:
        kind = bounded_identifier(kind, "job.kind", KIND_RE)
        if kind not in JOB_KINDS:
            raise ValidationError("job.kind is not supported")
        preferred_role = bounded_text(preferred_role, "preferred_role", 32)
        if preferred_role not in {"librarian", "cortex", "archive", "any"}:
            raise ValidationError("preferred_role is not supported")
        if not isinstance(payload, Mapping):
            raise ValidationError("job.payload must be an object")
        if not isinstance(requires_charging, bool):
            raise ValidationError("requires_charging must be a boolean")
        payload_json = canonical_json(payload)
        if len(payload_json.encode("utf-8")) > 64 * 1024:
            raise ValidationError("job.payload exceeds 65536 bytes")
        job_id = validate_global_id(job_id, "job_id") if job_id else str(uuid.uuid4())
        min_free_ram = _optional_int(min_free_ram_mib, "min_free_ram_mib") or 0
        maximum_temperature = _optional_float(
            max_temp_c,
            "max_temp_c",
            minimum=-20,
            maximum=150,
        )
        priority_value = bounded_number(priority, "job.priority")
        not_before = _optional_int(not_before_ms, "not_before_ms")
        timestamp = now_ms()
        with self.connection(write=True) as database:
            source_event_id = None
            if source_event_global_id is not None:
                source_event_global_id = validate_global_id(source_event_global_id)
                source = database.execute(
                    "SELECT id FROM event_log WHERE global_id=?", (source_event_global_id,)
                ).fetchone()
                if not source:
                    raise ValidationError("job source event is unknown")
                source_event_id = int(source[0])
            existing = database.execute(
                """
                SELECT kind,payload_json,preferred_role,min_free_ram_mib,max_temp_c,
                       requires_charging,priority,not_before_ms,source_event_id
                FROM librarian_job WHERE job_id=?
                """,
                (job_id,),
            ).fetchone()
            expected = (
                kind,
                payload_json,
                preferred_role,
                min_free_ram,
                maximum_temperature,
                int(requires_charging),
                priority_value,
                not_before,
                source_event_id,
            )
            if existing:
                if tuple(existing) != expected:
                    raise ValidationError("job ID was replayed with different request bytes")
                return job_id
            database.execute(
                """
                INSERT INTO librarian_job(
                    job_id,created_at_ms,updated_at_ms,kind,payload_json,preferred_role,
                    min_free_ram_mib,max_temp_c,requires_charging,priority,not_before_ms,
                    source_event_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    job_id,
                    timestamp,
                    timestamp,
                    kind,
                    payload_json,
                    preferred_role,
                    min_free_ram,
                    maximum_temperature,
                    int(requires_charging),
                    priority_value,
                    not_before,
                    source_event_id,
                ),
            )
        return job_id

    def get_job(self, job_id: str) -> dict[str, Any] | None:
        job_id = validate_global_id(job_id, "job_id")
        with self.connection() as database:
            row = database.execute("SELECT * FROM librarian_job WHERE job_id=?", (job_id,)).fetchone()
        return self._job_row(row) if row else None

    @staticmethod
    def _job_row(row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        result["payload"] = json.loads(result.pop("payload_json"))
        result["result"] = json.loads(result.pop("result_json")) if result["result_json"] else None
        result["requires_charging"] = bool(result["requires_charging"])
        return result

    def lease_job(
        self,
        *,
        owner_node_id: str,
        role: str,
        lease_ms: int = 60_000,
        free_ram_mib: int | None = None,
        temperature_c: float | None = None,
        charging: bool | None = None,
    ) -> dict[str, Any] | None:
        owner_node_id = bounded_identifier(owner_node_id, "owner_node_id", NODE_ID_RE)
        role = bounded_text(role, "role", 32)
        if role not in {"librarian", "cortex", "archive"}:
            raise ValidationError("worker role is not supported")
        free_ram_mib = _optional_int(free_ram_mib, "free_ram_mib")
        temperature_c = _optional_float(
            temperature_c,
            "temperature_c",
            minimum=-20,
            maximum=150,
        )
        if charging is not None and not isinstance(charging, bool):
            raise ValidationError("charging must be a boolean or null")
        timestamp = now_ms()
        lease_ms = max(1_000, min(int(lease_ms), 15 * 60_000))
        with self.connection(write=True) as database:
            node = database.execute(
                "SELECT enabled FROM node_registry WHERE node_id=?", (owner_node_id,)
            ).fetchone()
            if not node or not int(node[0]):
                raise ValidationError("worker node is not registered and enabled")
            database.execute(
                """
                UPDATE librarian_job SET state='pending',lease_owner_node_id=NULL,
                    lease_until_ms=NULL,updated_at_ms=?
                WHERE state IN ('leased','running') AND lease_until_ms<?
                """,
                (timestamp, timestamp),
            )
            row = database.execute(
                """
                SELECT * FROM librarian_job
                WHERE state='pending' AND (not_before_ms IS NULL OR not_before_ms<=?)
                  AND preferred_role IN (?, 'any')
                  AND (
                    min_free_ram_mib=0 OR
                    (? IS NOT NULL AND min_free_ram_mib<=?)
                  )
                  AND (
                    max_temp_c IS NULL OR
                    (? IS NOT NULL AND max_temp_c>=?)
                  )
                  AND (requires_charging=0 OR ?=1)
                ORDER BY priority DESC,created_at_ms ASC LIMIT 1
                """,
                (
                    timestamp,
                    role,
                    free_ram_mib,
                    free_ram_mib,
                    temperature_c,
                    temperature_c,
                    1 if charging is True else 0,
                ),
            ).fetchone()
            if not row:
                return None
            database.execute(
                """
                UPDATE librarian_job SET state='leased',lease_owner_node_id=?,lease_until_ms=?,
                    updated_at_ms=?,attempts=attempts+1 WHERE job_id=? AND state='pending'
                """,
                (owner_node_id, timestamp + lease_ms, timestamp, str(row["job_id"])),
            )
            leased = database.execute(
                "SELECT * FROM librarian_job WHERE job_id=?", (str(row["job_id"]),)
            ).fetchone()
            return self._job_row(leased)

    def complete_job(
        self,
        *,
        job_id: str,
        owner_node_id: str,
        result: Mapping[str, Any],
    ) -> None:
        job_id = validate_global_id(job_id, "job_id")
        owner_node_id = bounded_identifier(owner_node_id, "owner_node_id", NODE_ID_RE)
        if not isinstance(result, Mapping):
            raise ValidationError("job result must be an object")
        result_json = canonical_json(result)
        if len(result_json.encode("utf-8")) > 64 * 1024:
            raise ValidationError("job result exceeds 65536 bytes")
        with self.connection(write=True) as database:
            current = database.execute(
                """
                SELECT state,result_json,lease_owner_node_id
                FROM librarian_job WHERE job_id=?
                """,
                (job_id,),
            ).fetchone()
            if (
                current
                and str(current[0]) == "done"
                and str(current[2] or "") == owner_node_id
            ):
                if str(current[1]) != result_json:
                    raise ValidationError("completed job was replayed with a different result")
                return
            cursor = database.execute(
                """
                UPDATE librarian_job SET state='done',result_json=?,last_error=NULL,
                    updated_at_ms=?,lease_until_ms=NULL
                WHERE job_id=? AND lease_owner_node_id=? AND state IN ('leased','running')
                """,
                (result_json, now_ms(), job_id, owner_node_id),
            )
            if cursor.rowcount != 1:
                raise ValidationError("job is not leased to this node")

    def fail_job(
        self,
        *,
        job_id: str,
        owner_node_id: str,
        error: str,
        max_attempts: int = 3,
    ) -> str:
        job_id = validate_global_id(job_id, "job_id")
        owner_node_id = bounded_identifier(owner_node_id, "owner_node_id", NODE_ID_RE)
        error = bounded_text(error, "job error", 2048)
        with self.connection(write=True) as database:
            row = database.execute(
                "SELECT attempts FROM librarian_job WHERE job_id=? AND lease_owner_node_id=?",
                (job_id, owner_node_id),
            ).fetchone()
            if not row:
                raise ValidationError("job is not leased to this node")
            state = "failed" if int(row[0]) >= max_attempts else "pending"
            retry_at = None if state == "failed" else now_ms() + min(300_000, 2 ** int(row[0]) * 1_000)
            database.execute(
                """
                UPDATE librarian_job SET state=?,last_error=?,updated_at_ms=?,
                    lease_owner_node_id=NULL,lease_until_ms=NULL,not_before_ms=? WHERE job_id=?
                """,
                (state, error, now_ms(), retry_at, job_id),
            )
            if state == "failed":
                database.execute(
                    """
                    INSERT INTO friction_event(
                        global_id,created_at_ms,type,logical_key,description,severity,status
                    ) VALUES(?,?,'runtime_failure',?,?,0.8,'open')
                    """,
                    (
                        str(uuid.uuid5(CONTINUITY_NAMESPACE, f"worker-failure:{job_id}")),
                        now_ms(),
                        f"job:{job_id}",
                        f"Worker job {job_id} failed after {int(row[0])} attempts: {error}",
                    ),
                )
            return state

    def integrity_check(self) -> dict[str, Any]:
        with self.connection() as database:
            quick = str(database.execute("PRAGMA quick_check(1)").fetchone()[0])
            foreign = [tuple(row) for row in database.execute("PRAGMA foreign_key_check")]
            required = {
                "lattice_meta",
                "event_log",
                "memory_atom",
                "atom_evidence",
                "friction_event",
                "state_register",
                "goal",
                "node_registry",
                "request_receipt",
                "replication_outbox",
                "snapshot_catalog",
            }
            actual = {
                str(row[0])
                for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")
            }
        return {
            "ok": quick == "ok" and not foreign and not (required - actual),
            "quick_check": quick,
            "foreign_key_failures": foreign,
            "missing_tables": sorted(required - actual),
        }

    def counts(self) -> dict[str, int]:
        names = {
            "events": "event_log",
            "atoms": "memory_atom",
            "open_friction": "v_open_friction",
            "active_goals": "goal",
            "states": "state_register",
            "pending_jobs": "librarian_job",
            "pending_replication": "replication_outbox",
            "snapshots": "snapshot_catalog",
        }
        clauses = {
            "active_goals": " WHERE status IN ('active','blocked','paused')",
            "pending_jobs": " WHERE state IN ('pending','leased','running')",
            "pending_replication": " WHERE state IN ('pending','sending','failed')",
        }
        with self.connection() as database:
            return {
                key: int(
                    database.execute(
                        f"SELECT COUNT(*) FROM {table}{clauses.get(key, '')}"
                    ).fetchone()[0]
                )
                for key, table in names.items()
            }

    def last_event(self) -> dict[str, Any] | None:
        with self.connection() as database:
            row = database.execute(
                "SELECT * FROM event_log ORDER BY receive_time_ms DESC,id DESC LIMIT 1"
            ).fetchone()
        return self._event_row(row) if row else None


class _NullLock:
    def __enter__(self) -> "_NullLock":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None
