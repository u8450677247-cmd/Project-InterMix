"""Bounded JSON/HTTP service for the LIBRARIAN-01 protocol."""

from __future__ import annotations

import hmac
import json
import socket
import threading
import traceback
from collections.abc import Collection
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping
from urllib.parse import parse_qs, urlsplit

from .health import HEALTH_STATES, HealthReporter
from .models import EventEnvelope, ValidationError, canonical_json, reject_unknown
from .snapshots import FilesystemArchiveTarget, SnapshotManager
from .store import ContinuityStore


MAX_HTTP_BODY_BYTES = 256 * 1024
MAX_HTTP_PATH_BYTES = 8 * 1024
MAX_HTTP_WORKERS = 16
HTTP_READ_TIMEOUT_SECONDS = 15


def _require_schema_version(payload: Mapping[str, Any], operation: str) -> None:
    version = payload.get("schema_version")
    if isinstance(version, bool) or not isinstance(version, int) or version != 1:
        raise ValidationError(f"{operation} schema_version must be 1")


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """Keep slow or failing peers from spawning an unbounded thread set."""

    daemon_threads = True
    request_queue_size = 32

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self._request_slots = threading.BoundedSemaphore(MAX_HTTP_WORKERS)
        super().__init__(*args, **kwargs)

    def process_request(self, request: Any, client_address: Any) -> None:
        self._request_slots.acquire()
        try:
            super().process_request(request, client_address)
        except Exception:
            self._request_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


class LibrarianApplication:
    def __init__(
        self,
        store: ContinuityStore,
        *,
        health: HealthReporter | None = None,
        snapshots: SnapshotManager | None = None,
        archive_target: FilesystemArchiveTarget | None = None,
    ) -> None:
        self.store = store
        self.health = health or HealthReporter(store)
        self.snapshots = snapshots
        self.archive_target = archive_target

    def dispatch(
        self,
        method: str,
        raw_path: str,
        body: Mapping[str, Any] | None,
        *,
        authenticated_node_id: str | None,
    ) -> tuple[int, Mapping[str, Any]]:
        parsed = urlsplit(raw_path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query, keep_blank_values=False)
        payload = dict(body or {})

        if method == "GET" and path == "/v1/capabilities":
            return HTTPStatus.OK, {
                "service": "intermix-librarian",
                "protocol_version": 1,
                "lattice_schema_version": self.store.schema_version(),
                "operations": [
                    "events.ingest",
                    "events.batch_ingest",
                    "memory.delta",
                    "memory.query",
                    "context.compile",
                    "state.read",
                    "goals.read",
                    "friction.read",
                    "jobs.enqueue",
                    "jobs.read",
                    "jobs.result",
                    "health",
                    "node.heartbeat",
                    "snapshot.request",
                ],
                "embeddings_required": False,
                "health_states": list(HEALTH_STATES),
            }
        if method == "GET" and path == "/v1/health":
            return HTTPStatus.OK, self.health.report()
        if method == "POST" and path == "/v1/events":
            node_id = self._authenticated_node(authenticated_node_id)
            event = EventEnvelope.from_mapping(payload)
            if event.node_id != node_id:
                raise ValidationError("event origin must match the authenticated node")
            result = self.store.ingest_event(event)
            status = HTTPStatus.CONFLICT if result.status == "conflict" else HTTPStatus.OK
            return status, result.as_mapping()
        if method == "POST" and path == "/v1/events/batch":
            node_id = self._authenticated_node(authenticated_node_id)
            reject_unknown(payload, {"events"}, "event batch")
            raw_events = payload.get("events")
            if not isinstance(raw_events, list):
                raise ValidationError("events must be a list")
            events = [EventEnvelope.from_mapping(item) for item in raw_events]
            if any(event.node_id != node_id for event in events):
                raise ValidationError("every event origin must match the authenticated node")
            results = self.store.ingest_batch([event.as_mapping() for event in events])
            status = (
                HTTPStatus.CONFLICT
                if any(item.status == "conflict" for item in results)
                else HTTPStatus.OK
            )
            return status, {"results": [item.as_mapping() for item in results]}
        if method == "POST" and path == "/v1/memory/delta":
            node_id = self._authenticated_node(authenticated_node_id)
            source_event = payload.pop("source_event_global_id", None)
            delta = payload.pop("delta", None)
            if payload:
                raise ValidationError("memory delta request contains unsupported fields")
            if not isinstance(delta, Mapping):
                raise ValidationError("delta must be an object")
            return HTTPStatus.OK, self.store.apply_memory_delta(
                source_event_global_id=source_event,
                actor_node_id=node_id,
                delta=delta,
            )
        if method == "GET" and path == "/v1/state":
            scope = query.get("scope", [None])[0]
            return HTTPStatus.OK, {"state": self.store.list_state(scope=scope)}
        if method == "GET" and path == "/v1/goals":
            include_all = query.get("all", ["false"])[0].casefold() in {"1", "true", "yes"}
            return HTTPStatus.OK, {"goals": self.store.list_goals(active_only=not include_all)}
        if method == "GET" and path == "/v1/friction":
            include_all = query.get("all", ["false"])[0].casefold() in {"1", "true", "yes"}
            return HTTPStatus.OK, {"friction": self.store.list_friction(open_only=not include_all)}
        if method == "POST" and path == "/v1/memory/query":
            allowed = {"query", "limit"}
            if set(payload) - allowed:
                raise ValidationError("memory query contains unsupported fields")
            limit = payload.get("limit", 32)
            if isinstance(limit, bool) or not isinstance(limit, int):
                raise ValidationError("memory query limit must be an integer")
            return HTTPStatus.OK, {
                "memories": self.store.search_memories(
                    payload.get("query", ""), limit=limit
                )
            }
        if method == "POST" and path == "/v1/context/compile":
            allowed = {"query", "token_budget", "session_id"}
            if set(payload) - allowed:
                raise ValidationError("context request contains unsupported fields")
            token_budget = payload.get("token_budget", 2400)
            if isinstance(token_budget, bool) or not isinstance(token_budget, int):
                raise ValidationError("context token_budget must be an integer")
            return HTTPStatus.OK, self.store.compile_context(
                query=payload.get("query", ""),
                token_budget=token_budget,
                session_id=payload.get("session_id", ""),
            )
        if method == "POST" and path == "/v1/jobs":
            node_id = self._authenticated_node(authenticated_node_id)
            reject_unknown(
                payload,
                {
                    "kind",
                    "payload",
                    "preferred_role",
                    "priority",
                    "source_event_global_id",
                    "job_id",
                    "not_before_ms",
                    "min_free_ram_mib",
                    "max_temp_c",
                    "requires_charging",
                    "schema_version",
                },
                "job request",
            )
            _require_schema_version(payload, "job request")
            if payload.get("job_id") is None:
                raise ValidationError("job request requires job_id for idempotency")
            requires_charging = payload.get("requires_charging", False)
            if not isinstance(requires_charging, bool):
                raise ValidationError("requires_charging must be a boolean")
            job_id = self.store.enqueue_job(
                kind=payload.get("kind", ""),
                payload=payload.get("payload", {}),
                preferred_role=payload.get("preferred_role", "librarian"),
                priority=payload.get("priority", 0.5),
                source_event_global_id=payload.get("source_event_global_id"),
                job_id=payload.get("job_id"),
                not_before_ms=payload.get("not_before_ms"),
                min_free_ram_mib=payload.get("min_free_ram_mib", 0),
                max_temp_c=payload.get("max_temp_c"),
                requires_charging=requires_charging,
            )
            return HTTPStatus.ACCEPTED, {"job_id": job_id, "submitted_by": node_id}
        if path.startswith("/v1/jobs/"):
            suffix = path[len("/v1/jobs/") :]
            if suffix.endswith("/result") and method == "POST":
                job_id = suffix[: -len("/result")]
                node_id = self._authenticated_node(authenticated_node_id)
                reject_unknown(payload, {"result", "schema_version"}, "job result request")
                _require_schema_version(payload, "job result request")
                result = payload.get("result")
                if not isinstance(result, Mapping):
                    raise ValidationError("job result must be an object")
                self.store.complete_job(job_id=job_id, owner_node_id=node_id, result=result)
                return HTTPStatus.OK, {"job_id": job_id, "state": "done"}
            if method == "GET" and "/" not in suffix:
                job = self.store.get_job(suffix)
                if job is None:
                    return HTTPStatus.NOT_FOUND, {"error": "job not found"}
                return HTTPStatus.OK, job
        if method == "POST" and path == "/v1/node/heartbeat":
            node_id = self._authenticated_node(authenticated_node_id)
            if payload.get("request_id") is None:
                raise ValidationError("heartbeat requires request_id for idempotency")
            _require_schema_version(payload, "heartbeat")
            return HTTPStatus.OK, self.store.record_heartbeat(node_id, payload)
        if method == "POST" and path == "/v1/snapshot":
            self._authenticated_node(authenticated_node_id)
            reject_unknown(
                payload,
                {"reason", "archive_node_id", "replicate", "request_id", "schema_version"},
                "snapshot request",
            )
            _require_schema_version(payload, "snapshot request")
            if payload.get("request_id") is None:
                raise ValidationError("snapshot request requires request_id for idempotency")
            replicate = payload.get("replicate", False)
            if not isinstance(replicate, bool):
                raise ValidationError("snapshot replicate must be a boolean")
            if self.snapshots is None:
                return HTTPStatus.SERVICE_UNAVAILABLE, {"error": "snapshot manager is not configured"}
            artifact = self.snapshots.create(
                reason=payload.get("reason", "manual request"),
                archive_node_id=payload.get("archive_node_id", "archive-ds215j"),
                request_id=payload.get("request_id"),
            )
            response = artifact.as_mapping()
            if replicate:
                if self.archive_target is None:
                    response["replication"] = "queued-no-target"
                else:
                    response["archive_manifest"] = self.snapshots.replicate(
                        artifact.snapshot_id,
                        self.archive_target,
                        archive_node_id=str(payload.get("archive_node_id", "archive-ds215j")),
                    )
                    response["replication"] = "verified"
            return HTTPStatus.CREATED, response
        return HTTPStatus.NOT_FOUND, {"error": "unknown Librarian operation"}

    def _authenticated_node(self, node_id: str | None) -> str:
        if not node_id:
            raise ValidationError("X-Intermix-Node is required for this operation")
        if node_id == self.store.node_id:
            raise ValidationError("the Librarian authority cannot be claimed over HTTP")
        node = self.store.node(node_id)
        if not node or not node["enabled"]:
            raise ValidationError("authenticated node is not registered and enabled")
        if node["node_role"] in {"librarian", "archive"}:
            raise ValidationError("this node role cannot act as a remote service client")
        return node_id


def build_server(
    application: LibrarianApplication,
    *,
    host: str = "127.0.0.1",
    port: int = 8765,
    bearer_token: str = "",
    trusted_overlay: bool = False,
    allowed_node_ids: Collection[str] | None = None,
) -> ThreadingHTTPServer:
    if host not in {"127.0.0.1", "::1", "localhost"}:
        if not bearer_token:
            raise ValidationError("a bearer token is required for a non-loopback listener")
        if not trusted_overlay:
            raise ValidationError(
                "non-loopback HTTP requires an explicitly trusted encrypted overlay; "
                "otherwise keep Librarian on loopback behind a TLS proxy"
            )
    if bearer_token and len(bearer_token.encode("utf-8")) < 32:
        raise ValidationError("bearer token must contain at least 32 UTF-8 bytes")
    if isinstance(allowed_node_ids, (str, bytes)):
        raise ValidationError("allowed_node_ids must be a collection of node IDs")
    allowed_nodes = None if allowed_node_ids is None else frozenset(allowed_node_ids)
    if allowed_nodes is not None:
        for node_id in allowed_nodes:
            node = application.store.node(node_id)
            if (
                not node
                or not node["enabled"]
                or node["node_role"] in {"librarian", "archive"}
            ):
                raise ValidationError("allowed service nodes must be registered remote clients")

    class Handler(BaseHTTPRequestHandler):
        server_version = "IntermixLibrarian/1"
        sys_version = ""

        def setup(self) -> None:
            super().setup()
            self.connection.settimeout(HTTP_READ_TIMEOUT_SECONDS)

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
            self._handle("GET")

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
            self._handle("POST")

        def _handle(self, method: str) -> None:
            if len(self.path.encode("utf-8", "replace")) > MAX_HTTP_PATH_BYTES:
                self._respond(HTTPStatus.REQUEST_URI_TOO_LONG, {"error": "request path is too long"})
                return
            if bearer_token:
                supplied = self.headers.get("Authorization", "")
                expected = f"Bearer {bearer_token}"
                if not hmac.compare_digest(supplied, expected):
                    self._respond(HTTPStatus.UNAUTHORIZED, {"error": "authentication required"})
                    return
            supplied_node = self.headers.get("X-Intermix-Node")
            if allowed_nodes is not None:
                if not supplied_node or supplied_node not in allowed_nodes:
                    self._respond(HTTPStatus.FORBIDDEN, {"error": "node is not permitted"})
                    return
            try:
                body = self._read_body() if method == "POST" else None
                status, response = application.dispatch(
                    method,
                    self.path,
                    body,
                    authenticated_node_id=supplied_node,
                )
            except ValidationError as failure:
                self._respond(HTTPStatus.BAD_REQUEST, {"error": str(failure)})
                return
            except (ValueError, TypeError) as failure:
                self._respond(HTTPStatus.BAD_REQUEST, {"error": f"invalid request: {failure}"})
                return
            except Exception:
                # Never echo exception text, paths, tokens, or request content to a remote peer.
                traceback.print_exc()
                self._respond(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal Librarian failure"})
                return
            self._respond(status, response)

        def _read_body(self) -> Mapping[str, Any]:
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                raise ValidationError("Content-Length is required")
            try:
                length = int(raw_length)
            except ValueError as failure:
                raise ValidationError("Content-Length is invalid") from failure
            if length < 0 or length > MAX_HTTP_BODY_BYTES:
                raise ValidationError(f"request body exceeds {MAX_HTTP_BODY_BYTES} bytes")
            content_type = self.headers.get("Content-Type", "").partition(";")[0].strip().casefold()
            if content_type != "application/json":
                raise ValidationError("Content-Type must be application/json")
            try:
                decoded = json.loads(self.rfile.read(length).decode("utf-8"))
            except socket.timeout as failure:
                raise ValidationError("request body timed out") from failure
            except (UnicodeDecodeError, json.JSONDecodeError) as failure:
                raise ValidationError("request body is not valid UTF-8 JSON") from failure
            if not isinstance(decoded, Mapping):
                raise ValidationError("request body must be a JSON object")
            return decoded

        def _respond(self, status: int, response: Mapping[str, Any]) -> None:
            body = (canonical_json(response) + "\n").encode("utf-8")
            self.send_response(int(status))
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:
            # Request paths are useful; authorization headers and bodies are never logged.
            super().log_message(format, *args)

    return BoundedThreadingHTTPServer((host, int(port)), Handler)
