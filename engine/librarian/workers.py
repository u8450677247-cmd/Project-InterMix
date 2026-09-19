"""Disposable worker supervisor; worker failure never terminates Librarian."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable, Mapping

from .models import ValidationError
from .network import LocalResourceProvider, ResourceProvider
from .store import ContinuityStore


Worker = Callable[[Mapping[str, Any]], Mapping[str, Any]]


class WorkerSupervisor:
    def __init__(
        self,
        store: ContinuityStore,
        *,
        node_id: str | None = None,
        role: str = "librarian",
        workers: Mapping[str, Worker] | None = None,
        resources: ResourceProvider | None = None,
    ) -> None:
        self.store = store
        self.node_id = node_id or store.node_id
        self.role = role
        self.resources = resources or LocalResourceProvider(Path(store.db_path).parent)
        self.workers: dict[str, Worker] = {
            "embed_atom": self._embedding_unavailable,
            "deduplicate_candidates": self._deduplicate_candidates,
            "cluster_episode": self._prepare_episode_cluster,
            "integrity_check": lambda payload: store.integrity_check(),
            "context_compile": self._compile_context,
            "fts_maintenance": self._maintain_fts,
            "llm_consolidate": self._prepare_episode_cluster,
            "llm_resolve_friction": self._prepare_friction_resolution,
        }
        self.workers.update(dict(workers or {}))

    def _compile_context(self, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        packet = self.store.compile_context(
            query=str(payload.get("query", "")),
            token_budget=int(payload.get("token_budget", 2400)),
            session_id=str(payload.get("session_id", "")),
        )
        checkpoint = payload.get("checkpoint", False)
        if not isinstance(checkpoint, bool):
            raise ValidationError("context checkpoint flag must be boolean")
        result = dict(packet)
        if checkpoint:
            result["context_snapshot_id"] = self.store.record_context_snapshot(
                session_id=str(payload.get("session_id", "")),
                reason=str(payload.get("reason", "scheduled context checkpoint")),
                summary=str(payload.get("summary", "Deterministic context checkpoint.")),
                packet=packet,
            )
        return result

    @staticmethod
    def _embedding_unavailable(payload: Mapping[str, Any]) -> Mapping[str, Any]:
        return {
            "status": "skipped",
            "reason": "embedding runtime is not configured; lexical continuity remains active",
            "atom_ref": payload.get("atom_ref"),
        }

    def _deduplicate_candidates(self, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        limit = payload.get("limit", 32)
        if isinstance(limit, bool) or not isinstance(limit, int):
            raise ValidationError("deduplication limit must be an integer")
        limit = max(1, min(limit, 100))
        with self.store.connection() as database:
            rows = database.execute(
                """
                SELECT substr(a.canonical_text,1,512),a.global_id,b.global_id
                FROM memory_atom a JOIN memory_atom b
                  ON a.canonical_text=b.canonical_text AND a.id<b.id
                WHERE a.status='active' AND b.status='active'
                ORDER BY a.canonical_text,a.id,b.id LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return {
            "status": "prepared",
            "candidate_pairs": [
                {
                    "canonical_text": str(row[0]),
                    "atom_global_ids": [str(row[1]), str(row[2])],
                }
                for row in rows
            ],
        }

    def _prepare_episode_cluster(self, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        session_id = str(payload.get("session_id", "")).strip()
        if not session_id:
            raise ValidationError("episode consolidation requires session_id")
        limit = payload.get("limit", 64)
        if isinstance(limit, bool) or not isinstance(limit, int):
            raise ValidationError("episode limit must be an integer")
        limit = max(1, min(limit, 128))
        with self.store.connection() as database:
            rows = database.execute(
                """
                SELECT global_id FROM event_log
                WHERE session_id=? ORDER BY receive_time_ms DESC,id DESC LIMIT ?
                """,
                (session_id, limit),
            ).fetchall()
        return {
            "status": "prepared",
            "operation": "episode_consolidation",
            "session_id": session_id,
            "source_event_global_ids": [str(row[0]) for row in reversed(rows)],
            "requires_cortex": True,
        }

    def _prepare_friction_resolution(self, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        friction_id = payload.get("friction_id")
        if isinstance(friction_id, bool) or not isinstance(friction_id, int) or friction_id < 1:
            raise ValidationError("friction resolution requires a positive friction_id")
        with self.store.connection() as database:
            row = database.execute(
                """
                SELECT global_id,type,logical_key,description,severity,status
                FROM friction_event WHERE id=?
                """,
                (friction_id,),
            ).fetchone()
        if not row:
            raise ValidationError("friction record is unknown")
        return {
            "status": "prepared",
            "operation": "friction_resolution_request",
            "privacy_class": "local_only",
            "friction": dict(row),
            "requires_cortex": True,
        }

    def _maintain_fts(self, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        del payload
        with self.store.connection(write=True) as database:
            database.execute("INSERT INTO memory_fts(memory_fts) VALUES('optimize')")
        return {"optimized": True}

    def run_once(self) -> dict[str, Any] | None:
        resource = self.resources.snapshot()
        temperatures = [
            value
            for value in (resource.battery_temperature_c, resource.thermal_temperature_c)
            if value is not None
        ]
        job = self.store.lease_job(
            owner_node_id=self.node_id,
            role=self.role,
            free_ram_mib=resource.free_ram_mib,
            temperature_c=max(temperatures) if temperatures else None,
            charging=resource.charging,
        )
        if job is None:
            return None
        job_id = str(job["job_id"])
        worker = self.workers.get(str(job["kind"]))
        try:
            if worker is None:
                raise ValidationError(f"no worker is configured for {job['kind']}")
            result = dict(worker(job["payload"]))
            self.store.complete_job(job_id=job_id, owner_node_id=self.node_id, result=result)
            return {"job_id": job_id, "state": "done", "result": result}
        except Exception as failure:
            state = self.store.fail_job(
                job_id=job_id,
                owner_node_id=self.node_id,
                error=f"{type(failure).__name__}: {failure}",
            )
            return {"job_id": job_id, "state": state, "error": str(failure)}

    def run_until_idle(self, *, maximum: int = 100) -> list[dict[str, Any]]:
        maximum = max(1, min(int(maximum), 10_000))
        outcomes: list[dict[str, Any]] = []
        for _ in range(maximum):
            outcome = self.run_once()
            if outcome is None:
                break
            outcomes.append(outcome)
            if outcome["state"] == "pending":
                # Do not hot-loop a repeatedly crashing worker in one scheduler tick.
                break
        return outcomes
