"""Deterministic typed memory capture for the local second-brain layer.

The language model may propose semantic memories, but it is not trusted to
infer wellbeing state or decide that sensitive data exists.  This module only
records bounded excerpts that the user explicitly wrote, classifies them into
inspectable timeline domains, and retrieves sensitive entries only for a
matching query.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from memory_store import MemoryStore, estimate_tokens


SENSITIVE_MODE_KEY = "sensitive_memory_mode"
SENSITIVE_RETENTION_KEY = "sensitive_retention_days"

DOMAIN_TERMS: dict[str, set[str]] = {
    "wellbeing": {
        "anxiety", "anxious", "burnout", "depressed", "depression", "diagnosed",
        "emotion", "feel", "feeling", "mental", "mood", "overwhelmed", "panic",
        "sleep", "slept", "stress", "stressed", "therapy", "therapist", "wellbeing",
    },
    "work": {
        "boss", "career", "client", "colleague", "deadline", "job", "meeting",
        "office", "shift", "work", "workplace",
    },
    "project": {
        "architecture", "build", "bug", "code", "deploy", "feature", "fix",
        "intermix", "project", "release", "repository", "roadmap", "test", "version",
        "workspace",
    },
    "preference": {
        "like", "prefer", "preference", "rather", "style", "tone", "want",
    },
    "goal": {
        "aim", "goal", "intend", "plan", "target", "want",
    },
    "commitment": {
        "appointment", "commit", "deadline", "due", "meeting", "must", "schedule",
    },
}

WELLBEING_SELF_REPORT = re.compile(
    r"\b(?:"
    r"i\s+(?:feel|felt|am feeling|have been feeling)\s+(?:anxious|depressed|stressed|overwhelmed|"
    r"burned out|burnt out|low|sad|happy|calm|panicked|unsafe|suicidal|exhausted|emotionally numb)\b"
    r"|i\s+(?:was diagnosed with|have|had)\s+(?:anxiety|depression|burnout|ptsd|adhd|autism|panic attacks?)\b"
    r"|i\s+(?:take|started taking|stopped taking)\s+(?:medication|antidepressants?|anxiolytics?)\b"
    r"|i\s+(?:slept|did not sleep|didn['’]t sleep)\b"
    r"|i['’]m\s+(?:feeling\s+)?(?:anxious|depressed|stressed|overwhelmed|burned out|"
    r"burnt out|low|sad|happy|calm|panicked|unsafe|suicidal|exhausted|emotionally numb)\b"
    r"|my\s+(?:anxiety|depression|mental health|mood|sleep|stress|therapy|therapist|diagnosis|medication)\b"
    r")",
    flags=re.IGNORECASE,
)

WORK_SELF_REPORT = re.compile(
    r"\b(?:my\s+(?:job|work|boss|client|colleague|career|shift|deadline|meeting)\b"
    r"|i\s+(?:work|worked|am working|need to finish|have a deadline|have a meeting)\b"
    r"|at\s+work\b)",
    flags=re.IGNORECASE,
)

PROJECT_TRAJECTORY = re.compile(
    r"\b(?:project intermix|intermix|we (?:built|build|verified|decided|need|should|will)|"
    r"let['’]s (?:build|add|fix|implement|proceed)|roadmap|release v?\d|current (?:bug|issue|build))\b",
    flags=re.IGNORECASE,
)

PREFERENCE_REPORT = re.compile(
    r"\b(?:i (?:prefer|like|want|would rather|don['’]t want)|my preference is)\b",
    flags=re.IGNORECASE,
)

GOAL_REPORT = re.compile(
    r"\b(?:my goal is|i (?:aim|intend|plan|want) to|we need to|let['’]s)\b",
    flags=re.IGNORECASE,
)

COMMITMENT_REPORT = re.compile(
    r"\b(?:i (?:must|need to|have to|will)\b|(?:deadline|due|appointment|meeting)\s+(?:is|at|on)\b)",
    flags=re.IGNORECASE,
)


@dataclass(frozen=True)
class CapturedEvent:
    event_id: int
    domain: str
    event_type: str
    sensitive: bool
    content: str


def _sentences(text: str) -> list[str]:
    clean = re.sub(r"\s+", " ", text.replace("\x00", " ")).strip()
    if not clean:
        return []
    parts = re.split(r"(?<=[.!?])\s+|\n+", clean)
    return [part.strip()[:900] for part in parts if part.strip()][:24]


def _expiry(days: int) -> str | None:
    if days <= 0:
        return None
    return (
        datetime.now(timezone.utc) + timedelta(days=max(1, min(days, 3650)))
    ).replace(microsecond=0).isoformat()


def capture_explicit_events(
    store: MemoryStore,
    text: str,
    *,
    session_id: str,
    source_message_id: int,
) -> list[CapturedEvent]:
    """Capture only textual self-reports and explicit project/work trajectory."""
    mode = store.get_setting(SENSITIVE_MODE_KEY, "explicit_only").casefold()
    try:
        retention_days = int(store.get_setting(SENSITIVE_RETENTION_KEY, "365"))
    except ValueError:
        retention_days = 365

    captured: list[CapturedEvent] = []
    seen: set[tuple[str, str]] = set()
    for sentence in _sentences(text):
        classifications: list[tuple[str, str, bool, float]] = []
        if mode != "off" and WELLBEING_SELF_REPORT.search(sentence):
            classifications.append(("wellbeing", "explicit_self_report", True, 0.86))
        if WORK_SELF_REPORT.search(sentence):
            classifications.append(("work", "work_context", False, 0.76))
        if PROJECT_TRAJECTORY.search(sentence):
            classifications.append(("project", "trajectory", False, 0.82))
        if PREFERENCE_REPORT.search(sentence):
            classifications.append(("preference", "stated_preference", False, 0.78))
        if GOAL_REPORT.search(sentence):
            classifications.append(("goal", "stated_goal", False, 0.79))
        if COMMITMENT_REPORT.search(sentence):
            classifications.append(("commitment", "stated_commitment", False, 0.72))

        for domain, event_type, sensitive, salience in classifications:
            key = (domain, sentence.casefold())
            if key in seen:
                continue
            seen.add(key)
            event_id = store.record_memory_event(
                session_id=session_id,
                domain=domain,
                event_type=event_type,
                content=sentence,
                source_message_id=source_message_id,
                explicitly_stated=True,
                sensitive=sensitive,
                salience=salience,
                expires_at=_expiry(retention_days) if sensitive else None,
                metadata={
                    "capture": "deterministic_explicit_text",
                    "diagnostic_inference": False,
                    "verbatim_excerpt": True,
                },
            )
            captured.append(CapturedEvent(event_id, domain, event_type, sensitive, sentence))
            if len(captured) >= 12:
                return captured
    return captured


def query_domains(text: str) -> set[str]:
    words = set(re.findall(r"[a-z0-9]+", text.casefold()))
    domains = {
        domain for domain, terms in DOMAIN_TERMS.items() if words & terms
    }
    lowered = text.casefold()
    if any(term in lowered for term in ("what did we", "where were we", "previous project", "roadmap")):
        domains.add("project")
    if any(term in lowered for term in ("how have i been", "mental health history", "wellbeing timeline")):
        domains.add("wellbeing")
    return domains


def sensitive_recall_allowed(text: str) -> bool:
    domains = query_domains(text)
    return "wellbeing" in domains


def retrieve_second_brain_context(
    store: MemoryStore,
    query: str,
    *,
    token_budget: int = 520,
) -> str:
    domains = query_domains(query)
    if not domains:
        return ""
    allow_sensitive = sensitive_recall_allowed(query)
    events = store.search_memory_events(
        query,
        domains=sorted(domains),
        include_sensitive=allow_sensitive,
        limit=12,
    )
    if not events:
        events = store.search_memory_events(
            "",
            domains=sorted(domains),
            include_sensitive=allow_sensitive,
            limit=8,
        )
    lines: list[str] = []
    used = 0
    for event in events:
        label = str(event["domain"]).replace("_", " ")
        flag = ", explicit private self-report" if event.get("sensitive") else ""
        line = f"- [{event['occurred_at']}; {label}{flag}] {event['content']}"
        cost = estimate_tokens(line) + 2
        if lines and used + cost > token_budget:
            break
        if cost > token_budget:
            continue
        lines.append(line)
        used += cost
    if not lines:
        return ""
    rules = (
        "Use these controller-captured episodes as historical context, not as a diagnosis or a current-state assumption. "
        "When time matters, distinguish past report time from now."
    )
    return "[TYPED SECOND-BRAIN TIMELINE]\n" + rules + "\n" + "\n".join(lines)


def memory_audit(store: MemoryStore) -> dict[str, Any]:
    status = store.status()
    return {
        "sensitive_mode": store.get_setting(SENSITIVE_MODE_KEY, "explicit_only"),
        "sensitive_retention_days": store.get_setting(SENSITIVE_RETENTION_KEY, "365"),
        "database_encryption": "not provided by SQLite; relies on Android/Termux app-private storage",
        "inferred_diagnoses_allowed": False,
        "unrelated_sensitive_recall_allowed": False,
        "durable_memories": status.get("memories", 0),
        "typed_events": status.get("events", 0),
        "sensitive_facts": status.get("sensitive", 0),
        "sensitive_events": status.get("sensitive_events", 0),
        "domains": store.memory_domain_counts(),
    }


__all__ = [
    "CapturedEvent",
    "SENSITIVE_MODE_KEY",
    "SENSITIVE_RETENTION_KEY",
    "capture_explicit_events",
    "memory_audit",
    "query_domains",
    "retrieve_second_brain_context",
    "sensitive_recall_allowed",
]
