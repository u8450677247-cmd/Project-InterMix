"""Durable, versioned memory storage for Project Intermix.

This module intentionally has no third-party dependencies.  Every public
operation opens a short-lived SQLite connection so the Textual UI, inference
router, and background jobs can safely use the same database.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Sequence

from runtime_config import CONFIG


SCHEMA_VERSION = 2
DEFAULT_DB = str(CONFIG.memory_db)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def estimate_tokens(text: str) -> int:
    """Conservative tokenizer-free estimate suitable for prompt budgeting."""
    if not text:
        return 0
    return max(1, math.ceil(len(text) / 3.2))


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().casefold()


def stable_hash(*parts: str) -> str:
    joined = "\x1f".join(normalize_text(part) for part in parts)
    return hashlib.sha256(joined.encode("utf-8", "replace")).hexdigest()


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _json_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item).strip()]
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
            if isinstance(decoded, list):
                return [str(item) for item in decoded if str(item).strip()]
        except json.JSONDecodeError:
            if value.strip():
                return [value.strip()]
    return []


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


class MemoryStore:
    def __init__(self, db_path: str | os.PathLike[str] = DEFAULT_DB, initialize: bool = True):
        self.db_path = str(Path(db_path).expanduser())
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        if initialize:
            self.initialize()

    def _connect(self, readonly: bool = False) -> sqlite3.Connection:
        if readonly:
            uri = f"file:{Path(self.db_path).resolve()}?mode=ro"
            db = sqlite3.connect(uri, uri=True, timeout=10)
        else:
            db = sqlite3.connect(self.db_path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA busy_timeout = 10000")
        return db

    @contextmanager
    def connection(self, readonly: bool = False) -> Iterator[sqlite3.Connection]:
        db = self._connect(readonly=readonly)
        try:
            yield db
            if not readonly:
                db.commit()
        except Exception:
            if not readonly:
                db.rollback()
            raise
        finally:
            db.close()

    def initialize(self) -> None:
        with self.connection() as db:
            db.execute("PRAGMA journal_mode = WAL")
            db.execute("PRAGMA synchronous = NORMAL")
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS schema_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    current_task TEXT NOT NULL DEFAULT '',
                    open_loops_json TEXT NOT NULL DEFAULT '[]',
                    decisions_json TEXT NOT NULL DEFAULT '[]',
                    tone_state TEXT NOT NULL DEFAULT '',
                    active_project TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    closed_at TEXT
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('user','assistant','system','tool')),
                    speaker TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT 'chat',
                    legacy_fingerprint TEXT,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_legacy_fingerprint
                    ON messages(legacy_fingerprint)
                    WHERE legacy_fingerprint IS NOT NULL;
                CREATE INDEX IF NOT EXISTS idx_messages_session_id
                    ON messages(session_id, id);
                CREATE INDEX IF NOT EXISTS idx_messages_created_at
                    ON messages(created_at);

                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    memory_key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    normalized_value TEXT NOT NULL,
                    source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    confidence REAL NOT NULL DEFAULT 0.75 CHECK(confidence BETWEEN 0 AND 1),
                    salience REAL NOT NULL DEFAULT 0.5 CHECK(salience BETWEEN 0 AND 1),
                    explicitly_stated INTEGER NOT NULL DEFAULT 0 CHECK(explicitly_stated IN (0,1)),
                    sensitive INTEGER NOT NULL DEFAULT 0 CHECK(sensitive IN (0,1)),
                    pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    supersedes_id INTEGER REFERENCES memories(id) ON DELETE SET NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_confirmed_at TEXT,
                    last_used_at TEXT,
                    expires_at TEXT,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE INDEX IF NOT EXISTS idx_memories_active_key
                    ON memories(active, kind, memory_key);
                CREATE INDEX IF NOT EXISTS idx_memories_updated_at
                    ON memories(updated_at);

                CREATE TABLE IF NOT EXISTS memory_revisions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    memory_id INTEGER NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                    old_value TEXT NOT NULL,
                    new_value TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS memory_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
                    domain TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    content TEXT NOT NULL,
                    normalized_content TEXT NOT NULL,
                    source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    explicitly_stated INTEGER NOT NULL DEFAULT 1 CHECK(explicitly_stated IN (0,1)),
                    sensitive INTEGER NOT NULL DEFAULT 0 CHECK(sensitive IN (0,1)),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    salience REAL NOT NULL DEFAULT 0.5 CHECK(salience BETWEEN 0 AND 1),
                    occurred_at TEXT NOT NULL,
                    captured_at TEXT NOT NULL,
                    expires_at TEXT,
                    fingerprint TEXT NOT NULL UNIQUE,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE INDEX IF NOT EXISTS idx_memory_events_domain_time
                    ON memory_events(active, domain, occurred_at DESC);
                CREATE INDEX IF NOT EXISTS idx_memory_events_sensitive
                    ON memory_events(active, sensitive, occurred_at DESC);

                CREATE TABLE IF NOT EXISTS memory_entities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    entity_type TEXT NOT NULL,
                    canonical_name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    sensitive INTEGER NOT NULL DEFAULT 0 CHECK(sensitive IN (0,1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    UNIQUE(entity_type, normalized_name)
                );

                CREATE TABLE IF NOT EXISTS memory_relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    subject_entity_id INTEGER NOT NULL REFERENCES memory_entities(id) ON DELETE CASCADE,
                    predicate TEXT NOT NULL,
                    object_entity_id INTEGER REFERENCES memory_entities(id) ON DELETE CASCADE,
                    object_value TEXT NOT NULL DEFAULT '',
                    source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    confidence REAL NOT NULL DEFAULT 0.75 CHECK(confidence BETWEEN 0 AND 1),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE TABLE IF NOT EXISTS fact_watchlist (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    query TEXT NOT NULL,
                    query_key TEXT NOT NULL UNIQUE,
                    reason TEXT NOT NULL DEFAULT '',
                    cadence_seconds INTEGER NOT NULL DEFAULT 86400,
                    next_check_at TEXT NOT NULL,
                    last_checked_at TEXT,
                    last_status TEXT NOT NULL DEFAULT 'pending',
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE INDEX IF NOT EXISTS idx_fact_watchlist_due
                    ON fact_watchlist(active, next_check_at);

                CREATE TABLE IF NOT EXISTS report_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    report_type TEXT NOT NULL,
                    state_hash TEXT NOT NULL,
                    markdown_path TEXT NOT NULL DEFAULT '',
                    pdf_path TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE TABLE IF NOT EXISTS summaries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    summary_type TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source_start_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    source_end_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                    token_estimate INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS web_cache (
                    query_key TEXT PRIMARY KEY,
                    query TEXT NOT NULL,
                    content TEXT NOT NULL,
                    retrieved_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE TABLE IF NOT EXISTS project_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
                    action TEXT NOT NULL,
                    path TEXT NOT NULL DEFAULT '',
                    result TEXT NOT NULL DEFAULT '',
                    exit_code INTEGER,
                    before_hash TEXT,
                    after_hash TEXT,
                    created_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );

                CREATE TABLE IF NOT EXISTS memory_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS prompt_reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
                    input_tokens INTEGER NOT NULL,
                    compacted INTEGER NOT NULL DEFAULT 0,
                    report_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                    content,
                    speaker,
                    content='messages',
                    content_rowid='id'
                );

                CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                    memory_key,
                    value,
                    kind,
                    content='memories',
                    content_rowid='id'
                );

                CREATE VIRTUAL TABLE IF NOT EXISTS memory_events_fts USING fts5(
                    content,
                    domain,
                    event_type,
                    content='memory_events',
                    content_rowid='id'
                );

                CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                    INSERT INTO messages_fts(rowid, content, speaker)
                    VALUES (new.id, new.content, new.speaker);
                END;
                CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                    INSERT INTO messages_fts(messages_fts, rowid, content, speaker)
                    VALUES ('delete', old.id, old.content, old.speaker);
                END;
                CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                    INSERT INTO messages_fts(messages_fts, rowid, content, speaker)
                    VALUES ('delete', old.id, old.content, old.speaker);
                    INSERT INTO messages_fts(rowid, content, speaker)
                    VALUES (new.id, new.content, new.speaker);
                END;

                CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
                    INSERT INTO memories_fts(rowid, memory_key, value, kind)
                    VALUES (new.id, new.memory_key, new.value, new.kind);
                END;
                CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, memory_key, value, kind)
                    VALUES ('delete', old.id, old.memory_key, old.value, old.kind);
                END;
                CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, memory_key, value, kind)
                    VALUES ('delete', old.id, old.memory_key, old.value, old.kind);
                    INSERT INTO memories_fts(rowid, memory_key, value, kind)
                    VALUES (new.id, new.memory_key, new.value, new.kind);
                END;

                CREATE TRIGGER IF NOT EXISTS memory_events_ai AFTER INSERT ON memory_events BEGIN
                    INSERT INTO memory_events_fts(rowid, content, domain, event_type)
                    VALUES (new.id, new.content, new.domain, new.event_type);
                END;
                CREATE TRIGGER IF NOT EXISTS memory_events_ad AFTER DELETE ON memory_events BEGIN
                    INSERT INTO memory_events_fts(memory_events_fts, rowid, content, domain, event_type)
                    VALUES ('delete', old.id, old.content, old.domain, old.event_type);
                END;
                CREATE TRIGGER IF NOT EXISTS memory_events_au AFTER UPDATE ON memory_events BEGIN
                    INSERT INTO memory_events_fts(memory_events_fts, rowid, content, domain, event_type)
                    VALUES ('delete', old.id, old.content, old.domain, old.event_type);
                    INSERT INTO memory_events_fts(rowid, content, domain, event_type)
                    VALUES (new.id, new.content, new.domain, new.event_type);
                END;
                """
            )
            db.execute(
                "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (str(SCHEMA_VERSION),),
            )

    def set_setting(self, key: str, value: str) -> None:
        now = utc_now()
        with self.connection() as db:
            db.execute(
                "INSERT INTO settings(key, value, updated_at) VALUES(?,?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
                (key, value, now),
            )

    def get_setting(self, key: str, default: str = "") -> str:
        with self.connection(readonly=True) as db:
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return str(row["value"]) if row else default

    def create_session(self, title: str | None = None, activate: bool = True) -> dict[str, Any]:
        now = utc_now()
        session_id = uuid.uuid4().hex[:16]
        clean_title = (title or "New Intermix Session").strip()[:120] or "New Intermix Session"
        with self.connection() as db:
            db.execute(
                "INSERT INTO sessions(id,title,created_at,updated_at) VALUES(?,?,?,?)",
                (session_id, clean_title, now, now),
            )
        if activate:
            self.set_setting("active_session_id", session_id)
        return self.get_session(session_id) or {}

    def get_session(self, session_id: str) -> dict[str, Any] | None:
        with self.connection(readonly=True) as db:
            row = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        return self._session_dict(row) if row else None

    def get_active_session(self, create: bool = True) -> dict[str, Any] | None:
        session_id = self.get_setting("active_session_id")
        session = self.get_session(session_id) if session_id else None
        if session or not create:
            return session
        return self.create_session()

    def activate_session(self, session_id: str) -> bool:
        session = self.get_session(session_id)
        if not session:
            return False
        self.set_setting("active_session_id", session_id)
        with self.connection() as db:
            db.execute("UPDATE sessions SET closed_at=NULL, updated_at=? WHERE id=?", (utc_now(), session_id))
        return True

    def list_sessions(self, limit: int = 20) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT s.*, COUNT(m.id) AS message_count "
                "FROM sessions s LEFT JOIN messages m ON m.session_id=s.id "
                "GROUP BY s.id ORDER BY s.updated_at DESC LIMIT ?",
                (max(1, min(limit, 100)),),
            ).fetchall()
        return [self._session_dict(row) for row in rows]

    def update_session(
        self,
        session_id: str,
        *,
        title: str | None = None,
        summary: str | None = None,
        current_task: str | None = None,
        open_loops: Sequence[str] | None = None,
        decisions: Sequence[str] | None = None,
        tone_state: str | None = None,
        active_project: str | None = None,
    ) -> None:
        allowed: dict[str, Any] = {}
        if title is not None and title.strip():
            allowed["title"] = title.strip()[:120]
        if summary is not None:
            allowed["summary"] = summary.strip()[:2000]
        if current_task is not None:
            allowed["current_task"] = current_task.strip()[:600]
        if open_loops is not None:
            allowed["open_loops_json"] = _json([str(x)[:300] for x in open_loops][:12])
        if decisions is not None:
            allowed["decisions_json"] = _json([str(x)[:300] for x in decisions][:12])
        if tone_state is not None:
            allowed["tone_state"] = tone_state.strip()[:240]
        if active_project is not None:
            allowed["active_project"] = active_project.strip()[:160]
        if not allowed:
            return
        allowed["updated_at"] = utc_now()
        assignments = ", ".join(f"{key}=?" for key in allowed)
        values = list(allowed.values()) + [session_id]
        with self.connection() as db:
            db.execute(f"UPDATE sessions SET {assignments} WHERE id=?", values)

    @staticmethod
    def _session_dict(row: sqlite3.Row) -> dict[str, Any]:
        data = dict(row)
        data["open_loops"] = _json_list(data.pop("open_loops_json", "[]"))
        data["decisions"] = _json_list(data.pop("decisions_json", "[]"))
        return data

    def append_message(
        self,
        session_id: str,
        role: str,
        content: str,
        *,
        speaker: str | None = None,
        created_at: str | None = None,
        source: str = "chat",
        legacy_fingerprint: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        if role not in {"user", "assistant", "system", "tool"}:
            raise ValueError(f"Unsupported message role: {role}")
        clean = content.replace("\x00", "").strip()
        if not clean:
            raise ValueError("Cannot store an empty message")
        display = speaker or {
            "user": CONFIG.user_name,
            "assistant": CONFIG.assistant_name,
        }.get(role, role.title())
        now = created_at or utc_now()
        try:
            with self.connection() as db:
                cur = db.execute(
                    "INSERT INTO messages(session_id,role,speaker,content,created_at,token_estimate,source,legacy_fingerprint,metadata_json) "
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                    (
                        session_id,
                        role,
                        display[:80],
                        clean,
                        now,
                        estimate_tokens(clean),
                        source[:40],
                        legacy_fingerprint,
                        _json(metadata or {}),
                    ),
                )
                db.execute("UPDATE sessions SET updated_at=? WHERE id=?", (now, session_id))
                return int(cur.lastrowid)
        except sqlite3.IntegrityError:
            if not legacy_fingerprint:
                raise
            with self.connection(readonly=True) as db:
                row = db.execute(
                    "SELECT id FROM messages WHERE legacy_fingerprint=?",
                    (legacy_fingerprint,),
                ).fetchone()
            return int(row["id"]) if row else 0

    def get_messages(
        self,
        session_id: str,
        *,
        limit: int = 50,
        before_id: int | None = None,
        ascending: bool = True,
    ) -> list[dict[str, Any]]:
        where = "session_id=?"
        args: list[Any] = [session_id]
        if before_id is not None:
            where += " AND id < ?"
            args.append(before_id)
        args.append(max(1, min(limit, 1000)))
        with self.connection(readonly=True) as db:
            rows = db.execute(
                f"SELECT * FROM messages WHERE {where} ORDER BY id DESC LIMIT ?",
                args,
            ).fetchall()
        result = [dict(row) for row in rows]
        if ascending:
            result.reverse()
        return result

    def message_count(self, session_id: str) -> int:
        with self.connection(readonly=True) as db:
            return int(db.execute("SELECT COUNT(*) FROM messages WHERE session_id=?", (session_id,)).fetchone()[0])

    def content_exists(self, role: str, content: str, min_length: int = 40) -> bool:
        clean = normalize_text(content)
        if len(clean) < min_length:
            return False
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT content FROM messages WHERE role=? AND length(content)>=? ORDER BY id DESC LIMIT 1000",
                (role, min_length),
            ).fetchall()
        return any(normalize_text(row["content"]) == clean for row in rows)

    def search_messages(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        match = _fts_query(query)
        if not match:
            return []
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT m.*, bm25(messages_fts) AS rank "
                "FROM messages_fts JOIN messages m ON m.id=messages_fts.rowid "
                "WHERE messages_fts MATCH ? AND m.role IN ('user','assistant') "
                "ORDER BY rank ASC LIMIT ?",
                (match, max(1, min(limit, 30))),
            ).fetchall()
        return [dict(row) for row in rows]

    def upsert_memory(
        self,
        *,
        kind: str,
        memory_key: str,
        value: str,
        source_message_id: int | None,
        confidence: float = 0.75,
        salience: float = 0.5,
        explicitly_stated: bool = False,
        sensitive: bool = False,
        pinned: bool = False,
        expires_at: str | None = None,
        reason: str = "memory update",
        metadata: dict[str, Any] | None = None,
    ) -> int:
        clean_kind = re.sub(r"[^a-z0-9_-]+", "_", kind.casefold()).strip("_")[:50] or "user_fact"
        clean_key = re.sub(r"\s+", "_", memory_key.strip().casefold())[:120] or stable_hash(value)[:16]
        clean_value = value.replace("\x00", "").strip()[:4000]
        if not clean_value:
            raise ValueError("Memory value cannot be empty")
        confidence = max(0.0, min(float(confidence), 1.0))
        salience = max(0.0, min(float(salience), 1.0))
        normalized = normalize_text(clean_value)
        now = utc_now()

        with self.connection() as db:
            existing = db.execute(
                "SELECT * FROM memories WHERE active=1 AND kind=? AND memory_key=? ORDER BY id DESC LIMIT 1",
                (clean_kind, clean_key),
            ).fetchone()
            if existing and existing["normalized_value"] == normalized:
                reinforced = min(1.0, max(float(existing["confidence"]), confidence) + 0.03)
                db.execute(
                    "UPDATE memories SET confidence=?, salience=max(salience,?), explicitly_stated=max(explicitly_stated,?), "
                    "sensitive=max(sensitive,?), pinned=max(pinned,?), last_confirmed_at=?, updated_at=?, source_message_id=COALESCE(?,source_message_id) "
                    "WHERE id=?",
                    (
                        reinforced,
                        salience,
                        int(explicitly_stated),
                        int(sensitive),
                        int(pinned),
                        now,
                        now,
                        source_message_id,
                        existing["id"],
                    ),
                )
                return int(existing["id"])

            supersedes_id = int(existing["id"]) if existing else None
            if existing:
                db.execute("UPDATE memories SET active=0, updated_at=? WHERE id=?", (now, existing["id"]))
            cur = db.execute(
                "INSERT INTO memories(kind,memory_key,value,normalized_value,source_message_id,confidence,salience,"
                "explicitly_stated,sensitive,pinned,active,supersedes_id,created_at,updated_at,last_confirmed_at,expires_at,metadata_json) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    clean_kind,
                    clean_key,
                    clean_value,
                    normalized,
                    source_message_id,
                    confidence,
                    salience,
                    int(explicitly_stated),
                    int(sensitive),
                    int(pinned),
                    1,
                    supersedes_id,
                    now,
                    now,
                    now if explicitly_stated else None,
                    expires_at,
                    _json(metadata or {}),
                ),
            )
            new_id = int(cur.lastrowid)
            if existing:
                db.execute(
                    "INSERT INTO memory_revisions(memory_id,old_value,new_value,reason,source_message_id,created_at) "
                    "VALUES(?,?,?,?,?,?)",
                    (new_id, existing["value"], clean_value, reason[:240], source_message_id, now),
                )
            return new_id

    def search_memories(self, query: str, limit: int = 8, include_sensitive: bool = True) -> list[dict[str, Any]]:
        now = utc_now()
        match = _fts_query(query)
        sensitive_clause = "" if include_sensitive else " AND m.sensitive=0"
        if match:
            sql = (
                "SELECT m.*, bm25(memories_fts) AS rank FROM memories_fts "
                "JOIN memories m ON m.id=memories_fts.rowid "
                "WHERE memories_fts MATCH ? AND m.active=1 "
                "AND (m.expires_at IS NULL OR m.expires_at>?)"
                f"{sensitive_clause} ORDER BY rank ASC LIMIT 40"
            )
            args: tuple[Any, ...] = (match, now)
        else:
            sql = (
                "SELECT m.*, 10.0 AS rank FROM memories m WHERE m.active=1 "
                "AND (m.expires_at IS NULL OR m.expires_at>?)"
                f"{sensitive_clause} ORDER BY pinned DESC, salience DESC, updated_at DESC LIMIT 40"
            )
            args = (now,)
        with self.connection(readonly=True) as db:
            rows = db.execute(sql, args).fetchall()

        scored: list[dict[str, Any]] = []
        for row in rows:
            item = dict(row)
            rank = abs(float(item.get("rank") or 0.0))
            relevance = 1.0 / (1.0 + rank)
            score = (
                relevance * 0.52
                + float(item["salience"]) * 0.25
                + float(item["confidence"]) * 0.17
                + int(item["pinned"]) * 0.20
            )
            item["retrieval_score"] = score
            scored.append(item)
        scored.sort(key=lambda item: item["retrieval_score"], reverse=True)
        selected = scored[: max(1, min(limit, 30))]
        if selected:
            ids = [int(item["id"]) for item in selected]
            placeholders = ",".join("?" for _ in ids)
            with self.connection() as db:
                db.execute(f"UPDATE memories SET last_used_at=? WHERE id IN ({placeholders})", [now, *ids])
        return selected

    def list_memories(self, limit: int = 30, active_only: bool = True) -> list[dict[str, Any]]:
        where = "WHERE active=1" if active_only else ""
        with self.connection(readonly=True) as db:
            rows = db.execute(
                f"SELECT * FROM memories {where} ORDER BY pinned DESC, salience DESC, updated_at DESC LIMIT ?",
                (max(1, min(limit, 200)),),
            ).fetchall()
        return [dict(row) for row in rows]

    def pin_memory(self, memory_id: int, pinned: bool = True) -> bool:
        with self.connection() as db:
            cur = db.execute(
                "UPDATE memories SET pinned=?, updated_at=? WHERE id=?",
                (int(pinned), utc_now(), memory_id),
            )
            return cur.rowcount > 0

    def forget_memory(self, memory_id: int, purge: bool = False) -> bool:
        with self.connection() as db:
            if purge:
                cur = db.execute("DELETE FROM memories WHERE id=?", (memory_id,))
            else:
                cur = db.execute("UPDATE memories SET active=0, updated_at=? WHERE id=?", (utc_now(), memory_id))
            return cur.rowcount > 0

    def record_memory_event(
        self,
        *,
        session_id: str | None,
        domain: str,
        event_type: str,
        content: str,
        source_message_id: int | None,
        explicitly_stated: bool = True,
        sensitive: bool = False,
        salience: float = 0.5,
        occurred_at: str | None = None,
        expires_at: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        """Record an idempotent typed episode without inferring new claims."""
        clean_domain = re.sub(r"[^a-z0-9_-]+", "_", domain.casefold()).strip("_")[:50] or "personal"
        clean_type = re.sub(r"[^a-z0-9_-]+", "_", event_type.casefold()).strip("_")[:60] or "self_report"
        clean_content = content.replace("\x00", "").strip()[:4000]
        if not clean_content:
            raise ValueError("Memory event content cannot be empty")
        now = utc_now()
        event_time = occurred_at or now
        salience = max(0.0, min(float(salience), 1.0))
        fingerprint = stable_hash(
            str(source_message_id or ""),
            str(session_id or ""),
            clean_domain,
            clean_type,
            clean_content,
        )
        with self.connection() as db:
            db.execute(
                "INSERT OR IGNORE INTO memory_events("
                "session_id,domain,event_type,content,normalized_content,source_message_id,"
                "explicitly_stated,sensitive,active,salience,occurred_at,captured_at,expires_at,fingerprint,metadata_json"
                ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    session_id,
                    clean_domain,
                    clean_type,
                    clean_content,
                    normalize_text(clean_content),
                    source_message_id,
                    int(explicitly_stated),
                    int(sensitive),
                    1,
                    salience,
                    event_time,
                    now,
                    expires_at,
                    fingerprint,
                    _json(metadata or {}),
                ),
            )
            row = db.execute(
                "SELECT id FROM memory_events WHERE fingerprint=?",
                (fingerprint,),
            ).fetchone()
        return int(row["id"]) if row else 0

    def search_memory_events(
        self,
        query: str,
        *,
        domains: Sequence[str] | None = None,
        include_sensitive: bool = False,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        now = utc_now()
        domain_values = [
            re.sub(r"[^a-z0-9_-]+", "_", str(item).casefold()).strip("_")
            for item in (domains or [])
            if str(item).strip()
        ]
        clauses = ["e.active=1", "(e.expires_at IS NULL OR e.expires_at>?)"]
        args: list[Any] = [now]
        if not include_sensitive:
            clauses.append("e.sensitive=0")
        if domain_values:
            placeholders = ",".join("?" for _ in domain_values)
            clauses.append(f"e.domain IN ({placeholders})")
            args.extend(domain_values)
        match = _fts_query(query)
        if match:
            args = [match, *args, max(1, min(limit, 100))]
            sql = (
                "SELECT e.*, bm25(memory_events_fts) AS rank FROM memory_events_fts "
                "JOIN memory_events e ON e.id=memory_events_fts.rowid WHERE memory_events_fts MATCH ? AND "
                + " AND ".join(clauses)
                + " ORDER BY rank ASC, e.salience DESC, e.occurred_at DESC LIMIT ?"
            )
        else:
            args.append(max(1, min(limit, 100)))
            sql = (
                "SELECT e.*, 10.0 AS rank FROM memory_events e WHERE "
                + " AND ".join(clauses)
                + " ORDER BY e.salience DESC, e.occurred_at DESC LIMIT ?"
            )
        with self.connection(readonly=True) as db:
            rows = db.execute(sql, args).fetchall()
        return [dict(row) for row in rows]

    def list_memory_events(
        self,
        *,
        domain: str | None = None,
        include_sensitive: bool = True,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        clauses = ["active=1"]
        args: list[Any] = []
        if domain:
            clauses.append("domain=?")
            args.append(re.sub(r"[^a-z0-9_-]+", "_", domain.casefold()).strip("_"))
        if not include_sensitive:
            clauses.append("sensitive=0")
        args.append(max(1, min(limit, 500)))
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT * FROM memory_events WHERE " + " AND ".join(clauses)
                + " ORDER BY occurred_at DESC, id DESC LIMIT ?",
                args,
            ).fetchall()
        return [dict(row) for row in rows]

    def forget_memory_event(self, event_id: int, purge: bool = False) -> bool:
        with self.connection() as db:
            if purge:
                cur = db.execute("DELETE FROM memory_events WHERE id=?", (event_id,))
            else:
                cur = db.execute("UPDATE memory_events SET active=0 WHERE id=?", (event_id,))
            return cur.rowcount > 0

    def memory_domain_counts(self) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT domain, COUNT(*) AS events, SUM(sensitive) AS sensitive "
                "FROM memory_events WHERE active=1 GROUP BY domain ORDER BY events DESC, domain ASC"
            ).fetchall()
        return [dict(row) for row in rows]

    def add_summary(
        self,
        session_id: str,
        content: str,
        *,
        summary_type: str = "checkpoint",
        start_message_id: int | None = None,
        end_message_id: int | None = None,
    ) -> int:
        clean = content.strip()[:6000]
        with self.connection() as db:
            cur = db.execute(
                "INSERT INTO summaries(session_id,summary_type,content,source_start_message_id,source_end_message_id,token_estimate,created_at) "
                "VALUES(?,?,?,?,?,?,?)",
                (
                    session_id,
                    summary_type[:40],
                    clean,
                    start_message_id,
                    end_message_id,
                    estimate_tokens(clean),
                    utc_now(),
                ),
            )
            return int(cur.lastrowid)

    def cache_web(self, query: str, content: str, expires_at: str, metadata: dict[str, Any] | None = None) -> None:
        key = stable_hash(query)
        with self.connection() as db:
            db.execute(
                "INSERT INTO web_cache(query_key,query,content,retrieved_at,expires_at,metadata_json) VALUES(?,?,?,?,?,?) "
                "ON CONFLICT(query_key) DO UPDATE SET content=excluded.content,retrieved_at=excluded.retrieved_at,"
                "expires_at=excluded.expires_at,metadata_json=excluded.metadata_json",
                (key, query.strip(), content, utc_now(), expires_at, _json(metadata or {})),
            )

    def get_cached_web(self, query: str) -> str | None:
        key = stable_hash(query)
        with self.connection(readonly=True) as db:
            row = db.execute(
                "SELECT content FROM web_cache WHERE query_key=? AND expires_at>?",
                (key, utc_now()),
            ).fetchone()
        return str(row["content"]) if row else None

    def upsert_fact_watch(
        self,
        query: str,
        *,
        reason: str = "volatile grounded query",
        cadence_seconds: int = 86400,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        clean = re.sub(r"\s+", " ", query).strip()[:500]
        if not clean:
            raise ValueError("Watch query cannot be empty")
        cadence = max(900, min(int(cadence_seconds), 30 * 86400))
        key = stable_hash(clean)
        now_dt = datetime.now(timezone.utc)
        next_check = datetime.fromtimestamp(now_dt.timestamp() + cadence, timezone.utc).replace(microsecond=0).isoformat()
        with self.connection() as db:
            db.execute(
                "INSERT INTO fact_watchlist(query,query_key,reason,cadence_seconds,next_check_at,last_status,active,metadata_json) "
                "VALUES(?,?,?,?,?,'ready',1,?) ON CONFLICT(query_key) DO UPDATE SET "
                "reason=excluded.reason,cadence_seconds=excluded.cadence_seconds,active=1,metadata_json=excluded.metadata_json",
                (clean, key, reason[:240], cadence, next_check, _json(metadata or {})),
            )
            row = db.execute("SELECT id FROM fact_watchlist WHERE query_key=?", (key,)).fetchone()
        return int(row["id"]) if row else 0

    def due_fact_watches(self, limit: int = 2) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT * FROM fact_watchlist WHERE active=1 AND next_check_at<=? "
                "ORDER BY next_check_at ASC LIMIT ?",
                (utc_now(), max(1, min(limit, 10))),
            ).fetchall()
        return [dict(row) for row in rows]

    def list_fact_watches(self, limit: int = 50) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT * FROM fact_watchlist WHERE active=1 ORDER BY next_check_at ASC LIMIT ?",
                (max(1, min(limit, 200)),),
            ).fetchall()
        return [dict(row) for row in rows]

    def complete_fact_watch(
        self,
        watch_id: int,
        *,
        status: str,
        metadata: dict[str, Any] | None = None,
    ) -> bool:
        with self.connection() as db:
            row = db.execute(
                "SELECT cadence_seconds FROM fact_watchlist WHERE id=?",
                (watch_id,),
            ).fetchone()
            if not row:
                return False
            cadence = max(900, int(row["cadence_seconds"]))
            now_dt = datetime.now(timezone.utc)
            next_check = datetime.fromtimestamp(now_dt.timestamp() + cadence, timezone.utc).replace(microsecond=0).isoformat()
            db.execute(
                "UPDATE fact_watchlist SET last_checked_at=?,next_check_at=?,last_status=?,metadata_json=? WHERE id=?",
                (utc_now(), next_check, status[:80], _json(metadata or {}), watch_id),
            )
        return True

    def deactivate_fact_watch(self, watch_id: int) -> bool:
        with self.connection() as db:
            cur = db.execute("UPDATE fact_watchlist SET active=0 WHERE id=?", (watch_id,))
            return cur.rowcount > 0

    def record_report_run(
        self,
        *,
        report_type: str,
        state_hash: str,
        markdown_path: str,
        pdf_path: str,
        status: str,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        with self.connection() as db:
            cur = db.execute(
                "INSERT INTO report_runs(report_type,state_hash,markdown_path,pdf_path,status,created_at,metadata_json) "
                "VALUES(?,?,?,?,?,?,?)",
                (
                    report_type[:60],
                    state_hash[:128],
                    markdown_path[:1000],
                    pdf_path[:1000],
                    status[:60],
                    utc_now(),
                    _json(metadata or {}),
                ),
            )
            return int(cur.lastrowid)

    def latest_report_run(self, report_type: str = "daily_status") -> dict[str, Any] | None:
        with self.connection(readonly=True) as db:
            row = db.execute(
                "SELECT * FROM report_runs WHERE report_type=? ORDER BY id DESC LIMIT 1",
                (report_type,),
            ).fetchone()
        return dict(row) if row else None

    def list_report_runs(self, limit: int = 30) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT * FROM report_runs ORDER BY id DESC LIMIT ?",
                (max(1, min(limit, 200)),),
            ).fetchall()
        return [dict(row) for row in rows]

    def record_project_event(
        self,
        *,
        session_id: str | None,
        action: str,
        path: str = "",
        result: str = "",
        exit_code: int | None = None,
        before_hash: str | None = None,
        after_hash: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> int:
        with self.connection() as db:
            cur = db.execute(
                "INSERT INTO project_events(session_id,action,path,result,exit_code,before_hash,after_hash,created_at,metadata_json) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    session_id,
                    action[:80],
                    path[:500],
                    result[:4000],
                    exit_code,
                    before_hash,
                    after_hash,
                    utc_now(),
                    _json(metadata or {}),
                ),
            )
            return int(cur.lastrowid)

    def recent_project_events(self, limit: int = 10) -> list[dict[str, Any]]:
        with self.connection(readonly=True) as db:
            rows = db.execute(
                "SELECT * FROM project_events ORDER BY id DESC LIMIT ?",
                (max(1, min(limit, 100)),),
            ).fetchall()
        result = [dict(row) for row in rows]
        result.reverse()
        return result

    def record_prompt_report(self, session_id: str, input_tokens: int, compacted: bool, report: dict[str, Any]) -> None:
        with self.connection() as db:
            db.execute(
                "INSERT INTO prompt_reports(session_id,input_tokens,compacted,report_json,created_at) VALUES(?,?,?,?,?)",
                (session_id, input_tokens, int(compacted), _json(report), utc_now()),
            )

    def latest_prompt_report(self, session_id: str | None = None) -> dict[str, Any] | None:
        with self.connection(readonly=True) as db:
            if session_id:
                row = db.execute(
                    "SELECT * FROM prompt_reports WHERE session_id=? ORDER BY id DESC LIMIT 1",
                    (session_id,),
                ).fetchone()
            else:
                row = db.execute(
                    "SELECT * FROM prompt_reports ORDER BY id DESC LIMIT 1"
                ).fetchone()
        if not row:
            return None
        data = dict(row)
        try:
            data["report"] = json.loads(data.pop("report_json"))
        except json.JSONDecodeError:
            data["report"] = {}
        return data

    def status(self) -> dict[str, Any]:
        session = self.get_active_session(create=False)
        with self.connection(readonly=True) as db:
            counts = {
                "sessions": int(db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]),
                "messages": int(db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]),
                "memories": int(db.execute("SELECT COUNT(*) FROM memories WHERE active=1").fetchone()[0]),
                "sensitive": int(db.execute("SELECT COUNT(*) FROM memories WHERE active=1 AND sensitive=1").fetchone()[0]),
                "events": int(db.execute("SELECT COUNT(*) FROM memory_events WHERE active=1").fetchone()[0]),
                "sensitive_events": int(db.execute("SELECT COUNT(*) FROM memory_events WHERE active=1 AND sensitive=1").fetchone()[0]),
                "watchlist": int(db.execute("SELECT COUNT(*) FROM fact_watchlist WHERE active=1").fetchone()[0]),
                "pending_jobs": int(db.execute("SELECT COUNT(*) FROM memory_jobs WHERE status='pending'").fetchone()[0]),
            }
        counts["active_session"] = session["id"] if session else ""
        counts["active_title"] = session["title"] if session else ""
        counts["schema_version"] = SCHEMA_VERSION
        return counts

    def export_session_markdown(self, session_id: str, destination: str | os.PathLike[str]) -> str:
        session = self.get_session(session_id)
        if not session:
            raise ValueError("Session not found")
        messages = self.get_messages(session_id, limit=1000)
        lines = [f"# {session['title']}", "", f"Session: `{session_id}`", ""]
        if session.get("summary"):
            lines.extend(["## Checkpoint", "", session["summary"], ""])
        lines.extend(["## Conversation", ""])
        for message in messages:
            lines.extend([
                f"### {message['speaker']} - {message['created_at']}",
                "",
                message["content"],
                "",
            ])
        path = Path(destination).expanduser()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines), encoding="utf-8")
        return str(path)


__all__ = [
    "DEFAULT_DB",
    "MemoryStore",
    "SCHEMA_VERSION",
    "estimate_tokens",
    "normalize_text",
    "stable_hash",
    "utc_now",
]
