"""Consistent local snapshots and verified filesystem/NAS replication."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import tempfile
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Mapping

from .models import (
    ValidationError,
    bounded_identifier,
    bounded_text,
    canonical_json,
    now_ms,
    sha256_text,
    validate_global_id,
)
from .store import LATTICE_SCHEMA_VERSION, ContinuityStore


MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024 * 1024


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _copy_stream(source: BinaryIO, destination: BinaryIO) -> None:
    shutil.copyfileobj(source, destination, length=1024 * 1024)
    destination.flush()
    os.fsync(destination.fileno())


@dataclass(frozen=True)
class SnapshotArtifact:
    snapshot_id: str
    request_id: str | None
    generation: int
    database_path: Path
    manifest_path: Path
    database_sha256: str
    manifest_sha256: str
    byte_size: int
    manifest: Mapping[str, Any]

    def as_mapping(self) -> dict[str, Any]:
        return {
            "snapshot_id": self.snapshot_id,
            "request_id": self.request_id,
            "generation": self.generation,
            "database_path": str(self.database_path),
            "manifest_path": str(self.manifest_path),
            "database_sha256": self.database_sha256,
            "manifest_sha256": self.manifest_sha256,
            "byte_size": self.byte_size,
            "manifest": dict(self.manifest),
        }


class FilesystemArchiveTarget:
    """A narrow archive adapter used for a mounted NAS or a test directory."""

    def __init__(self, root: str | os.PathLike[str]) -> None:
        self.root = Path(root).expanduser().resolve(strict=False)

    def replicate(self, artifact: SnapshotArtifact, *, node_id: str) -> str:
        node_id = bounded_identifier(node_id, "node_id")
        self.root.mkdir(parents=True, exist_ok=True)
        if not self.root.is_dir() or self.root.is_symlink():
            raise ValidationError("archive root must be a real directory")
        relative_directory = Path("continuity") / "snapshots" / node_id / f"{artifact.generation:08d}"
        destination_directory = (self.root / relative_directory).resolve(strict=False)
        if self.root not in destination_directory.parents:
            raise ValidationError("archive destination escaped its configured root")
        destination_directory.mkdir(parents=True, exist_ok=True)
        if destination_directory.is_symlink():
            raise ValidationError("archive destination cannot be a symlink")

        database_name = f"{artifact.snapshot_id}.sqlite3"
        manifest_name = f"{artifact.snapshot_id}.manifest.json"
        database_target = destination_directory / database_name
        manifest_target = destination_directory / manifest_name
        self._verified_copy(
            artifact.database_path,
            database_target,
            artifact.database_sha256,
            artifact.byte_size,
        )
        self._verified_copy(
            artifact.manifest_path,
            manifest_target,
            artifact.manifest_sha256,
            artifact.manifest_path.stat().st_size,
        )
        return (relative_directory / manifest_name).as_posix()

    @staticmethod
    def _verified_copy(source: Path, destination: Path, expected_hash: str, expected_size: int) -> None:
        if destination.exists():
            if destination.is_symlink():
                raise ValidationError("archive destination cannot be a symlink")
            if destination.is_file() and destination.stat().st_size == expected_size:
                if hash_file(destination) == expected_hash:
                    return
            raise ValidationError(f"archive destination already exists with different bytes: {destination.name}")
        partial = destination.with_name(destination.name + f".{uuid.uuid4().hex}.partial")
        try:
            with source.open("rb") as read_from, partial.open("xb") as write_to:
                _copy_stream(read_from, write_to)
            if partial.stat().st_size != expected_size or hash_file(partial) != expected_hash:
                raise ValidationError("archive copy failed size/hash verification")
            os.replace(partial, destination)
        finally:
            try:
                partial.unlink()
            except FileNotFoundError:
                pass


class SnapshotManager:
    def __init__(self, store: ContinuityStore, snapshot_directory: str | os.PathLike[str]) -> None:
        self.store = store
        self.snapshot_directory = Path(snapshot_directory).expanduser().resolve(strict=False)
        self._lock = threading.Lock()

    def create(
        self,
        *,
        reason: str,
        archive_node_id: str = "archive-ds215j",
        request_id: str | None = None,
    ) -> SnapshotArtifact:
        reason = bounded_text(reason, "snapshot reason", 512)
        archive_node_id = bounded_identifier(archive_node_id, "archive_node_id")
        request_id = (
            validate_global_id(request_id, "snapshot request_id")
            if request_id is not None
            else None
        )
        with self._lock:
            if request_id is not None:
                with self.store.connection() as database:
                    existing = database.execute(
                        "SELECT snapshot_id,reason FROM snapshot_catalog WHERE request_id=?",
                        (request_id,),
                    ).fetchone()
                    destination = database.execute(
                        """
                        SELECT destination_node_id FROM replication_outbox
                        WHERE object_type='snapshot' AND object_global_id=?
                        """,
                        (str(existing[0]),),
                    ).fetchone() if existing else None
                if existing:
                    if str(existing[1]) != reason or (
                        destination and str(destination[0]) != archive_node_id
                    ):
                        raise ValidationError(
                            "snapshot request ID was replayed with different request bytes"
                        )
                    return self.artifact(str(existing[0]))
            self.snapshot_directory.mkdir(parents=True, exist_ok=True)
            if self.snapshot_directory.is_symlink():
                raise ValidationError("snapshot directory cannot be a symlink")
            with self.store.connection() as database:
                parent = database.execute(
                    "SELECT db_sha256 FROM snapshot_catalog ORDER BY generation DESC LIMIT 1"
                ).fetchone()
                generation = int(
                    database.execute(
                        "SELECT COALESCE(MAX(generation),0)+1 FROM snapshot_catalog"
                    ).fetchone()[0]
                )
                high_water = {
                    str(row[0]): int(row[1])
                    for row in database.execute(
                        "SELECT origin_node_id,MAX(origin_seq) FROM event_log "
                        "WHERE origin_node_id IS NOT NULL GROUP BY origin_node_id"
                    )
                }
            snapshot_id = str(uuid.uuid4())
            database_path = self.snapshot_directory / f"{generation:08d}-{snapshot_id}.sqlite3"
            manifest_path = self.snapshot_directory / f"{generation:08d}-{snapshot_id}.manifest.json"
            temporary = database_path.with_name(database_path.name + ".partial")
            if database_path.exists() or manifest_path.exists() or temporary.exists():
                raise ValidationError("snapshot destination unexpectedly exists")

            source = sqlite3.connect(self.store.db_path, timeout=30)
            destination = sqlite3.connect(str(temporary), timeout=30)
            try:
                source.execute("PRAGMA busy_timeout = 30000")
                source.backup(destination, pages=256, sleep=0.01)
                destination.commit()
            finally:
                destination.close()
                source.close()

            try:
                byte_size = temporary.stat().st_size
                if byte_size <= 0 or byte_size > MAX_SNAPSHOT_BYTES:
                    raise ValidationError("snapshot size is outside the accepted range")
                verification = sqlite3.connect(
                    temporary.resolve().as_uri() + "?mode=ro",
                    uri=True,
                )
                try:
                    quick = str(verification.execute("PRAGMA quick_check(1)").fetchone()[0])
                    foreign = verification.execute("PRAGMA foreign_key_check").fetchone()
                    user_version = int(verification.execute("PRAGMA user_version").fetchone()[0])
                finally:
                    verification.close()
                if quick != "ok" or foreign is not None:
                    raise ValidationError("snapshot failed SQLite integrity verification")
                database_sha = hash_file(temporary)
                created_at = now_ms()
                manifest = {
                    "format": "project-intermix-continuity-snapshot",
                    "format_version": 1,
                    "snapshot_id": snapshot_id,
                    "snapshot_generation": generation,
                    "origin_node_id": self.store.node_id,
                    "created_at_ms": created_at,
                    "lattice_schema_version": LATTICE_SCHEMA_VERSION,
                    "sqlite_user_version": user_version,
                    "event_high_water": high_water,
                    "parent_snapshot_hash": str(parent[0]) if parent else None,
                    "payload_filename": database_path.name,
                    "payload_sha256": database_sha,
                    "payload_bytes": byte_size,
                    "reason": reason,
                }
                manifest_text = canonical_json(manifest) + "\n"
                manifest_sha = sha256_text(manifest_text)
                with manifest_path.open("x", encoding="utf-8", newline="\n") as manifest_file:
                    manifest_file.write(manifest_text)
                    manifest_file.flush()
                    os.fsync(manifest_file.fileno())
                os.replace(temporary, database_path)
            except Exception:
                for path in (temporary, database_path, manifest_path):
                    try:
                        path.unlink()
                    except FileNotFoundError:
                        pass
                raise

            with self.store.connection(write=True) as database:
                database.execute(
                    """
                    INSERT INTO snapshot_catalog(
                        snapshot_id,request_id,generation,node_id,created_at_ms,lattice_schema_version,
                        sqlite_user_version,event_high_water_json,parent_snapshot_hash,
                        db_sha256,manifest_sha256,byte_size,local_path,manifest_path,reason
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        snapshot_id,
                        request_id,
                        generation,
                        self.store.node_id,
                        created_at,
                        LATTICE_SCHEMA_VERSION,
                        user_version,
                        canonical_json(high_water),
                        manifest["parent_snapshot_hash"],
                        database_sha,
                        manifest_sha,
                        byte_size,
                        str(database_path),
                        str(manifest_path),
                        reason,
                    ),
                )
                database.execute(
                    """
                    INSERT OR REPLACE INTO content_object(
                        sha256,created_at_ms,source_node_id,object_class,byte_size,mime_type,
                        local_path,nas_state,metadata_json
                    ) VALUES(?, ?, ?, 'db_snapshot', ?, 'application/vnd.sqlite3', ?, 'queued', ?)
                    """,
                    (
                        database_sha,
                        created_at,
                        self.store.node_id,
                        byte_size,
                        str(database_path),
                        canonical_json({"snapshot_id": snapshot_id, "manifest_sha256": manifest_sha}),
                    ),
                )
                database.execute(
                    """
                    INSERT INTO replication_outbox(
                        created_at_ms,destination_node_id,object_type,object_global_id,
                        payload_sha256,payload_json,priority
                    ) VALUES(?,?,?,?,?,?,0.9)
                    ON CONFLICT(destination_node_id,object_type,object_global_id) DO NOTHING
                    """,
                    (
                        created_at,
                        archive_node_id,
                        "snapshot",
                        snapshot_id,
                        database_sha,
                        canonical_json(
                            {
                                "database_path": str(database_path),
                                "manifest_path": str(manifest_path),
                                "manifest_sha256": manifest_sha,
                            }
                        ),
                    ),
                )
            return SnapshotArtifact(
                snapshot_id=snapshot_id,
                request_id=request_id,
                generation=generation,
                database_path=database_path,
                manifest_path=manifest_path,
                database_sha256=database_sha,
                manifest_sha256=manifest_sha,
                byte_size=byte_size,
                manifest=manifest,
            )

    def artifact(self, snapshot_id: str) -> SnapshotArtifact:
        snapshot_id = bounded_text(snapshot_id, "snapshot_id", 80)
        try:
            uuid.UUID(snapshot_id)
        except ValueError as failure:
            raise ValidationError("snapshot_id must be a UUID") from failure
        with self.store.connection() as database:
            row = database.execute(
                "SELECT * FROM snapshot_catalog WHERE snapshot_id=?", (snapshot_id,)
            ).fetchone()
        if not row:
            raise ValidationError("snapshot is not catalogued")
        database_path = Path(str(row["local_path"]))
        manifest_path = Path(str(row["manifest_path"]))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        return SnapshotArtifact(
            snapshot_id=snapshot_id,
            request_id=str(row["request_id"]) if row["request_id"] else None,
            generation=int(row["generation"]),
            database_path=database_path,
            manifest_path=manifest_path,
            database_sha256=str(row["db_sha256"]),
            manifest_sha256=str(row["manifest_sha256"]),
            byte_size=int(row["byte_size"]),
            manifest=manifest,
        )

    def replicate(
        self,
        snapshot_id: str,
        target: FilesystemArchiveTarget,
        *,
        archive_node_id: str = "archive-ds215j",
    ) -> str:
        artifact = self.artifact(snapshot_id)
        try:
            relative_manifest = target.replicate(artifact, node_id=self.store.node_id)
        except Exception as failure:
            with self.store.connection(write=True) as database:
                database.execute(
                    """
                    UPDATE replication_outbox SET state='failed',attempts=attempts+1,
                        last_error=?,next_attempt_ms=?
                    WHERE destination_node_id=? AND object_type='snapshot' AND object_global_id=?
                    """,
                    (str(failure)[:2048], now_ms() + 60_000, archive_node_id, snapshot_id),
                )
            raise
        with self.store.connection(write=True) as database:
            database_relative = Path(relative_manifest).with_name(
                artifact.database_path.name
            ).as_posix()
            database.execute(
                """
                UPDATE snapshot_catalog SET nas_relative_path=?,nas_verified=1
                WHERE snapshot_id=?
                """,
                (relative_manifest, snapshot_id),
            )
            database.execute(
                """
                UPDATE content_object SET nas_relative_path=?,nas_state='present'
                WHERE sha256=?
                """,
                (database_relative, artifact.database_sha256),
            )
            database.execute(
                """
                UPDATE replication_outbox SET state='acked',attempts=attempts+1,
                    last_error=NULL,next_attempt_ms=NULL
                WHERE destination_node_id=? AND object_type='snapshot' AND object_global_id=?
                """,
                (archive_node_id, snapshot_id),
            )
        return relative_manifest


def restore_snapshot(
    database_path: str | os.PathLike[str],
    manifest_path: str | os.PathLike[str],
    destination_path: str | os.PathLike[str],
    *,
    expected_manifest_sha256: str | None = None,
) -> Path:
    """Verify and restore to a new path; never overwrite an existing database."""

    source = Path(database_path).expanduser().resolve()
    manifest_file = Path(manifest_path).expanduser().resolve()
    destination = Path(destination_path).expanduser().resolve(strict=False)
    if destination.exists():
        raise ValidationError("restore destination already exists")
    manifest_text = manifest_file.read_text(encoding="utf-8")
    try:
        manifest = json.loads(manifest_text)
    except json.JSONDecodeError as failure:
        raise ValidationError("snapshot manifest is not valid JSON") from failure
    if not isinstance(manifest, dict):
        raise ValidationError("snapshot manifest must be an object")
    canonical_manifest = canonical_json(manifest) + "\n"
    if manifest_text != canonical_manifest:
        raise ValidationError("snapshot manifest is not canonical JSON")
    manifest_sha256 = sha256_text(canonical_manifest)
    if expected_manifest_sha256 is not None:
        expected_manifest_sha256 = str(expected_manifest_sha256).casefold()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_manifest_sha256):
            raise ValidationError("expected manifest SHA-256 is invalid")
        if manifest_sha256 != expected_manifest_sha256:
            raise ValidationError("snapshot manifest SHA-256 verification failed")
    required = {
        "format",
        "format_version",
        "snapshot_id",
        "snapshot_generation",
        "origin_node_id",
        "created_at_ms",
        "lattice_schema_version",
        "sqlite_user_version",
        "event_high_water",
        "parent_snapshot_hash",
        "payload_filename",
        "payload_sha256",
        "payload_bytes",
        "reason",
    }
    if set(manifest) != required:
        raise ValidationError("snapshot manifest fields are not canonical")
    if manifest["format"] != "project-intermix-continuity-snapshot" or manifest["format_version"] != 1:
        raise ValidationError("snapshot format is unsupported")
    if source.name != manifest["payload_filename"]:
        raise ValidationError("snapshot payload filename does not match the manifest")
    if source.stat().st_size != int(manifest["payload_bytes"]):
        raise ValidationError("snapshot byte size does not match the manifest")
    if hash_file(source) != str(manifest["payload_sha256"]):
        raise ValidationError("snapshot SHA-256 verification failed")
    verification = sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)
    try:
        if str(verification.execute("PRAGMA quick_check(1)").fetchone()[0]) != "ok":
            raise ValidationError("snapshot SQLite quick_check failed")
        if verification.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValidationError("snapshot foreign-key verification failed")
    finally:
        verification.close()

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary_handle, temporary_name = tempfile.mkstemp(
        prefix=destination.name + ".", suffix=".partial", dir=destination.parent
    )
    os.close(temporary_handle)
    temporary = Path(temporary_name)
    try:
        with source.open("rb") as read_from, temporary.open("wb") as write_to:
            _copy_stream(read_from, write_to)
        if temporary.stat().st_size != int(manifest["payload_bytes"]):
            raise ValidationError("restored snapshot was truncated")
        if hash_file(temporary) != str(manifest["payload_sha256"]):
            raise ValidationError("restored snapshot hash changed")
        os.replace(temporary, destination)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    return destination
