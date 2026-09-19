"""Explicit LIBRARIAN-01 health-state calculation."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from .network import (
    LocalResourceProvider,
    NetworkSentinel,
    ResourceProvider,
    UnknownNetworkSentinel,
)
from .store import ContinuityStore


HEALTH_STATES = (
    "BOOTING",
    "DEGRADED_LOCKED",
    "HEALTHY",
    "NO_NAS",
    "NO_CORTEX",
    "NO_WAN",
    "CELLULAR_FAILOVER",
    "MEMORY_PRESSURE",
    "THERMAL_LIMIT",
    "STORAGE_PRESSURE",
    "DB_RECOVERY",
    "UPDATE_PENDING",
    "RECOVERY_REQUIRED",
)


class HealthReporter:
    def __init__(
        self,
        store: ContinuityStore,
        *,
        network: NetworkSentinel | None = None,
        resources: ResourceProvider | None = None,
    ) -> None:
        self.store = store
        self.network = network or UnknownNetworkSentinel()
        self.resources = resources or LocalResourceProvider(Path(store.db_path).parent)
        self.started_monotonic = time.monotonic()

    def report(self) -> dict[str, Any]:
        integrity = self.store.integrity_check()
        resource = self.resources.snapshot()
        network = self.network.snapshot()
        counts = self.store.counts()
        states: list[str] = []
        if not integrity["ok"]:
            states.append("DB_RECOVERY")
        if resource.total_storage_mib and resource.free_storage_mib is not None:
            if resource.free_storage_mib / max(resource.total_storage_mib, 1) < 0.10:
                states.append("STORAGE_PRESSURE")
        if resource.free_ram_mib is not None and resource.free_ram_mib < 384:
            states.append("MEMORY_PRESSURE")
        hottest = max(
            value
            for value in (resource.battery_temperature_c, resource.thermal_temperature_c, -273.0)
            if value is not None
        )
        if hottest >= 43.0:
            states.append("THERMAL_LIMIT")
        if network.nas_reachable is False:
            states.append("NO_NAS")
        effective_cortex = network.cortex_reachable
        wifi_wan = network.wifi_internet_validated
        cellular = network.cellular_internet_available
        if wifi_wan is False and cellular is False:
            states.append("NO_WAN")
        elif network.active_wan == "cellular" or (wifi_wan is False and cellular is True):
            states.append("CELLULAR_FAILOVER")
        with self.store.connection() as database:
            failed_jobs = int(
                database.execute("SELECT COUNT(*) FROM librarian_job WHERE state='failed'").fetchone()[0]
            )
            worker_crashes = int(
                database.execute(
                    """
                    SELECT COALESCE(SUM(attempts),0) FROM librarian_job
                    WHERE last_error IS NOT NULL
                    """
                ).fetchone()[0]
            )
            cortex = database.execute(
                """
                SELECT n.last_seen_ms,h.observed_at_ms,h.status
                FROM node_registry n LEFT JOIN node_heartbeat h ON h.node_id=n.node_id
                WHERE n.node_role='cortex' AND n.enabled=1
                ORDER BY n.last_seen_ms DESC LIMIT 1
                """
            ).fetchone()
            latest_snapshot = database.execute(
                """
                SELECT snapshot_id,generation,created_at_ms,db_sha256,nas_verified,nas_relative_path
                FROM snapshot_catalog ORDER BY generation DESC LIMIT 1
                """
            ).fetchone()
            last_nas = database.execute(
                """
                SELECT snapshot_id,generation,created_at_ms,nas_relative_path
                FROM snapshot_catalog WHERE nas_verified=1 ORDER BY generation DESC LIMIT 1
                """
            ).fetchone()
        if effective_cortex is None and cortex:
            last_seen = max(int(cortex[0] or 0), int(cortex[1] or 0))
            effective_cortex = (
                str(cortex[2] or "ok") not in {"offline", "maintenance"}
                and int(time.time() * 1000) - last_seen <= 180_000
            )
        if effective_cortex is False:
            states.append("NO_CORTEX")
        if failed_jobs >= 3 or worker_crashes >= 3:
            states.append("RECOVERY_REQUIRED")
        if not states:
            states.append("HEALTHY")
        last_event = self.store.last_event()
        return {
            "node_id": self.store.node_id,
            "service": "intermix-librarian",
            "lattice_schema_version": self.store.schema_version(),
            "states": states,
            "primary_state": states[0],
            "uptime_seconds": int(time.monotonic() - self.started_monotonic),
            "database": integrity,
            "queues": {
                "jobs": counts["pending_jobs"],
                "replication": counts["pending_replication"],
                "open_friction": counts["open_friction"],
            },
            "counts": counts,
            "last_committed_event": (
                {
                    "global_id": last_event["global_id"],
                    "origin_node_id": last_event["origin_node_id"],
                    "origin_seq": last_event["origin_seq"],
                    "receive_time_ms": last_event["receive_time_ms"],
                }
                if last_event
                else None
            ),
            "last_snapshot": dict(latest_snapshot) if latest_snapshot else None,
            "last_nas_replication": dict(last_nas) if last_nas else None,
            "worker_crash_count": worker_crashes,
            "failed_job_count": failed_jobs,
            "effective_cortex_reachable": effective_cortex,
            "resources": resource.as_mapping(),
            "network": network.as_mapping(),
        }
