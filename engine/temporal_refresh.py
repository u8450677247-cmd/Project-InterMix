"""Idle refresh of provenance-bearing volatile evidence."""

from __future__ import annotations

import hashlib
import re
import threading
from datetime import datetime, timedelta, timezone
from typing import Any

from freshness_policy import assess_freshness
from memory_store import MemoryStore
from web_search import search_web, validate_web_evidence


def _source_urls(evidence: str) -> list[str]:
    return [
        match.group(1).rstrip(".,;:!?)]}'\"")
        for match in re.finditer(r"(?m)^Source:\s+(https?://\S+)", evidence)
    ][:8]


def refresh_due_facts(
    store: MemoryStore,
    *,
    cancel_event: threading.Event | None = None,
    limit: int = 2,
) -> dict[str, Any]:
    due = store.due_fact_watches(limit=limit)
    result: dict[str, Any] = {
        "status": "complete",
        "due": len(due),
        "refreshed": 0,
        "unavailable": 0,
        "items": [],
    }
    for watch in due:
        if cancel_event is not None and cancel_event.is_set():
            result["status"] = "cancelled"
            break
        watch_id = int(watch["id"])
        query = str(watch["query"])
        decision = assess_freshness(query, forced=True)
        evidence = search_web(
            decision.optimized_query,
            evaluation_query=query,
        )
        valid = validate_web_evidence(query, evidence)
        if not valid:
            store.complete_fact_watch(
                watch_id,
                status="unavailable",
                metadata={"checked_query": decision.optimized_query},
            )
            result["unavailable"] += 1
            result["items"].append({"id": watch_id, "query": query, "status": "unavailable"})
            continue

        cadence = max(900, int(watch.get("cadence_seconds") or 86400))
        expires = (
            datetime.now(timezone.utc) + timedelta(seconds=cadence)
        ).replace(microsecond=0).isoformat()
        digest = hashlib.sha256(evidence.encode("utf-8", "replace")).hexdigest()
        urls = _source_urls(evidence)
        store.cache_web(
            query,
            evidence,
            expires,
            metadata={
                "status": "verified_idle_refresh",
                "content_sha256": digest,
                "source_urls": urls,
                "search_plan": decision.optimized_query,
            },
        )
        store.record_memory_event(
            session_id=None,
            domain="knowledge",
            event_type="evidence_refresh",
            content=f"Verified current evidence refreshed for: {query}. Sources: {', '.join(urls) or 'provider evidence block'}",
            source_message_id=None,
            explicitly_stated=False,
            sensitive=False,
            salience=0.58,
            metadata={
                "claim_status": "evidence_only",
                "content_sha256": digest,
                "source_urls": urls,
                "watch_id": watch_id,
            },
        )
        store.complete_fact_watch(
            watch_id,
            status="verified",
            metadata={"content_sha256": digest, "source_urls": urls},
        )
        result["refreshed"] += 1
        result["items"].append({"id": watch_id, "query": query, "status": "verified", "sources": urls})
    return result


__all__ = ["refresh_due_facts"]
