"""Controller-owned contracts for exact, time-sensitive factual claims.

Search relevance is not proof.  This module distinguishes questions such as
"latest stable version" from compatibility mentions, prereleases, release
dates, and broader topical searches.  Exact facts can therefore be rendered
and validated without trusting the language model to preserve their numbers.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any


VERSION_RE = re.compile(
    r"(?<![\w.])v?(\d+\.\d+(?:\.\d+)?(?:a\d+|b\d+|rc\d+)?)(?![\w.])",
    re.IGNORECASE,
)
DATE_RE = re.compile(
    r"\b(?:"
    r"\d{4}-\d{2}-\d{2}|"
    r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|"
    r"Dec(?:ember)?)\.?\s+\d{1,2},?\s+\d{4}"
    r")\b",
    re.IGNORECASE,
)
CURRENCY_RE = re.compile(
    r"(?:[$€£]\s?\d|\b\d[\d.,]*\s?(?:USD|EUR|GBP)\b)",
    re.IGNORECASE,
)

LATEST_TERMS = {"latest", "newest", "current"}
PRERELEASE_TERMS = {
    "alpha", "beta", "candidate", "nightly", "pre-release", "prerelease",
    "preview", "rc",
}
COMPATIBILITY_TERMS = {
    "compatible", "compatibility", "requires", "requirement", "support",
    "supported", "supports",
}


@dataclass(frozen=True)
class ClaimContract:
    kind: str
    subject: str = ""
    channel: str = ""
    exact: bool = False
    reason: str = ""

    @property
    def cache_key(self) -> str:
        if not self.exact or not self.subject:
            return ""
        normalized = re.sub(r"[^a-z0-9]+", "-", self.subject.casefold()).strip("-")
        return f"official-fact:{normalized}:{self.kind}"


@dataclass(frozen=True)
class VerifiedFact:
    claim_type: str
    subject: str
    value: str
    channel: str
    source_id: int
    source_url: str
    provider: str
    evidence: str
    retrieved_at: str
    release_date: str = ""
    authority: str = "official"

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9_-]+", text.casefold()))


def _version_subject(text: str) -> str:
    if re.search(r"\bpython\b", text, re.IGNORECASE):
        return "Python"
    patterns = (
        r"\b(?:latest|newest|current)(?:\s+stable)?\s+([a-z][a-z0-9_.+-]*)\s+(?:version|release)\b",
        r"\b(?:latest|newest|current)(?:\s+stable)?\s+(?:version|release)\s+(?:of|for)\s+([a-z][a-z0-9_.+-]*)\b",
        r"\b([a-z][a-z0-9_.+-]*)\s+(?:latest|newest|current)(?:\s+stable)?\s+(?:version|release)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            candidate = match.group(1).strip(" ._-+")
            if candidate.casefold() not in {"the", "a", "my", "this", "that"}:
                return candidate
    return ""


def classify_claim(query: str) -> ClaimContract:
    """Classify only claims strict enough to justify controller verification."""
    clean = re.sub(r"\s+", " ", query).strip()
    words = _tokens(clean)
    subject = _version_subject(clean)
    latest = bool(words & LATEST_TERMS)
    version_like = bool(words & {"version", "release"})
    prerelease = bool(words & PRERELEASE_TERMS) or bool(
        re.search(r"\b(?:pre[- ]?release|release candidate|rc\d*)\b", clean, re.IGNORECASE)
    )

    if latest and version_like and subject:
        channel = "prerelease" if prerelease else "stable"
        return ClaimContract(
            kind=f"latest_{channel}_version",
            subject=subject,
            channel=channel,
            exact=True,
            reason=f"exact latest {channel} release requested",
        )

    release_date = bool(
        re.search(
            r"\b(?:when|what date|release date|released on)\b.*\b(?:release|released|version)?\b",
            clean,
            re.IGNORECASE,
        )
    )
    if release_date and (subject or re.search(r"\bpython\b", clean, re.IGNORECASE)):
        return ClaimContract(
            kind="release_date",
            subject=subject or "Python",
            exact=True,
            reason="exact release date requested",
        )

    if words & {"price", "cost"} and words & {"current", "latest", "today", "now"}:
        return ClaimContract(
            kind="current_price",
            subject=subject,
            exact=bool(subject),
            reason="current price requested",
        )

    return ClaimContract(kind="general", reason="no exact claim contract")


def extract_versions(text: str) -> list[str]:
    values: list[str] = []
    for match in VERSION_RE.finditer(text):
        value = match.group(1)
        if value not in values:
            values.append(value)
    return values


def is_prerelease(version: str) -> bool:
    return bool(re.search(r"(?:a|b|rc)\d+$", version, re.IGNORECASE))


def version_sort_key(version: str) -> tuple[int, int, int, int, int]:
    match = re.fullmatch(
        r"(\d+)\.(\d+)(?:\.(\d+))?(?:(a|b|rc)(\d+))?",
        version,
        re.IGNORECASE,
    )
    if not match:
        return (0, 0, 0, -1, 0)
    major, minor, patch, phase, phase_number = match.groups()
    phase_rank = {"a": 0, "b": 1, "rc": 2, None: 3}[phase.casefold() if phase else None]
    return (
        int(major),
        int(minor),
        int(patch or 0),
        phase_rank,
        int(phase_number or 0),
    )


def item_entails_claim(contract: ClaimContract, item: dict[str, str]) -> bool:
    """Reject topical overlap unless the evidence actually states the claim."""
    if not contract.exact:
        return True

    title = str(item.get("title") or "")
    snippet = str(item.get("snippet") or "")
    haystack = re.sub(r"\s+", " ", f"{title} {snippet}").strip()
    lowered = haystack.casefold()
    subject_tokens = _tokens(contract.subject)
    if subject_tokens and not subject_tokens <= _tokens(haystack):
        return False

    declared_type = str(item.get("claim_type") or "")
    declared_value = str(item.get("verified_value") or "").strip()
    declared_channel = str(item.get("channel") or "").casefold()
    if declared_type == contract.kind and declared_value:
        if contract.channel and declared_channel and declared_channel != contract.channel:
            return False
        if contract.channel == "stable" and is_prerelease(declared_value):
            return False
        if contract.channel == "prerelease" and not is_prerelease(declared_value):
            return False
        return True

    if contract.kind.startswith("latest_"):
        versions = extract_versions(haystack)
        if not versions:
            return False
        if contract.channel == "stable" and not any(not is_prerelease(value) for value in versions):
            return False
        if contract.channel == "prerelease" and not any(is_prerelease(value) for value in versions):
            return False

        latest_assertion = bool(
            re.search(
                r"\b(?:latest|newest|current)\b.{0,80}\b(?:version|release|python)\b|"
                r"\b(?:version|release|python)\b.{0,80}\b(?:latest|newest|current)\b|"
                r"\bdownload\s+python\s+\d+\.\d+",
                lowered,
            )
        )
        compatibility_only = bool(_tokens(haystack) & COMPATIBILITY_TERMS) and not latest_assertion
        return latest_assertion and not compatibility_only

    if contract.kind == "release_date":
        return bool(DATE_RE.search(haystack) and _tokens(haystack) & {"release", "released"})
    if contract.kind == "current_price":
        return bool(CURRENCY_RE.search(haystack) and _tokens(haystack) & {"price", "cost"})
    return False


def verified_fact_from_item(
    contract: ClaimContract,
    item: dict[str, str],
    *,
    source_id: int,
) -> VerifiedFact | None:
    if not item_entails_claim(contract, item):
        return None
    value = str(item.get("verified_value") or "").strip()
    if not value and contract.kind.startswith("latest_"):
        versions = extract_versions(str(item.get("title", "")) + " " + str(item.get("snippet", "")))
        matching = [
            candidate for candidate in versions
            if is_prerelease(candidate) == (contract.channel == "prerelease")
        ]
        value = max(matching, key=version_sort_key) if matching else ""
    if not value:
        return None
    return VerifiedFact(
        claim_type=contract.kind,
        subject=contract.subject,
        value=value,
        channel=str(item.get("channel") or contract.channel),
        source_id=source_id,
        source_url=str(item.get("url") or ""),
        provider=str(item.get("provider") or ""),
        evidence=str(item.get("snippet") or ""),
        retrieved_at=str(item.get("retrieved_at") or datetime.now(timezone.utc).replace(microsecond=0).isoformat()),
        release_date=str(item.get("release_date") or ""),
        authority=str(item.get("authority") or "official"),
    )


__all__ = [
    "ClaimContract",
    "VerifiedFact",
    "classify_claim",
    "extract_versions",
    "is_prerelease",
    "item_entails_claim",
    "verified_fact_from_item",
    "version_sort_key",
]
