from __future__ import annotations

import json
import io
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import uuid
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

from librarian.client import LibrarianClient, LibrarianClientError
from librarian.health import BATTERY_THERMAL_LIMIT_C, DEVICE_THERMAL_LIMIT_C, HealthReporter
from librarian.legacy import project_existing_memory
from librarian.models import EventEnvelope, ValidationError, canonical_json, now_ms
from librarian.network import (
    LocalResourceProvider,
    NetworkSnapshot,
    ResourceSnapshot,
    StaticNetworkSentinel,
    ThermalReading,
)
from librarian.service import LibrarianApplication, build_server
from librarian.snapshots import FilesystemArchiveTarget, SnapshotManager, hash_file, restore_snapshot
from librarian.store import ContinuityStore, apply_transactional_migration
from librarian.workers import WorkerSupervisor
from librarian_bridge import DistributedContinuityBridge
from librarian_cli import main as librarian_cli_main
from memory_store import MemoryStore


class StaticResources:
    def __init__(self, snapshot: ResourceSnapshot):
        self.value = snapshot

    def snapshot(self) -> ResourceSnapshot:
        return self.value


class ResourceProbeTests(unittest.TestCase):
    def test_android_control_zones_are_not_reported_as_temperatures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def add_zone(index: int, sensor_type: str, raw: str) -> None:
                zone = root / f"thermal_zone{index}"
                zone.mkdir()
                (zone / "type").write_text(sensor_type, encoding="utf-8")
                (zone / "temp").write_text(raw, encoding="utf-8")

            # Captured on the Redmi LIBRARIAN-01 flight, plus equivalent BCL
            # aliases. Charge/current/voltage controller inputs are not
            # temperatures even though Android exposes them as thermal zones.
            add_zone(9, "soc", "63")
            add_zone(8, "socd", "92")
            add_zone(2, "pm6150-ibat-lvl1", "546")
            add_zone(1, "pm6150-ibat-lvl0", "546")
            add_zone(5, "pm6150-vbat-lvl2", "3737")
            add_zone(7, "pm6150l-vph-lvl2", "3000")
            add_zone(26, "cpu-1-0-usr", "37700")
            add_zone(10, "pm6150l-tz", "37000")
            add_zone(74, "battery", "34200")

            reading = LocalResourceProvider._thermal(root)
            battery = LocalResourceProvider._battery_thermal(root)

        self.assertIsNotNone(reading)
        assert reading is not None
        self.assertAlmostEqual(reading.temperature_c, 37.7)
        self.assertEqual(reading.sensor_type, "cpu-1-0-usr")
        self.assertEqual(reading.zone, "thermal_zone26")
        self.assertIsNotNone(battery)
        assert battery is not None
        self.assertAlmostEqual(battery.temperature_c, 34.2)
        self.assertEqual(battery.sensor_type, "battery")
        self.assertEqual(battery.zone, "thermal_zone74")

    def test_named_battery_zone_falls_back_when_power_supply_is_unreadable(self):
        readings = [
            ThermalReading(temperature_c=34.2, sensor_type="battery", zone="thermal_zone74"),
            ThermalReading(
                temperature_c=37.7,
                sensor_type="cpu-1-0-usr",
                zone="thermal_zone26",
            ),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            provider = LocalResourceProvider(temporary)
            with (
                mock.patch.object(provider, "_battery", return_value=(63.0, None, None)),
                mock.patch.object(provider, "_thermal_readings", return_value=readings),
            ):
                snapshot = provider.snapshot()

        self.assertAlmostEqual(snapshot.battery_temperature_c or 0, 34.2)
        self.assertEqual(snapshot.battery_temperature_sensor_type, "battery")
        self.assertEqual(snapshot.battery_temperature_sensor_zone, "thermal_zone74")
        self.assertAlmostEqual(snapshot.thermal_temperature_c or 0, 37.7)
        self.assertEqual(snapshot.thermal_sensor_type, "cpu-1-0-usr")


class LibrarianTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.db_path = self.root / "sovereign.db"
        self.store = ContinuityStore(self.db_path)
        self.store.register_node("cortex-primary", "cortex", "Pixel 10 Pro")
        self.store.register_node("archive-ds215j", "archive", "DS215j")

    def tearDown(self):
        self.temp.cleanup()

    def event(self, text: str = "Remember the cyan cockpit.", sequence: int = 1) -> EventEnvelope:
        return EventEnvelope.from_mapping(
            {
                "global_event_id": str(uuid.uuid4()),
                "node_id": "cortex-primary",
                "node_sequence": sequence,
                "source_time_ms": now_ms(),
                "kind": "chat.user",
                "actor": "user",
                "content": text,
                "payload": {"session_id": "test"},
                "schema_version": 1,
            }
        )

    @staticmethod
    def delta(event: EventEnvelope, *, value: str = "cyan", client_id: str = "theme") -> dict:
        return {
            "schema_version": 1,
            "atoms": [
                {
                    "client_id": client_id,
                    "class": "preference",
                    "domain": "user",
                    "subject": "user",
                    "predicate": "ui.theme",
                    "object_text": value,
                    "canonical_text": f"User preference is {value}.",
                    "epistemic": "user_stated",
                    "confidence": 0.95,
                    "salience": 0.8,
                    "stability": 0.8,
                    "novelty": 0.6,
                    "utility": 0.9,
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


class SchemaAndIngestionTests(LibrarianTestCase):
    def test_additive_migration_preserves_current_memory_store(self):
        alternate = self.root / "existing.db"
        existing = MemoryStore(alternate)
        session = existing.create_session("Before Librarian")
        message_id = existing.append_message(session["id"], "user", "Keep every old row.")
        memory_id = existing.upsert_memory(
            kind="user_preference",
            memory_key="theme",
            value="cyan and purple",
            source_message_id=message_id,
            explicitly_stated=True,
        )
        ContinuityStore(alternate)
        reopened = MemoryStore(alternate)
        self.assertEqual(reopened.message_count(session["id"]), 1)
        self.assertEqual(reopened.search_memories("cyan purple")[0]["id"], memory_id)
        with sqlite3.connect(alternate) as database:
            self.assertEqual(database.execute("SELECT COUNT(*) FROM memories").fetchone()[0], 1)
            self.assertEqual(database.execute("SELECT COUNT(*) FROM lattice_meta").fetchone()[0], 2)

    def test_cli_snapshot_gates_first_migration_and_repeat_init_is_idempotent(self):
        target = self.root / "cli-existing.db"
        existing = MemoryStore(target)
        session = existing.create_session("Before CLI migration")
        existing.append_message(session["id"], "user", "Preserve this row.")
        backup_directory = self.root / "migration-backups"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(
                librarian_cli_main(
                    [
                        "--db",
                        str(target),
                        "init",
                        "--backup-dir",
                        str(backup_directory),
                    ]
                ),
                0,
            )
        backups = list(backup_directory.glob("*.sqlite3"))
        manifests = list(backup_directory.glob("*.manifest.json"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(len(manifests), 1)
        self.assertEqual(MemoryStore(backups[0]).message_count(session["id"]), 1)

        with redirect_stdout(io.StringIO()):
            self.assertEqual(
                librarian_cli_main(
                    [
                        "--db",
                        str(target),
                        "init",
                        "--backup-dir",
                        str(backup_directory),
                    ]
                ),
                0,
            )
        self.assertEqual(len(list(backup_directory.glob("*.sqlite3"))), 1)

    def test_failed_migration_rolls_back_completely(self):
        target = self.root / "rollback.db"
        with self.assertRaises(sqlite3.OperationalError):
            apply_transactional_migration(
                target,
                "CREATE TABLE should_rollback(id INTEGER); INSERT INTO missing_table VALUES(1);",
            )
        with sqlite3.connect(target) as database:
            present = database.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='should_rollback'"
            ).fetchone()
        self.assertIsNone(present)

    def test_supplied_v01_shape_is_upgraded_without_reapplying_alter(self):
        target = self.root / "v01.db"
        with sqlite3.connect(target) as database:
            database.executescript(
                """
                CREATE TABLE event_log(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_ms INTEGER NOT NULL,
                    session_id TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    parent_event_id INTEGER,
                    tool_name TEXT,
                    metadata_json TEXT,
                    content_sha256 TEXT
                );
                """
            )
        store = ContinuityStore(target)
        store.register_node("cortex-primary", "cortex", "Pixel")
        event = self.event()
        result = store.ingest_event(event)
        self.assertTrue(result.committed)
        self.assertEqual(store.ingest_event(event).status, "duplicate")

    def test_event_replay_100_times_commits_exactly_once(self):
        event = self.event()
        results = [self.store.ingest_event(event) for _ in range(100)]
        self.assertEqual(sum(item.committed for item in results), 1)
        self.assertEqual(sum(item.status == "duplicate" for item in results), 99)
        self.assertEqual(self.store.counts()["events"], 1)

    def test_receive_time_is_owned_by_the_librarian_authority(self):
        raw = self.event().as_mapping()
        raw["receive_time_ms"] = 1
        self.store.ingest_event(raw)
        stored = self.store.event(raw["global_event_id"])
        self.assertGreater(stored["receive_time_ms"], 1)

    def test_event_identity_conflict_is_recorded_and_rows_are_immutable(self):
        event = self.event()
        committed = self.store.ingest_event(event)
        changed = EventEnvelope.from_mapping(
            {**event.as_mapping(), "content": "Different bytes", "payload_hash": None}
        )
        conflict = self.store.ingest_event(changed)
        self.assertEqual(conflict.status, "conflict")
        self.assertIsNotNone(conflict.conflict_id)
        with self.assertRaises(sqlite3.IntegrityError), self.store.connection(write=True) as database:
            database.execute("UPDATE event_log SET content='mutated' WHERE id=?", (committed.event_id,))

    def test_invalid_hash_and_schema_are_rejected_before_commit(self):
        raw = self.event().as_mapping()
        raw["payload_hash"] = "0" * 64
        with self.assertRaises(ValidationError):
            self.store.ingest_event(raw)
        raw = self.event(sequence=2).as_mapping()
        raw["schema_version"] = 99
        raw.pop("payload_hash")
        with self.assertRaises(ValidationError):
            self.store.ingest_event(raw)
        raw = self.event(sequence=3).as_mapping()
        raw["node_sequence"] = 3.5
        raw.pop("payload_hash")
        with self.assertRaises(ValidationError):
            self.store.ingest_event(raw)
        with self.assertRaises(ValidationError):
            canonical_json({"non_finite": float("nan")})
        self.assertEqual(self.store.counts()["events"], 0)


class ProvenanceAndFrictionTests(LibrarianTestCase):
    def test_memory_delta_requires_provenance_and_authority(self):
        event = self.event()
        self.store.ingest_event(event)
        result = self.store.apply_memory_delta(
            source_event_global_id=event.global_event_id,
            actor_node_id="cortex-primary",
            delta=self.delta(event),
        )
        self.assertEqual(result["atoms"], 1)
        memory = self.store.search_memories("cyan cockpit")[0]
        self.assertEqual(memory["evidence"][0]["event_global_id"], event.global_event_id)
        self.store.register_node("foreign-client", "client", "Foreign")
        with self.assertRaises(ValidationError):
            self.store.apply_memory_delta(
                source_event_global_id=event.global_event_id,
                actor_node_id="foreign-client",
                delta=self.delta(event, client_id="unauthorized"),
            )

    def test_derived_identity_replay_cannot_change_atom_or_evidence_bytes(self):
        event = self.event()
        self.store.ingest_event(event)
        original = self.delta(event)
        self.store.apply_memory_delta(
            source_event_global_id=event.global_event_id,
            actor_node_id="cortex-primary",
            delta=original,
        )

        changed_atom = self.delta(event)
        changed_atom["atoms"][0]["object_text"] = "purple"
        with self.assertRaises(ValidationError):
            self.store.apply_memory_delta(
                source_event_global_id=event.global_event_id,
                actor_node_id="cortex-primary",
                delta=changed_atom,
            )

        changed_evidence = self.delta(event)
        changed_evidence["evidence"][0]["weight"] = 0.25
        with self.assertRaises(ValidationError):
            self.store.apply_memory_delta(
                source_event_global_id=event.global_event_id,
                actor_node_id="cortex-primary",
                delta=changed_evidence,
            )
        self.assertEqual(self.store.counts()["atoms"], 1)

    def test_malformed_delta_rolls_back_inserted_atoms(self):
        event = self.event()
        self.store.ingest_event(event)
        broken = self.delta(event)
        broken["evidence"] = []
        with self.assertRaises(ValidationError):
            self.store.apply_memory_delta(
                source_event_global_id=event.global_event_id,
                actor_node_id="cortex-primary",
                delta=broken,
            )
        self.assertEqual(self.store.counts()["atoms"], 0)

    def test_conflicting_state_opens_friction_without_silent_flip_then_resolves(self):
        first = self.event("Cyan is my theme.", 1)
        self.store.ingest_event(first)
        first_delta = self.delta(first, value="cyan", client_id="cyan")
        first_delta["state_updates"] = [
            {
                "scope": "user",
                "key": "ui.theme",
                "value": "cyan",
                "source_atom_ref": "cyan",
            }
        ]
        self.store.apply_memory_delta(
            source_event_global_id=first.global_event_id,
            actor_node_id="cortex-primary",
            delta=first_delta,
        )

        second = self.event("Purple is my theme.", 2)
        self.store.ingest_event(second)
        second_delta = self.delta(second, value="purple", client_id="purple-proposal")
        second_delta["state_updates"] = [
            {
                "scope": "user",
                "key": "ui.theme",
                "value": "purple",
                "source_atom_ref": "purple-proposal",
            }
        ]
        conflict = self.store.apply_memory_delta(
            source_event_global_id=second.global_event_id,
            actor_node_id="cortex-primary",
            delta=second_delta,
        )
        self.assertEqual(self.store.list_state()[0]["value"], "cyan")
        self.assertEqual(len(conflict["state_conflicts"]), 1)
        friction_id = conflict["state_conflicts"][0]
        self.assertEqual(self.store.list_friction()[0]["status"], "open")

        third = self.event("I explicitly changed it to purple.", 3)
        self.store.ingest_event(third)
        resolution = self.delta(third, value="purple", client_id="purple-resolution")
        resolution["state_updates"] = [
            {
                "scope": "user",
                "key": "ui.theme",
                "value": "purple",
                "source_atom_ref": "purple-resolution",
                "resolve_friction_id": friction_id,
            }
        ]
        self.store.apply_memory_delta(
            source_event_global_id=third.global_event_id,
            actor_node_id="cortex-primary",
            delta=resolution,
        )
        self.assertEqual(self.store.list_state()[0]["value"], "purple")
        self.assertEqual(self.store.list_friction(open_only=False)[0]["status"], "resolved")
        self.assertEqual(self.store.counts()["atoms"], 3)

    def test_context_packet_is_bounded_and_has_source_ids_without_embeddings(self):
        event = self.event("Alpha continuity evidence.", 1)
        self.store.ingest_event(event)
        atoms = []
        evidence = []
        for index in range(40):
            ref = f"alpha-{index}"
            atoms.append(
                {
                    "client_id": ref,
                    "class": "fact",
                    "canonical_text": "alpha " + (f"continuity fact {index} " * 20),
                    "epistemic": "observed",
                    "confidence": 0.8,
                    "salience": 0.6,
                    "utility": 0.7,
                }
            )
            evidence.append(
                {
                    "atom_ref": ref,
                    "event_global_id": event.global_event_id,
                    "relation": "source",
                }
            )
        self.store.apply_memory_delta(
            source_event_global_id=event.global_event_id,
            actor_node_id="cortex-primary",
            delta={"schema_version": 1, "atoms": atoms, "evidence": evidence},
        )
        packet = self.store.compile_context(query="alpha continuity", token_budget=600)
        actual_tokens = max(1, (len(canonical_json(packet).encode("utf-8")) + 2) // 3)
        self.assertLessEqual(packet["approx_tokens"], 600)
        self.assertLessEqual(actual_tokens, 600)
        self.assertTrue(packet["truncated"])
        self.assertFalse(packet["embeddings_used"])
        self.assertTrue(packet["sections"]["memories"])
        self.assertIn("global_id", packet["sections"]["memories"][0])
        self.assertTrue(packet["sections"]["memories"][0]["evidence"])


class SnapshotWorkerAndHealthTests(LibrarianTestCase):
    def setUp(self):
        super().setUp()
        self.event_one = self.event()
        self.store.ingest_event(self.event_one)
        self.store.apply_memory_delta(
            source_event_global_id=self.event_one.global_event_id,
            actor_node_id="cortex-primary",
            delta=self.delta(self.event_one),
        )

    def test_snapshot_replication_restore_hash_and_duplicate_replay(self):
        manager = SnapshotManager(self.store, self.root / "snapshots")
        artifact = manager.create(reason="closed-loop test")
        self.assertEqual(hash_file(artifact.database_path), artifact.database_sha256)
        broken_root = self.root / "not-a-directory"
        broken_root.write_text("block", encoding="utf-8")
        with self.assertRaises((FileExistsError, ValidationError)):
            manager.replicate(
                artifact.snapshot_id, FilesystemArchiveTarget(broken_root)
            )
        self.assertEqual(self.store.counts()["pending_replication"], 1)

        relative = manager.replicate(
            artifact.snapshot_id, FilesystemArchiveTarget(self.root / "nas")
        )
        self.assertTrue((self.root / "nas" / relative).is_file())
        restored_path = restore_snapshot(
            artifact.database_path,
            artifact.manifest_path,
            self.root / "restored.db",
            expected_manifest_sha256=artifact.manifest_sha256,
        )
        restored = ContinuityStore(restored_path)
        replay = restored.ingest_event(self.event_one)
        self.assertEqual(replay.status, "duplicate")
        self.assertEqual(restored.counts()["events"], 1)
        self.assertEqual(restored.counts()["atoms"], 1)
        self.assertTrue(restored.integrity_check()["ok"])

    def test_corrupted_snapshot_is_rejected(self):
        manager = SnapshotManager(self.store, self.root / "snapshots")
        artifact = manager.create(reason="corruption test")
        with self.assertRaises(ValidationError):
            restore_snapshot(
                artifact.database_path,
                artifact.manifest_path,
                self.root / "wrong-digest.db",
                expected_manifest_sha256="0" * 64,
            )
        corrupt = self.root / "corrupt.sqlite3"
        corrupt.write_bytes(artifact.database_path.read_bytes() + b"corruption")
        with self.assertRaises(ValidationError):
            restore_snapshot(corrupt, artifact.manifest_path, self.root / "should-not-exist.db")
        self.assertFalse((self.root / "should-not-exist.db").exists())

    def test_worker_crash_retries_then_opens_runtime_friction(self):
        job_id = self.store.enqueue_job(kind="embed_atom", payload={"atom": "missing"})

        def crash(payload):
            del payload
            raise MemoryError("synthetic worker OOM")

        supervisor = WorkerSupervisor(self.store, workers={"embed_atom": crash})
        outcomes = []
        for attempt in range(3):
            outcomes.append(supervisor.run_once())
            if attempt < 2:
                with self.store.connection(write=True) as database:
                    database.execute(
                        "UPDATE librarian_job SET not_before_ms=0 WHERE job_id=?",
                        (job_id,),
                    )
        self.assertEqual(outcomes[-1]["state"], "failed")
        self.assertEqual(self.store.get_job(job_id)["state"], "failed")
        self.assertEqual(self.store.list_friction()[0]["type"], "runtime_failure")
        self.assertTrue(self.store.integrity_check()["ok"])

    def test_builtin_optional_workers_prepare_bounded_results(self):
        embed_job = self.store.enqueue_job(
            kind="embed_atom",
            payload={"atom_ref": "not-required"},
        )
        embed = WorkerSupervisor(self.store).run_once()
        self.assertEqual(embed["state"], "done")
        self.assertEqual(embed["result"]["status"], "skipped")
        self.assertEqual(self.store.get_job(embed_job)["state"], "done")

        checkpoint_job = self.store.enqueue_job(
            kind="context_compile",
            payload={
                "query": "cyan cockpit",
                "token_budget": 600,
                "session_id": "test",
                "checkpoint": True,
                "reason": "unit test",
                "summary": "Synthetic checkpoint.",
            },
        )
        checkpoint = WorkerSupervisor(self.store).run_once()
        self.assertEqual(checkpoint["state"], "done")
        self.assertIn("context_snapshot_id", checkpoint["result"])
        self.assertEqual(self.store.get_job(checkpoint_job)["state"], "done")

    def test_worker_resource_requirements_fail_closed_until_safe(self):
        job_id = self.store.enqueue_job(
            kind="integrity_check",
            payload={},
            min_free_ram_mib=1024,
            max_temp_c=40,
            requires_charging=True,
        )
        constrained = StaticResources(
            ResourceSnapshot(
                free_ram_mib=512,
                total_ram_mib=4096,
                free_storage_mib=10_000,
                total_storage_mib=100_000,
                battery_pct=60,
                battery_temperature_c=42,
                charging=False,
                thermal_temperature_c=41,
            )
        )
        self.assertIsNone(WorkerSupervisor(self.store, resources=constrained).run_once())
        self.assertEqual(self.store.get_job(job_id)["state"], "pending")

        safe = StaticResources(
            ResourceSnapshot(
                free_ram_mib=2048,
                total_ram_mib=4096,
                free_storage_mib=10_000,
                total_storage_mib=100_000,
                battery_pct=80,
                battery_temperature_c=34,
                charging=True,
                thermal_temperature_c=36,
            )
        )
        outcome = WorkerSupervisor(self.store, resources=safe).run_once()
        self.assertEqual(outcome["state"], "done")
        self.assertEqual(self.store.get_job(job_id)["state"], "done")

    def test_health_uses_sensor_class_specific_thermal_limits(self):
        def report(*, battery: float | None, device: float | None) -> dict:
            resources = StaticResources(
                ResourceSnapshot(
                    free_ram_mib=2048,
                    total_ram_mib=4096,
                    free_storage_mib=20_000,
                    total_storage_mib=100_000,
                    battery_pct=None,
                    battery_temperature_c=battery,
                    charging=None,
                    thermal_temperature_c=device,
                    thermal_sensor_type="cpu-1-0-usr" if device is not None else None,
                    thermal_sensor_zone="thermal_zone26" if device is not None else None,
                )
            )
            return HealthReporter(self.store, resources=resources).report()

        normal_silicon = report(battery=None, device=63.0)
        self.assertNotIn("THERMAL_LIMIT", normal_silicon["states"])
        self.assertEqual(normal_silicon["thermal_policy"]["triggered_by"], [])
        self.assertEqual(
            normal_silicon["thermal_policy"]["battery_limit_c"],
            BATTERY_THERMAL_LIMIT_C,
        )
        self.assertEqual(
            normal_silicon["thermal_policy"]["device_limit_c"],
            DEVICE_THERMAL_LIMIT_C,
        )

        hot_battery = report(battery=BATTERY_THERMAL_LIMIT_C, device=37.7)
        self.assertIn("THERMAL_LIMIT", hot_battery["states"])
        self.assertEqual(hot_battery["thermal_policy"]["triggered_by"], ["battery"])

        hot_device = report(battery=None, device=DEVICE_THERMAL_LIMIT_C)
        self.assertIn("THERMAL_LIMIT", hot_device["states"])
        self.assertEqual(hot_device["thermal_policy"]["triggered_by"], ["device"])

    def test_health_is_explicit_when_cortex_nas_and_wan_disappear(self):
        network = StaticNetworkSentinel(
            NetworkSnapshot(
                lan_reachable=True,
                nas_reachable=False,
                cortex_reachable=False,
                wifi_internet_validated=False,
                cellular_internet_available=False,
                active_wan="none",
                metered=False,
            )
        )
        resources = StaticResources(
            ResourceSnapshot(
                free_ram_mib=1500,
                total_ram_mib=4096,
                free_storage_mib=20_000,
                total_storage_mib=100_000,
                battery_pct=75,
                battery_temperature_c=31,
                charging=True,
                thermal_temperature_c=34,
            )
        )
        report = HealthReporter(self.store, network=network, resources=resources).report()
        self.assertIn("NO_NAS", report["states"])
        self.assertIn("NO_CORTEX", report["states"])
        self.assertIn("NO_WAN", report["states"])
        self.assertNotIn("DB_RECOVERY", report["states"])


class ClientServiceAndLegacyTests(LibrarianTestCase):
    def test_authenticated_service_and_offline_cortex_outbox(self):
        token = "t" * 48
        snapshots = SnapshotManager(self.store, self.root / "service-snapshots")
        application = LibrarianApplication(
            self.store,
            snapshots=snapshots,
            archive_target=FilesystemArchiveTarget(self.root / "service-nas"),
        )
        server = build_server(
            application,
            host="127.0.0.1",
            port=0,
            bearer_token=token,
            allowed_node_ids={"cortex-primary"},
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        outbox = self.root / "cortex-outbox.db"
        try:
            offline = LibrarianClient(
                "http://127.0.0.1:9",
                node_id="cortex-primary",
                token=token,
                outbox_path=outbox,
                timeout_seconds=0.25,
            )
            event = offline.create_event(actor="user", kind="chat.user", content="Queued offline")
            queued = offline.submit_event(event)
            self.assertEqual(queued["status"], "queued")
            self.assertEqual(offline.outbox_count(), 1)

            online = LibrarianClient(
                f"http://127.0.0.1:{server.server_address[1]}",
                node_id="cortex-primary",
                token=token,
                outbox_path=outbox,
            )
            flushed = online.flush_outbox()
            self.assertEqual(flushed, {"sent": 1, "failed": 0, "remaining": 0})
            self.assertEqual(self.store.counts()["events"], 1)
            self.assertEqual(online.health()["primary_state"], "HEALTHY")

            spoofed = EventEnvelope.from_mapping(
                {
                    "global_event_id": str(uuid.uuid4()),
                    "node_id": "archive-ds215j",
                    "node_sequence": 1,
                    "source_time_ms": now_ms(),
                    "kind": "chat.user",
                    "actor": "user",
                    "content": "spoofed origin",
                }
            )
            with self.assertRaises(LibrarianClientError):
                online.submit_event(spoofed, queue_on_failure=False)

            forbidden_node = LibrarianClient(
                f"http://127.0.0.1:{server.server_address[1]}",
                node_id="archive-ds215j",
                token=token,
                outbox_path=self.root / "forbidden-outbox.db",
            )
            with self.assertRaises(LibrarianClientError):
                forbidden_node.health()

            heartbeat_id = str(uuid.uuid4())
            heartbeat = {
                "observed_at_ms": now_ms(),
                "status": "ok",
                "free_ram_mib": 2048,
                "details": {"source": "unit-test"},
            }
            self.assertEqual(
                online.heartbeat(heartbeat, request_id=heartbeat_id)["status"],
                "committed",
            )
            self.assertEqual(
                online.heartbeat(heartbeat, request_id=heartbeat_id)["status"],
                "duplicate",
            )
            with self.assertRaises(LibrarianClientError):
                online.heartbeat(
                    {**heartbeat, "free_ram_mib": 1024},
                    request_id=heartbeat_id,
                )

            job_id = str(uuid.uuid4())
            submitted = online.enqueue_job(
                kind="context_compile",
                payload={"query": "queued context", "session_id": "test"},
                preferred_role="cortex",
                job_id=job_id,
            )
            self.assertEqual(submitted["job_id"], job_id)
            replayed = online.enqueue_job(
                kind="context_compile",
                payload={"query": "queued context", "session_id": "test"},
                preferred_role="cortex",
                job_id=job_id,
            )
            self.assertEqual(replayed["job_id"], job_id)
            leased = self.store.lease_job(owner_node_id="cortex-primary", role="cortex")
            self.assertEqual(leased["job_id"], job_id)
            self.assertEqual(online.submit_job_result(job_id, {"ok": True})["state"], "done")
            self.assertEqual(online.submit_job_result(job_id, {"ok": True})["state"], "done")
            with self.assertRaises(LibrarianClientError):
                online.submit_job_result(job_id, {"ok": False})

            snapshot_request_id = str(uuid.uuid4())
            first_snapshot = online.request_snapshot(
                reason="service idempotency test",
                replicate=True,
                request_id=snapshot_request_id,
            )
            second_snapshot = online.request_snapshot(
                reason="service idempotency test",
                replicate=True,
                request_id=snapshot_request_id,
            )
            self.assertEqual(first_snapshot["snapshot_id"], second_snapshot["snapshot_id"])
            self.assertEqual(self.store.counts()["snapshots"], 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_non_loopback_service_requires_authentication(self):
        with self.assertRaises(ValidationError):
            build_server(LibrarianApplication(self.store), host="0.0.0.0", port=0)
        with self.assertRaises(ValidationError):
            build_server(
                LibrarianApplication(self.store),
                host="0.0.0.0",
                port=0,
                bearer_token="t" * 48,
            )

    def test_termux_bridge_closes_the_event_memory_context_loop(self):
        token = "t" * 48
        server = build_server(
            LibrarianApplication(self.store),
            host="127.0.0.1",
            port=0,
            bearer_token=token,
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            client = LibrarianClient(
                f"http://127.0.0.1:{server.server_address[1]}",
                node_id="cortex-primary",
                token=token,
                outbox_path=self.root / "bridge-outbox.db",
            )
            bridge = DistributedContinuityBridge(client)
            turn = bridge.capture_user_turn(
                "Remember that the cockpit theme is cyan.",
                session_id="bridge-session",
            )
            self.assertEqual(turn.delivery, "committed")
            self.assertIsNotNone(turn.event_global_id)
            self.assertEqual(turn.context_packet["session_id"], "bridge-session")

            local = MemoryStore(self.db_path)
            session = local.create_session("Bridge")
            message_id = local.append_message(
                session["id"], "user", "Remember that the cockpit theme is cyan."
            )
            local.upsert_memory(
                kind="user_preference",
                memory_key="cockpit.theme",
                value="cyan",
                source_message_id=message_id,
                confidence=0.95,
                salience=0.9,
                explicitly_stated=True,
            )
            self.assertEqual(
                bridge.sync_committed_memories(
                    local,
                    source_message_id=message_id,
                    source_event_global_id=turn.event_global_id,
                ),
                "committed",
            )
            self.assertEqual(
                bridge.capture_assistant_turn(
                    "I will preserve the cyan cockpit preference.",
                    session_id="bridge-session",
                    parent_event_global_id=turn.event_global_id,
                ),
                "committed",
            )
            self.assertEqual(self.store.counts()["events"], 2)
            self.assertEqual(self.store.counts()["atoms"], 1)
            recalled = self.store.compile_context(query="cockpit theme", token_budget=600)
            self.assertIn("cyan", recalled["sections"]["memories"][0]["canonical_text"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_legacy_projection_is_idempotent_and_preserves_original_rows(self):
        target = self.root / "legacy.db"
        existing = MemoryStore(target)
        session = existing.create_session("Legacy")
        message_id = existing.append_message(session["id"], "user", "Use a cyan-purple theme.")
        existing.upsert_memory(
            kind="user_preference",
            memory_key="theme",
            value="cyan-purple",
            source_message_id=message_id,
            confidence=0.9,
            salience=0.8,
            explicitly_stated=True,
        )
        store = ContinuityStore(target)
        first = project_existing_memory(store)
        first_counts = store.counts()
        second = project_existing_memory(store)
        second_counts = store.counts()
        self.assertEqual(first["messages_seen"], 1)
        self.assertGreaterEqual(second["events_duplicate"], 1)
        self.assertEqual(first_counts, second_counts)
        reopened = MemoryStore(target)
        self.assertEqual(reopened.message_count(session["id"]), 1)
        self.assertEqual(reopened.search_memories("cyan purple")[0]["value"], "cyan-purple")


class AndroidSentinelContractTests(unittest.TestCase):
    def test_android_sentinel_is_narrow_and_endpoint_reachability_is_injected(self):
        source = (
            ROOT
            / "android/app/src/main/java/dev/anicloud/sovereign/LibrarianNetworkSentinel.kt"
        ).read_text(encoding="utf-8")
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(
            encoding="utf-8"
        )
        self.assertIn("ACCESS_NETWORK_STATE", manifest)
        self.assertIn("ConnectivityManager", source)
        self.assertIn("NET_CAPABILITY_VALIDATED", source)
        self.assertIn("LibrarianEndpointProbe", source)
        self.assertNotIn("TelephonyManager", source)
        self.assertNotIn("WifiManager", source)


if __name__ == "__main__":
    unittest.main()
