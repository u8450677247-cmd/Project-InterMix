"""Failure-tolerant Cortex client with a local immutable event outbox."""

from __future__ import annotations

import json
import os
import sqlite3
import ssl
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Mapping, Sequence

from .models import EventEnvelope, ValidationError, canonical_json, now_ms


class LibrarianClientError(RuntimeError):
    pass


class LibrarianClient:
    def __init__(
        self,
        base_url: str,
        *,
        node_id: str,
        token: str = "",
        outbox_path: str | os.PathLike[str],
        timeout_seconds: float = 10.0,
        allow_insecure_lan: bool = False,
        ssl_context: ssl.SSLContext | None = None,
    ) -> None:
        parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValidationError("Librarian URL must be HTTP(S) with a hostname")
        if parsed.username is not None or parsed.password is not None:
            raise ValidationError("Librarian URL must not contain credentials")
        if parsed.query or parsed.fragment:
            raise ValidationError("Librarian base URL must not contain a query or fragment")
        loopback = parsed.hostname in {"127.0.0.1", "::1", "localhost"}
        if parsed.scheme != "https" and not loopback and not allow_insecure_lan:
            raise ValidationError("non-loopback Librarian transport must use HTTPS or an explicit test override")
        self.base_url = base_url.rstrip("/")
        self.node_id = node_id
        self.token = token
        self.outbox_path = str(Path(outbox_path).expanduser().resolve(strict=False))
        self.timeout_seconds = max(0.25, min(float(timeout_seconds), 120.0))
        self.ssl_context = ssl_context
        self._lock = threading.RLock()
        Path(self.outbox_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize_outbox()

    def _connect(self) -> sqlite3.Connection:
        database = sqlite3.connect(self.outbox_path, timeout=10)
        database.row_factory = sqlite3.Row
        database.execute("PRAGMA journal_mode=WAL")
        database.execute("PRAGMA synchronous=FULL")
        return database

    def _initialize_outbox(self) -> None:
        with self._connect() as database:
            database.executescript(
                """
                CREATE TABLE IF NOT EXISTS client_meta(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS event_outbox(
                    global_event_id TEXT PRIMARY KEY,
                    envelope_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT
                );
                """
            )
            database.execute(
                "INSERT OR IGNORE INTO client_meta(key,value) VALUES('next_sequence','1')"
            )

    def _next_sequence(self) -> int:
        with self._lock, self._connect() as database:
            database.execute("BEGIN IMMEDIATE")
            row = database.execute(
                "SELECT value FROM client_meta WHERE key='next_sequence'"
            ).fetchone()
            sequence = int(row[0])
            database.execute(
                "UPDATE client_meta SET value=? WHERE key='next_sequence'", (str(sequence + 1),)
            )
            database.commit()
            return sequence

    def create_event(
        self,
        *,
        actor: str,
        kind: str,
        content: str,
        payload: Mapping[str, Any] | None = None,
        parent_ids: Sequence[str] = (),
    ) -> EventEnvelope:
        return EventEnvelope.from_mapping(
            {
                "global_event_id": str(uuid.uuid4()),
                "node_id": self.node_id,
                "node_sequence": self._next_sequence(),
                "source_time_ms": now_ms(),
                "actor": actor,
                "kind": kind,
                "content": content,
                "payload": dict(payload or {}),
                "parent_ids": list(parent_ids),
                "schema_version": 1,
            }
        )

    def submit_event(self, event: EventEnvelope, *, queue_on_failure: bool = True) -> dict[str, Any]:
        try:
            result = self._request("POST", "/v1/events", event.as_mapping())
        except (OSError, LibrarianClientError) as failure:
            if not queue_on_failure:
                raise
            self._queue(event, str(failure))
            return {"status": "queued", "global_event_id": event.global_event_id}
        if result.get("status") == "conflict":
            raise LibrarianClientError("Librarian rejected an event identity conflict")
        self._remove_queued(event.global_event_id)
        return result

    def _queue(self, event: EventEnvelope, error: str) -> None:
        with self._lock, self._connect() as database:
            database.execute(
                """
                INSERT INTO event_outbox(
                    global_event_id,envelope_json,created_at_ms,attempts,last_error
                ) VALUES(?,?,?,?,?)
                ON CONFLICT(global_event_id) DO UPDATE SET
                    attempts=event_outbox.attempts+1,last_error=excluded.last_error
                """,
                (
                    event.global_event_id,
                    canonical_json(event.as_mapping()),
                    now_ms(),
                    1,
                    error[:2048],
                ),
            )

    def _remove_queued(self, global_event_id: str) -> None:
        with self._lock, self._connect() as database:
            database.execute(
                "DELETE FROM event_outbox WHERE global_event_id=?", (global_event_id,)
            )

    def flush_outbox(self, *, maximum: int = 128) -> dict[str, int]:
        maximum = max(1, min(int(maximum), 128))
        with self._connect() as database:
            rows = database.execute(
                "SELECT envelope_json FROM event_outbox ORDER BY created_at_ms LIMIT ?", (maximum,)
            ).fetchall()
        sent = 0
        failed = 0
        for row in rows:
            envelope = EventEnvelope.from_mapping(json.loads(str(row[0])))
            try:
                self.submit_event(envelope, queue_on_failure=False)
                sent += 1
            except (OSError, LibrarianClientError):
                self._queue(envelope, "flush failed")
                failed += 1
                break
        return {"sent": sent, "failed": failed, "remaining": self.outbox_count()}

    def outbox_count(self) -> int:
        with self._connect() as database:
            return int(database.execute("SELECT COUNT(*) FROM event_outbox").fetchone()[0])

    def submit_memory_delta(self, source_event_global_id: str, delta: Mapping[str, Any]) -> dict[str, Any]:
        return self._request(
            "POST",
            "/v1/memory/delta",
            {"source_event_global_id": source_event_global_id, "delta": dict(delta)},
        )

    def compile_context(
        self, query: str, *, token_budget: int = 2400, session_id: str = ""
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            "/v1/context/compile",
            {"query": query, "token_budget": token_budget, "session_id": session_id},
        )

    def query_memories(self, query: str, *, limit: int = 32) -> dict[str, Any]:
        return self._request(
            "POST",
            "/v1/memory/query",
            {"query": query, "limit": limit},
        )

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
    ) -> dict[str, Any]:
        request: dict[str, Any] = {
            "schema_version": 1,
            "job_id": job_id or str(uuid.uuid4()),
            "kind": kind,
            "payload": dict(payload),
            "preferred_role": preferred_role,
            "priority": priority,
            "min_free_ram_mib": min_free_ram_mib,
            "requires_charging": requires_charging,
        }
        if source_event_global_id is not None:
            request["source_event_global_id"] = source_event_global_id
        if not_before_ms is not None:
            request["not_before_ms"] = not_before_ms
        if max_temp_c is not None:
            request["max_temp_c"] = max_temp_c
        return self._request("POST", "/v1/jobs", request)

    def get_job(self, job_id: str) -> dict[str, Any]:
        return self._request("GET", f"/v1/jobs/{job_id}", None)

    def submit_job_result(
        self,
        job_id: str,
        result: Mapping[str, Any],
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            f"/v1/jobs/{job_id}/result",
            {"schema_version": 1, "result": dict(result)},
        )

    def heartbeat(
        self,
        state: Mapping[str, Any],
        *,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        payload = dict(state)
        if "request_id" in payload or "schema_version" in payload:
            raise ValidationError("heartbeat state cannot override protocol identity fields")
        payload["request_id"] = request_id or str(uuid.uuid4())
        payload["schema_version"] = 1
        payload.setdefault("observed_at_ms", now_ms())
        return self._request("POST", "/v1/node/heartbeat", payload)

    def request_snapshot(
        self,
        *,
        reason: str,
        replicate: bool = False,
        archive_node_id: str = "archive-ds215j",
        request_id: str | None = None,
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            "/v1/snapshot",
            {
                "schema_version": 1,
                "request_id": request_id or str(uuid.uuid4()),
                "reason": reason,
                "replicate": replicate,
                "archive_node_id": archive_node_id,
            },
        )

    def health(self) -> dict[str, Any]:
        return self._request("GET", "/v1/health", None)

    def _request(
        self, method: str, path: str, payload: Mapping[str, Any] | None
    ) -> dict[str, Any]:
        data = None if payload is None else (canonical_json(payload) + "\n").encode("utf-8")
        headers = {"Accept": "application/json", "X-Intermix-Node": self.node_id}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(
                request, timeout=self.timeout_seconds, context=self.ssl_context
            ) as response:
                raw = response.read(512 * 1024 + 1)
                if len(raw) > 512 * 1024:
                    raise LibrarianClientError("Librarian response exceeded 512 KiB")
                decoded = json.loads(raw.decode("utf-8"))
                if not isinstance(decoded, dict):
                    raise LibrarianClientError("Librarian response was not an object")
                return decoded
        except urllib.error.HTTPError as failure:
            raw = failure.read(16 * 1024)
            try:
                message = json.loads(raw.decode("utf-8")).get("error", "request rejected")
            except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
                message = "request rejected"
            raise LibrarianClientError(f"Librarian HTTP {failure.code}: {message}") from failure
