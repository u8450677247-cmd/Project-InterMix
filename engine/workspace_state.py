"""Durable and recoverable workspace file operations for Project Intermix.

The workspace is collaborative rather than opaque: every overwrite receives a
content-addressed checkpoint, generated deletes become approval requests, and
all metadata is written atomically beneath ``.intermix``.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from runtime_config import CONFIG


WORKSPACE_DIR = CONFIG.workspace_dir.resolve(strict=False)
META_DIR = WORKSPACE_DIR / ".intermix"
CHECKPOINT_DIR = META_DIR / "checkpoints"
CHECKPOINT_BLOBS = CHECKPOINT_DIR / "blobs"
TRASH_DIR = META_DIR / "trash"
PENDING_DELETIONS = META_DIR / "pending_deletions.json"
WORKSPACE_EVENTS = META_DIR / "workspace_events.jsonl"

MAX_FILE_BYTES = 1_000_000
MAX_EDITOR_BYTES = 500_000
MAX_READ_BYTES = 120_000

_STATE_LOCK = threading.RLock()


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def ensure_workspace() -> None:
    WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)
    META_DIR.mkdir(parents=True, exist_ok=True)
    CHECKPOINT_BLOBS.mkdir(parents=True, exist_ok=True)
    TRASH_DIR.mkdir(parents=True, exist_ok=True)


def safe_path(raw_path: str | os.PathLike[str], *, must_exist: bool = False) -> Path:
    """Resolve a relative workspace path without permitting symlink escapes."""
    clean = str(raw_path).strip()
    if not clean or "\x00" in clean:
        raise ValueError("Empty or invalid workspace path")
    supplied = Path(clean)
    if supplied.is_absolute():
        candidate = supplied.resolve(strict=False)
    else:
        candidate = (WORKSPACE_DIR / supplied).resolve(strict=False)
    if os.path.commonpath((str(WORKSPACE_DIR), str(candidate))) != str(WORKSPACE_DIR):
        raise ValueError("Path escapes the Sovereign workspace")
    if candidate == META_DIR or META_DIR in candidate.parents:
        raise ValueError("Intermix control state is reserved and cannot be edited as workspace data")
    if must_exist and not candidate.exists():
        raise FileNotFoundError(clean)
    return candidate


def relative_path(path: Path) -> str:
    return str(path.resolve(strict=False).relative_to(WORKSPACE_DIR))


def file_hash(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _atomic_write_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    try:
        temporary.write_bytes(content)
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def atomic_write_json(path: Path, payload: Any) -> None:
    encoded = (
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    _atomic_write_bytes(path, encoded)


def atomic_write_text(path: Path, content: str) -> None:
    """Atomically replace a UTF-8 text document."""
    _atomic_write_bytes(path, content.replace("\x00", "").encode("utf-8"))


def read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, TypeError):
        return default


def append_workspace_event(event: dict[str, Any]) -> None:
    ensure_workspace()
    payload = {"time": utc_now(), **event}
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
    with _STATE_LOCK:
        with WORKSPACE_EVENTS.open("a", encoding="utf-8") as stream:
            stream.write(encoded)


def _checkpoint(path: Path, *, source: str) -> str | None:
    before_hash = file_hash(path)
    if before_hash is None:
        return None
    blob = CHECKPOINT_BLOBS / before_hash
    if not blob.exists():
        _atomic_write_bytes(blob, path.read_bytes())
    append_workspace_event(
        {
            "action": "checkpoint",
            "path": relative_path(path),
            "hash": before_hash,
            "source": source,
        }
    )
    return before_hash


def write_text(
    raw_path: str | os.PathLike[str],
    content: str,
    *,
    source: str = "agent",
) -> dict[str, Any]:
    ensure_workspace()
    encoded = content.replace("\x00", "").encode("utf-8")
    if len(encoded) > MAX_FILE_BYTES:
        raise ValueError(f"File exceeds {MAX_FILE_BYTES} byte limit")
    path = safe_path(raw_path)
    if path.exists() and not path.is_file():
        raise ValueError("Target is not a regular file")
    with _STATE_LOCK:
        before_hash = file_hash(path)
        checkpoint_hash = _checkpoint(path, source=source)
        path.parent.mkdir(parents=True, exist_ok=True)
        _atomic_write_bytes(path, encoded)
        after_hash = file_hash(path)
        event = {
            "action": "write_file",
            "path": relative_path(path),
            "result": "success",
            "exit_code": 0,
            "before_hash": before_hash,
            "after_hash": after_hash,
            "checkpoint_hash": checkpoint_hash,
            "source": source,
        }
        append_workspace_event(event)
        return event


def read_text(
    raw_path: str | os.PathLike[str], *, max_bytes: int = MAX_READ_BYTES
) -> tuple[str, bool, str]:
    """Return text, editable flag, and a read-only explanation."""
    path = safe_path(raw_path, must_exist=True)
    if not path.is_file():
        raise ValueError("Target is not a regular file")
    size = path.stat().st_size
    if size > max_bytes:
        data = path.read_bytes()[:max_bytes].decode("utf-8", "replace")
        return data, False, f"Preview truncated at {max_bytes} bytes"
    raw = path.read_bytes()
    if b"\x00" in raw:
        return f"Binary file · {size} bytes", False, "Binary files are read-only"
    return raw.decode("utf-8", "replace"), size <= MAX_EDITOR_BYTES, ""


def iter_workspace_files(limit: int = 2000) -> Iterable[Path]:
    ensure_workspace()
    count = 0
    for path in WORKSPACE_DIR.rglob("*"):
        if not path.is_file() or META_DIR in path.parents:
            continue
        if "__pycache__" in path.parts:
            continue
        yield path
        count += 1
        if count >= max(1, limit):
            return


def request_deletion(raw_path: str | os.PathLike[str]) -> dict[str, Any]:
    ensure_workspace()
    path = safe_path(raw_path, must_exist=True)
    if not path.is_file():
        raise ValueError("Only regular files may be submitted for deletion")
    with _STATE_LOCK:
        pending = read_json(PENDING_DELETIONS, [])
        if not isinstance(pending, list):
            pending = []
        relative = relative_path(path)
        existing = next(
            (item for item in pending if item.get("path") == relative), None
        )
        if existing:
            return existing
        item = {
            "id": uuid.uuid4().hex[:8],
            "path": relative,
            "expected_hash": file_hash(path),
            "requested_at": utc_now(),
        }
        pending.append(item)
        atomic_write_json(PENDING_DELETIONS, pending[-100:])
        append_workspace_event({"action": "delete_requested", **item})
        return item


def pending_deletions() -> list[dict[str, Any]]:
    ensure_workspace()
    result = read_json(PENDING_DELETIONS, [])
    return result if isinstance(result, list) else []


def _remove_pending(request_id: str) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    pending = pending_deletions()
    selected = next((item for item in pending if item.get("id") == request_id), None)
    remaining = [item for item in pending if item.get("id") != request_id]
    return selected, remaining


def approve_deletion(request_id: str) -> dict[str, Any]:
    ensure_workspace()
    with _STATE_LOCK:
        item, remaining = _remove_pending(request_id.strip())
        if not item:
            raise ValueError("Deletion request was not found")
        path = safe_path(str(item["path"]), must_exist=True)
        current_hash = file_hash(path)
        if current_hash != item.get("expected_hash"):
            raise ValueError("File changed after deletion was requested; review it again")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
        destination = TRASH_DIR / stamp / str(item["path"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(path), str(destination))
        atomic_write_json(PENDING_DELETIONS, remaining)
        event = {
            "action": "delete_file",
            "path": str(item["path"]),
            "result": "moved to recoverable workspace trash",
            "exit_code": 0,
            "before_hash": current_hash,
            "after_hash": None,
            "request_id": item["id"],
            "source": "user_approved",
        }
        append_workspace_event(event)
        return event


def deny_deletion(request_id: str) -> dict[str, Any]:
    ensure_workspace()
    with _STATE_LOCK:
        item, remaining = _remove_pending(request_id.strip())
        if not item:
            raise ValueError("Deletion request was not found")
        atomic_write_json(PENDING_DELETIONS, remaining)
        event = {
            "action": "delete_denied",
            "path": str(item["path"]),
            "result": "denied by user",
            "exit_code": 1,
            "request_id": item["id"],
        }
        append_workspace_event(event)
        return event


ensure_workspace()


__all__ = [
    "CHECKPOINT_DIR",
    "MAX_EDITOR_BYTES",
    "MAX_FILE_BYTES",
    "MAX_READ_BYTES",
    "META_DIR",
    "WORKSPACE_DIR",
    "append_workspace_event",
    "approve_deletion",
    "atomic_write_json",
    "deny_deletion",
    "ensure_workspace",
    "file_hash",
    "iter_workspace_files",
    "pending_deletions",
    "read_json",
    "read_text",
    "relative_path",
    "request_deletion",
    "safe_path",
    "utc_now",
    "write_text",
]
