#!/usr/bin/env python3
"""Operate the Project Intermix LIBRARIAN-01 ordinary service."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sqlite3
import stat
import sys
import threading
import traceback
import uuid
from pathlib import Path
from typing import Any

from librarian.health import HealthReporter
from librarian.legacy import project_existing_memory
from librarian.models import canonical_json, now_ms
from librarian.service import LibrarianApplication, build_server
from librarian.snapshots import FilesystemArchiveTarget, SnapshotManager, hash_file, restore_snapshot
from librarian.store import LATTICE_SCHEMA_VERSION, ContinuityStore
from librarian.workers import WorkerSupervisor
from runtime_config import CONFIG


def _store(args: argparse.Namespace) -> ContinuityStore:
    return ContinuityStore(args.db, node_id=args.node_id)


def _token(path: str | None) -> str:
    if not path:
        return ""
    token_path = Path(path).expanduser().resolve()
    token = token_path.read_text(encoding="utf-8").strip()
    mode = stat.S_IMODE(token_path.stat().st_mode)
    if mode & 0o077:
        raise SystemExit(f"Refusing token file with group/other permissions: {oct(mode)}")
    if len(token.encode("utf-8")) < 32:
        raise SystemExit("Librarian token must contain at least 32 UTF-8 bytes")
    return token


def _print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True))


def _installed_lattice_version(database_path: str | os.PathLike[str]) -> str | None:
    path = Path(database_path).expanduser().resolve(strict=False)
    if not path.is_file() or path.stat().st_size == 0:
        return None
    database = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=30)
    try:
        table = database.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='lattice_meta'"
        ).fetchone()
        if not table:
            return None
        row = database.execute(
            "SELECT value FROM lattice_meta WHERE key='schema_version'"
        ).fetchone()
        return str(row[0]) if row else None
    finally:
        database.close()


def _migration_backup_if_needed(
    database_path: str | os.PathLike[str],
    backup_directory: str | os.PathLike[str],
) -> dict[str, Any] | None:
    source_path = Path(database_path).expanduser().resolve(strict=False)
    if not source_path.is_file() or source_path.stat().st_size == 0:
        return None
    previous_version = _installed_lattice_version(source_path)
    if previous_version == LATTICE_SCHEMA_VERSION:
        return None
    destination_directory = Path(backup_directory).expanduser().resolve(strict=False)
    destination_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(destination_directory, 0o700)
    identifier = uuid.uuid4().hex
    destination = destination_directory / f"pre-librarian-{identifier}.sqlite3"
    partial = destination.with_name(destination.name + ".partial")
    manifest_path = destination.with_suffix(".manifest.json")
    source: sqlite3.Connection | None = None
    target: sqlite3.Connection | None = None
    try:
        source = sqlite3.connect(source_path, timeout=30)
        target = sqlite3.connect(partial, timeout=30)
        source.execute("PRAGMA busy_timeout=30000")
        source.backup(target, pages=256, sleep=0.01)
        target.commit()
    except Exception:
        try:
            partial.unlink()
        except FileNotFoundError:
            pass
        raise
    finally:
        if target is not None:
            target.close()
        if source is not None:
            source.close()
    try:
        verification = sqlite3.connect(partial.as_uri() + "?mode=ro", uri=True)
        try:
            quick_check = str(verification.execute("PRAGMA quick_check(1)").fetchone()[0])
        finally:
            verification.close()
        if quick_check != "ok":
            raise SystemExit("Pre-migration backup failed SQLite integrity verification")
        os.chmod(partial, 0o600)
        os.replace(partial, destination)
        manifest = {
            "format": "project-intermix-pre-librarian-migration",
            "format_version": 1,
            "created_at_ms": now_ms(),
            "previous_lattice_version": previous_version,
            "payload_filename": destination.name,
            "payload_sha256": hash_file(destination),
            "payload_bytes": destination.stat().st_size,
        }
        descriptor = os.open(manifest_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(canonical_json(manifest) + "\n")
            output.flush()
            os.fsync(output.fileno())
        return {
            "database_path": str(destination),
            "manifest_path": str(manifest_path),
            "database_sha256": manifest["payload_sha256"],
            "previous_lattice_version": previous_version,
        }
    except Exception:
        for path in (partial, destination, manifest_path):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=str(CONFIG.memory_db))
    parser.add_argument("--node-id", default="librarian-01")
    commands = parser.add_subparsers(dest="command", required=True)

    initialize = commands.add_parser("init", help="Install the additive lattice and node registry")
    initialize.add_argument("--token-file")
    initialize.add_argument("--create-token", action="store_true")
    initialize.add_argument(
        "--backup-dir",
        default=str(CONFIG.archive_dir / "librarian-migration-backups"),
    )
    initialize.add_argument(
        "--import-existing",
        action="store_true",
        help="Idempotently project existing messages/memories into the lattice",
    )

    serve = commands.add_parser("serve", help="Run the bounded Librarian HTTP service")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    serve.add_argument("--token-file")
    serve.add_argument(
        "--client-node-id",
        action="append",
        help="Registered remote node allowed to use this token (repeatable; default: cortex-primary)",
    )
    serve.add_argument(
        "--trusted-overlay",
        action="store_true",
        help="Allow non-loopback HTTP only when the bound interface is inside an encrypted overlay",
    )
    serve.add_argument("--snapshot-dir", default=str(CONFIG.archive_dir / "librarian-snapshots"))
    serve.add_argument("--archive-root")

    commands.add_parser("health", help="Print explicit health state")

    context = commands.add_parser("context", help="Compile a bounded context packet")
    context.add_argument("query")
    context.add_argument("--tokens", type=int, default=2400)
    context.add_argument("--session-id", default="")

    snapshot = commands.add_parser("snapshot", help="Create and optionally replicate a snapshot")
    snapshot.add_argument("--reason", default="manual checkpoint")
    snapshot.add_argument("--snapshot-dir", default=str(CONFIG.archive_dir / "librarian-snapshots"))
    snapshot.add_argument("--archive-root")

    restore = commands.add_parser("restore", help="Verify a snapshot into a new database path")
    restore.add_argument("database")
    restore.add_argument("manifest")
    restore.add_argument("destination")
    restore.add_argument(
        "--manifest-sha256",
        help="Expected digest printed when the snapshot was created",
    )

    worker = commands.add_parser("worker", help="Run queued deterministic workers")
    worker.add_argument("--maximum", type=int, default=100)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "restore":
        restored = restore_snapshot(
            args.database,
            args.manifest,
            args.destination,
            expected_manifest_sha256=args.manifest_sha256,
        )
        _print({"restored": str(restored)})
        return 0

    installed_version = _installed_lattice_version(args.db)
    if args.command != "init" and installed_version != LATTICE_SCHEMA_VERSION:
        raise SystemExit("Run `intermix-librarian init` before this command")
    migration_backup = (
        _migration_backup_if_needed(args.db, args.backup_dir)
        if args.command == "init"
        else None
    )
    store = _store(args)
    if args.command == "init":
        store.register_node("cortex-primary", "cortex", "CORTEX-PRIMARY", platform="Pixel")
        store.register_node("archive-ds215j", "archive", "DS215j", platform="Synology")
        projection = project_existing_memory(store) if args.import_existing else None
        token_created = False
        if args.create_token:
            if not args.token_file:
                raise SystemExit("--create-token requires --token-file")
            path = Path(args.token_file).expanduser().resolve(strict=False)
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                raise SystemExit("Refusing to replace an existing token file")
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(secrets.token_urlsafe(48) + "\n")
            token_created = True
        _print(
            {
                "initialized": True,
                "schema_version": store.schema_version(),
                "integrity": store.integrity_check(),
                "existing_memory_projection": projection,
                "migration_backup": migration_backup,
                "token_created": token_created,
            }
        )
        return 0
    if args.command == "health":
        _print(HealthReporter(store).report())
        return 0
    if args.command == "context":
        _print(
            store.compile_context(
                query=args.query, token_budget=args.tokens, session_id=args.session_id
            )
        )
        return 0
    if args.command == "snapshot":
        manager = SnapshotManager(store, args.snapshot_dir)
        artifact = manager.create(reason=args.reason)
        result = artifact.as_mapping()
        if args.archive_root:
            result["archive_manifest"] = manager.replicate(
                artifact.snapshot_id, FilesystemArchiveTarget(args.archive_root)
            )
        _print(result)
        return 0
    if args.command == "worker":
        _print({"outcomes": WorkerSupervisor(store).run_until_idle(maximum=args.maximum)})
        return 0
    if args.command == "serve":
        snapshots = SnapshotManager(store, args.snapshot_dir)
        archive = FilesystemArchiveTarget(args.archive_root) if args.archive_root else None
        application = LibrarianApplication(
            store,
            health=HealthReporter(store),
            snapshots=snapshots,
            archive_target=archive,
        )
        server = build_server(
            application,
            host=args.host,
            port=args.port,
            bearer_token=_token(args.token_file),
            trusted_overlay=args.trusted_overlay,
            allowed_node_ids=args.client_node_id or ["cortex-primary"],
        )
        stop_workers = threading.Event()
        workers = WorkerSupervisor(store)

        def supervise_workers() -> None:
            while not stop_workers.is_set():
                try:
                    outcome = workers.run_once()
                    delay = 0.25 if outcome is not None else 2.0
                except Exception:
                    # The HTTP authority and SQLite store stay alive even when the
                    # disposable scheduler encounters an infrastructure failure.
                    traceback.print_exc()
                    delay = 5.0
                stop_workers.wait(delay)

        worker_thread = threading.Thread(
            target=supervise_workers,
            name="intermix-librarian-workers",
            daemon=True,
        )
        worker_thread.start()
        print(
            canonical_json(
                {
                    "service": "intermix-librarian",
                    "listen": f"{args.host}:{args.port}",
                    "schema": store.schema_version(),
                }
            ),
            flush=True,
        )
        try:
            server.serve_forever(poll_interval=0.5)
        except KeyboardInterrupt:
            pass
        finally:
            stop_workers.set()
            server.server_close()
            worker_thread.join(timeout=6)
        return 0
    raise SystemExit("unreachable")


if __name__ == "__main__":
    raise SystemExit(main())
