#!/usr/bin/env python3
"""Run one share-safe, on-device LIBRARIAN-01 daily flight.

The live authority receives a read-only health/integrity check and, by default,
one idempotent verified snapshot per UTC day. Event ingest, memory provenance,
duplicate replay, context compilation, archive replication, and restore are
exercised in a disposable shadow database on the same device/filesystem so test
content never enters the user's continuity history.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import resource
import sqlite3
import sys
import tempfile
import time
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from librarian.health import HealthReporter  # noqa: E402
from librarian.models import EventEnvelope, now_ms  # noqa: E402
from librarian.snapshots import (  # noqa: E402
    FilesystemArchiveTarget,
    SnapshotManager,
    restore_snapshot,
)
from librarian.store import LATTICE_SCHEMA_VERSION, ContinuityStore  # noqa: E402
from provider_vault import (  # noqa: E402
    VAULT_FILE,
    load_provider_environment,
    provider_configuration_status,
)
from runtime_config import CONFIG  # noqa: E402
from web_search import provider_policy, run_provider_canary  # noqa: E402


FLIGHT_NAMESPACE = uuid.UUID("39146d92-b4fb-4678-b380-f8042b42dc19")


def _elapsed(started: float) -> float:
    return round(time.perf_counter() - started, 6)


def _max_rss_kib() -> int:
    value = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    return value // 1024 if sys.platform == "darwin" else value


def _event(day: str) -> EventEnvelope:
    return EventEnvelope.from_mapping(
        {
            "global_event_id": str(uuid.uuid5(FLIGHT_NAMESPACE, f"shadow-event:{day}")),
            "node_id": "flight-cortex",
            "node_sequence": 1,
            "source_time_ms": now_ms(),
            "kind": "flight.observation",
            "actor": "runtime",
            "content": "Disposable daily continuity flight observation.",
            "payload": {"session_id": "librarian-daily-flight", "day": day},
            "schema_version": 1,
        }
    )


def _delta(event: EventEnvelope, day: str) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "atoms": [
            {
                "client_id": "daily-flight",
                "class": "fact",
                "domain": "operations",
                "subject": "librarian-shadow",
                "predicate": "flight.completed",
                "object_text": day,
                "canonical_text": f"The disposable Librarian flight completed for {day}.",
                "epistemic": "tool_verified",
                "confidence": 1.0,
                "salience": 0.2,
                "stability": 0.1,
                "novelty": 0.1,
                "utility": 0.8,
            }
        ],
        "evidence": [
            {
                "atom_ref": "daily-flight",
                "event_global_id": event.global_event_id,
                "relation": "verifies",
                "weight": 1.0,
            }
        ],
    }


def _shadow_flight(root: Path, day: str) -> dict[str, Any]:
    timings: dict[str, float] = {}
    database_path = root / "shadow" / "sovereign.db"
    store = ContinuityStore(database_path, node_id="flight-librarian")
    store.register_node("flight-cortex", "cortex", "FLIGHT-CORTEX", platform="shadow")
    store.register_node("flight-archive", "archive", "FLIGHT-ARCHIVE", platform="shadow")
    event = _event(day)

    started = time.perf_counter()
    committed = store.ingest_event(event)
    duplicate = store.ingest_event(event)
    timings["ingest_and_duplicate"] = _elapsed(started)

    started = time.perf_counter()
    delta = store.apply_memory_delta(
        source_event_global_id=event.global_event_id,
        actor_node_id="flight-cortex",
        delta=_delta(event, day),
    )
    timings["validated_memory_commit"] = _elapsed(started)

    started = time.perf_counter()
    context = store.compile_context(
        query="daily continuity flight completed",
        token_budget=800,
        session_id="librarian-daily-flight",
    )
    source_ids = {
        str(evidence["event_global_id"])
        for memory in context["sections"]["memories"]
        for evidence in memory["evidence"]
    }
    source_ids.update(
        str(recent["global_id"])
        for recent in context["sections"]["recent_events"]
    )
    timings["bounded_context"] = _elapsed(started)

    manager = SnapshotManager(store, root / "shadow-snapshots")
    started = time.perf_counter()
    artifact = manager.create(
        reason="disposable on-device daily flight",
        archive_node_id="flight-archive",
        request_id=str(uuid.uuid5(FLIGHT_NAMESPACE, f"shadow-snapshot:{day}")),
    )
    archive_manifest = manager.replicate(
        artifact.snapshot_id,
        FilesystemArchiveTarget(root / "shadow-archive"),
    )
    restored_path = restore_snapshot(
        artifact.database_path,
        artifact.manifest_path,
        root / "shadow-restored.db",
        expected_manifest_sha256=artifact.manifest_sha256,
    )
    timings["snapshot_archive_restore"] = _elapsed(started)

    restored = ContinuityStore(
        restored_path,
        node_id="flight-librarian",
        initialize=False,
    )
    integrity = store.integrity_check()
    restored_integrity = restored.integrity_check()
    restored_counts = restored.counts()
    passed = bool(
        committed.status == "committed"
        and duplicate.status == "duplicate"
        and delta["atoms"] == 1
        and event.global_event_id in source_ids
        and integrity["ok"]
        and restored_integrity["ok"]
        and restored_counts["events"] == 1
        and restored_counts["atoms"] == 1
    )
    return {
        "passed": passed,
        "ingest": committed.status,
        "duplicate_replay": duplicate.status,
        "committed_atoms": delta["atoms"],
        "context_source_count": len(source_ids),
        "context_contains_probe_evidence": event.global_event_id in source_ids,
        "integrity": integrity,
        "snapshot": {
            "generation": artifact.generation,
            "database_sha256": artifact.database_sha256,
            "manifest_sha256": artifact.manifest_sha256,
            "bytes": artifact.byte_size,
            "archive_manifest": archive_manifest,
        },
        "restored_integrity": restored_integrity,
        "restored_counts": restored_counts,
        "timings_seconds": timings,
    }


def _atomic_private_file(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.parent.is_symlink():
        raise ValueError("flight report directory cannot be a symlink")
    if path.exists():
        raise FileExistsError(f"refusing to replace existing flight artifact: {path.name}")
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        # A hard link publishes the complete temp file and fails if the destination
        # appeared concurrently. Unlike replace(), it cannot overwrite evidence.
        os.link(temporary_name, path)
        os.unlink(temporary_name)
        os.chmod(path, 0o600)
    except Exception:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise


def _atomic_report(path: Path, payload: dict[str, Any]) -> str:
    content = (
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    digest = hashlib.sha256(content).hexdigest()
    _atomic_private_file(path, content)
    checksum_path = path.with_name(path.name + ".sha256")
    _atomic_private_file(
        checksum_path,
        f"{digest}  {path.name}\n".encode("ascii"),
    )
    return digest


def run_daily_flight(
    *,
    database_path: Path,
    report_directory: Path,
    snapshot_directory: Path,
    provider_vault_path: Path = VAULT_FILE,
    archive_root: Path | None = None,
    snapshot_live: bool = True,
    provider_canary: bool = False,
    canary_providers: int = 2,
    flight_day: str | None = None,
    node_id: str = "librarian-01",
) -> tuple[dict[str, Any], Path]:
    day = date.fromisoformat(flight_day).isoformat() if flight_day else datetime.now(timezone.utc).date().isoformat()
    database_path = database_path.expanduser().resolve(strict=True)
    report_directory = report_directory.expanduser().resolve(strict=False)
    snapshot_directory = snapshot_directory.expanduser().resolve(strict=False)
    if not database_path.is_file() or database_path.is_symlink():
        raise ValueError("live Librarian database must be a real file")

    live_store = ContinuityStore(database_path, node_id=node_id, initialize=False)
    if live_store.schema_version() != LATTICE_SCHEMA_VERSION:
        raise ValueError("live database does not contain the current Continuity Lattice")

    started = time.perf_counter()
    live_before = HealthReporter(live_store).report()
    live_health_seconds = _elapsed(started)
    report_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(report_directory, 0o700)

    with tempfile.TemporaryDirectory(prefix=".librarian-flight-", dir=report_directory) as temporary:
        shadow = _shadow_flight(Path(temporary), day)

    live_snapshot: dict[str, Any] | None = None
    if snapshot_live:
        manager = SnapshotManager(live_store, snapshot_directory)
        started = time.perf_counter()
        artifact = manager.create(
            reason=f"daily continuity flight {day}",
            request_id=str(uuid.uuid5(FLIGHT_NAMESPACE, f"live-snapshot:{node_id}:{day}")),
        )
        archive_manifest = None
        if archive_root is not None:
            archive_manifest = manager.replicate(
                artifact.snapshot_id,
                FilesystemArchiveTarget(archive_root),
            )
        live_snapshot = {
            "snapshot_id": artifact.snapshot_id,
            "generation": artifact.generation,
            "database_sha256": artifact.database_sha256,
            "manifest_sha256": artifact.manifest_sha256,
            "bytes": artifact.byte_size,
            "archive_manifest": archive_manifest,
            "elapsed_seconds": _elapsed(started),
        }

    live_after = HealthReporter(live_store).report()
    provider_status = provider_configuration_status(provider_vault_path)
    if provider_canary:
        load_provider_environment(provider_vault_path)
    canary = run_provider_canary(canary_providers) if provider_canary else None
    generated_at = datetime.now(timezone.utc)
    version_path = ROOT / "VERSION"
    report = {
        "format": "project-intermix-librarian-daily-flight",
        "format_version": 2,
        "report_id": str(uuid.uuid4()),
        "flight_day_utc": day,
        "generated_at_utc": generated_at.isoformat(),
        "release": version_path.read_text(encoding="utf-8").strip(),
        "scope": (
            "on-device live health and idempotent snapshot; full writes run only in a disposable "
            "shadow database"
        ),
        "host": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
            "python": platform.python_version(),
            "sqlite": sqlite3.sqlite_version,
            "max_rss_kib": _max_rss_kib(),
        },
        "live_authority": {
            "before": live_before,
            "after": live_after,
            "health_elapsed_seconds": live_health_seconds,
            "snapshot": live_snapshot,
        },
        "shadow_flight": shadow,
        "grounding": {
            "configured": provider_status,
            "configured_count": sum(provider_status.values()),
            "policy": provider_policy(),
            "canary": canary,
            "live_queries_executed": int((canary or {}).get("requests_used", 0)),
        },
    }
    report["passed"] = bool(
        live_before["database"]["ok"]
        and live_after["database"]["ok"]
        and shadow["passed"]
        and (not snapshot_live or live_snapshot is not None)
        and (not provider_canary or (canary or {}).get("status") == "passed")
    )
    filename = (
        generated_at.strftime("%Y%m%dT%H%M%S.%fZ")
        + f"-{uuid.uuid4().hex[:8]}-librarian-flight.json"
    )
    report_path = report_directory / filename
    report["artifact"] = {
        "filename": report_path.name,
        "checksum_filename": report_path.name + ".sha256",
    }
    report_sha256 = _atomic_report(report_path, report)
    report["artifact"]["sha256"] = report_sha256
    return report, report_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=CONFIG.memory_db)
    parser.add_argument(
        "--report-dir",
        type=Path,
        default=CONFIG.archive_dir / "librarian-flight-reports",
    )
    parser.add_argument(
        "--snapshot-dir",
        type=Path,
        default=CONFIG.archive_dir / "librarian-snapshots",
    )
    parser.add_argument("--provider-vault", type=Path, default=VAULT_FILE)
    parser.add_argument("--archive-root", type=Path)
    parser.add_argument("--snapshot-live", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument(
        "--provider-canary",
        action="store_true",
        help="Send one fixed non-personal query to a bounded configured-provider wave",
    )
    parser.add_argument(
        "--canary-providers",
        type=int,
        choices=(1, 2, 3),
        default=2,
        help="Maximum configured providers in the canary wave (default: 2)",
    )
    parser.add_argument("--day", help="UTC ISO date override for deterministic validation")
    parser.add_argument("--node-id", default="librarian-01")
    parser.add_argument("--json", action="store_true", help="Print the full share-safe report")
    args = parser.parse_args(argv)
    try:
        report, report_path = run_daily_flight(
            database_path=args.db,
            report_directory=args.report_dir,
            snapshot_directory=args.snapshot_dir,
            provider_vault_path=args.provider_vault,
            archive_root=args.archive_root,
            snapshot_live=args.snapshot_live,
            provider_canary=args.provider_canary,
            canary_providers=args.canary_providers,
            flight_day=args.day,
            node_id=args.node_id,
        )
    except Exception as failure:
        print(
            json.dumps(
                {
                    "passed": False,
                    "error": type(failure).__name__,
                    "detail": str(failure)[:300],
                },
                sort_keys=True,
            ),
            file=sys.stderr,
        )
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(
            json.dumps(
                {
                    "passed": report["passed"],
                    "report": str(report_path),
                    "live_states": report["live_authority"]["after"]["states"],
                    "live_snapshot": (
                        report["live_authority"]["snapshot"] or {}
                    ).get("snapshot_id"),
                    "shadow_timings_seconds": report["shadow_flight"]["timings_seconds"],
                    "configured_provider_fields": report["grounding"]["configured_count"],
                    "provider_canary": (report["grounding"]["canary"] or {}).get("status"),
                    "report_sha256": report["artifact"]["sha256"],
                },
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
        )
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
