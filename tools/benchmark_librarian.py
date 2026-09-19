#!/usr/bin/env python3
"""Run a repeatable LIBRARIAN-01 closed-loop microbenchmark.

This is a software benchmark, not a claim about untested phone hardware. It
exercises immutable ingest, provenance commits, lexical recall, idempotent
replay, a verified SQLite snapshot, archive replication, and restore.
"""

from __future__ import annotations

import argparse
import json
import platform
import resource
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from librarian.models import EventEnvelope, now_ms  # noqa: E402
from librarian.snapshots import (  # noqa: E402
    FilesystemArchiveTarget,
    SnapshotManager,
    restore_snapshot,
)
from librarian.store import ContinuityStore  # noqa: E402


BENCHMARK_NAMESPACE = uuid.UUID("51a19068-3f10-4cde-98cc-4ec1a966d2c6")


def _elapsed(start: float) -> float:
    return round(time.perf_counter() - start, 6)


def _rss_kib() -> int:
    value = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    # Linux and Android report KiB; macOS reports bytes.
    return value // 1024 if sys.platform == "darwin" else value


def _event(index: int, started_ms: int) -> EventEnvelope:
    topic = index % 64
    return EventEnvelope.from_mapping(
        {
            "global_event_id": str(uuid.uuid5(BENCHMARK_NAMESPACE, f"event:{index}")),
            "node_id": "cortex-primary",
            "node_sequence": index + 1,
            "source_time_ms": started_ms + index,
            "kind": "benchmark.observation",
            "actor": "user",
            "content": f"Benchmark observation {index} for durable topic {topic}.",
            "payload": {"session_id": "librarian-benchmark", "topic": topic},
            "schema_version": 1,
        }
    )


def _delta(event: EventEnvelope, index: int) -> dict[str, Any]:
    topic = index % 64
    client_id = f"benchmark-{index}"
    return {
        "schema_version": 1,
        "atoms": [
            {
                "client_id": client_id,
                "class": "fact",
                "domain": "benchmark",
                "subject": f"topic-{topic}",
                "predicate": "benchmark.observation",
                "object_text": f"value-{index}",
                "canonical_text": (
                    f"Durable benchmark topic {topic} has observed value {index}."
                ),
                "epistemic": "observed",
                "confidence": 0.9,
                "salience": 0.6,
                "stability": 0.7,
                "novelty": 0.5,
                "utility": 0.6,
            }
        ],
        "evidence": [
            {
                "atom_ref": client_id,
                "event_global_id": event.global_event_id,
                "relation": "source",
                "weight": 1.0,
            }
        ],
    }


def run_benchmark(*, events: int, queries: int, atom_stride: int) -> dict[str, Any]:
    if events < 1 or events > 100_000:
        raise ValueError("events must be between 1 and 100000")
    if queries < 1 or queries > 10_000:
        raise ValueError("queries must be between 1 and 10000")
    if atom_stride < 1 or atom_stride > events:
        raise ValueError("atom_stride must be between 1 and events")

    with tempfile.TemporaryDirectory(prefix="intermix-librarian-benchmark-") as temporary:
        root = Path(temporary)
        database_path = root / "sovereign.db"
        store = ContinuityStore(database_path)
        store.register_node("cortex-primary", "cortex", "CORTEX-PRIMARY", platform="benchmark")
        store.register_node("archive-ds215j", "archive", "DS215j", platform="benchmark")
        started_ms = now_ms()
        envelopes = [_event(index, started_ms) for index in range(events)]

        started = time.perf_counter()
        committed = [store.ingest_event(event).status for event in envelopes]
        ingest_seconds = _elapsed(started)
        if set(committed) != {"committed"}:
            raise RuntimeError("initial ingest did not commit every event")

        atom_events = list(range(0, events, atom_stride))
        started = time.perf_counter()
        for index in atom_events:
            store.apply_memory_delta(
                source_event_global_id=envelopes[index].global_event_id,
                actor_node_id="cortex-primary",
                delta=_delta(envelopes[index], index),
            )
        memory_seconds = _elapsed(started)

        started = time.perf_counter()
        context_sources = 0
        for index in range(queries):
            packet = store.compile_context(
                query=f"durable topic {index % 64}",
                token_budget=1200,
                session_id="librarian-benchmark",
            )
            source_ids = {
                str(evidence["event_global_id"])
                for memory in packet["sections"]["memories"]
                for evidence in memory["evidence"]
            }
            source_ids.update(
                str(event["global_id"])
                for event in packet["sections"]["recent_events"]
            )
            context_sources += len(source_ids)
        query_seconds = _elapsed(started)

        count_before_replay = store.counts()["events"]
        started = time.perf_counter()
        duplicate = [store.ingest_event(event).status for event in envelopes]
        replay_seconds = _elapsed(started)
        if set(duplicate) != {"duplicate"} or store.counts()["events"] != count_before_replay:
            raise RuntimeError("duplicate replay changed the event set")

        manager = SnapshotManager(store, root / "snapshots")
        started = time.perf_counter()
        artifact = manager.create(reason="repeatable software benchmark")
        snapshot_seconds = _elapsed(started)
        started = time.perf_counter()
        archive_manifest = manager.replicate(
            artifact.snapshot_id,
            FilesystemArchiveTarget(root / "archive"),
        )
        replicate_seconds = _elapsed(started)
        restored_path = restore_snapshot(
            artifact.database_path,
            artifact.manifest_path,
            root / "restored.db",
            expected_manifest_sha256=artifact.manifest_sha256,
        )
        restored = ContinuityStore(restored_path)
        restored_counts = restored.counts()

        database_bytes = sum(
            path.stat().st_size
            for path in (
                database_path,
                database_path.with_name(database_path.name + "-wal"),
                database_path.with_name(database_path.name + "-shm"),
            )
            if path.exists()
        )
        max_rss_kib = _rss_kib()
        integrity = store.integrity_check()
        passed = bool(
            integrity["ok"]
            and restored_counts["events"] == events
            and restored_counts["atoms"] == len(atom_events)
            and max_rss_kib < 4 * 1024 * 1024
        )
        return {
            "benchmark_version": 1,
            "scope": "software-only; not on-device evidence",
            "platform": platform.platform(),
            "parameters": {
                "events": events,
                "queries": queries,
                "atom_stride": atom_stride,
                "atoms": len(atom_events),
            },
            "timings_seconds": {
                "ingest": ingest_seconds,
                "memory_commit": memory_seconds,
                "context_queries": query_seconds,
                "duplicate_replay": replay_seconds,
                "snapshot": snapshot_seconds,
                "archive_replication": replicate_seconds,
            },
            "rates_per_second": {
                "ingest": round(events / max(ingest_seconds, 0.000001), 2),
                "memory_commit": round(len(atom_events) / max(memory_seconds, 0.000001), 2),
                "context_queries": round(queries / max(query_seconds, 0.000001), 2),
            },
            "resources": {
                "database_bytes": database_bytes,
                "snapshot_bytes": artifact.byte_size,
                "max_rss_kib": max_rss_kib,
                "within_4_gib_process_envelope": max_rss_kib < 4 * 1024 * 1024,
            },
            "verification": {
                "integrity": integrity,
                "duplicate_replay_exactly_once": True,
                "average_context_source_ids": round(context_sources / queries, 2),
                "archive_manifest": archive_manifest,
                "archive_verified": True,
                "restored_counts": restored_counts,
            },
            "passed": passed,
        }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", type=int, default=1_000)
    parser.add_argument("--queries", type=int, default=100)
    parser.add_argument("--atom-stride", type=int, default=10)
    args = parser.parse_args(argv)
    result = run_benchmark(
        events=args.events,
        queries=args.queries,
        atom_stride=args.atom_stride,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
