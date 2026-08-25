"""Deterministic temporal-risk and search-query planning.

The model is not asked to decide whether its own knowledge is stale.  This
small classifier keeps timeless and interpretive questions local while routing
evolving technical or current-world claims through evidence retrieval.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime


VOLATILE_WORDS = {
    "available", "availability", "breaking", "ceo", "current", "currently",
    "deadline", "driver", "election", "latest", "law", "market", "newest",
    "news", "now", "patch", "president", "price", "recent", "regulation",
    "release", "released", "schedule", "score", "security", "stock", "today",
    "trend", "trending", "update", "updated", "version", "weather",
}

TECH_WORDS = {
    "android", "api", "backend", "browser", "cli", "code", "coding", "cuda",
    "dependency", "driver", "framework", "github", "gpu", "library", "litert",
    "linux", "model", "onnx", "opencl", "package", "pixel", "python", "release",
    "repository", "runtime", "sdk", "software", "termux", "textual", "torch",
    "version", "xnnpack",
}

STABLE_OR_INTERPRETIVE_WORDS = {
    "algebra", "arithmetic", "calculate", "calculus", "equation", "geometry",
    "math", "mathematics", "meaning", "meditation", "metaphor", "numerology",
    "philosophy", "poem", "spiritual", "spirituality", "symbolism", "theorem",
}

CREATION_WORDS = {
    "brainstorm", "create", "draft", "generate", "imagine", "rewrite", "script",
    "story", "write",
}

LOOKUP_RE = re.compile(r"\b(?:check|find|look up|search|verify|fact[- ]?check)\b", re.I)
QUESTION_RE = re.compile(
    r"^(?:who|what|where|when|which|why|how|is|are|was|were|do|does|did|can|could|"
    r"has|have|will|would|should)\b",
    re.I,
)


@dataclass(frozen=True)
class FreshnessDecision:
    search: bool
    risk: str
    reason: str
    optimized_query: str
    expected_success: str


def _words(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9_-]+", text.casefold()))


def optimize_search_query(text: str, *, risk: str) -> str:
    clean = re.sub(r"```.*?```", " ", text, flags=re.DOTALL)
    clean = re.sub(r"\s+", " ", clean).strip()
    clean = re.sub(
        r"^(?:please\s+)?(?:can|could|would)\s+you\s+(?:please\s+)?",
        "",
        clean,
        flags=re.IGNORECASE,
    )
    if len(clean) > 260:
        question_parts = re.findall(r"[^.!?]{10,}[?]", clean)
        clean = (question_parts[-1] if question_parts else clean[:260]).strip()
    words = _words(clean)
    year = str(datetime.now().astimezone().year)
    if (
        risk == "current_world"
        or (risk == "volatile_tech" and bool(words & VOLATILE_WORDS))
    ) and not any(word.isdigit() and len(word) == 4 for word in words):
        clean += " " + year
    if risk == "volatile_tech" and not words & {"news", "price", "score", "weather", "trend"}:
        clean += " official documentation release notes"
    return clean[:420]


def assess_freshness(text: str, *, forced: bool = False) -> FreshnessDecision:
    clean = re.sub(r"\s+", " ", text).strip()
    words = _words(clean)
    if forced:
        risk = "volatile_tech" if words & TECH_WORDS else "current_world"
        return FreshnessDecision(True, risk, "explicit /web override", optimize_search_query(clean, risk=risk), "high")

    word_count = len(re.findall(r"[a-z0-9_-]+", clean.casefold()))
    question_like = bool(QUESTION_RE.search(clean) or "?" in clean[-320:])
    lookup_like = bool(LOOKUP_RE.search(clean[:240]))
    stable = bool(words & STABLE_OR_INTERPRETIVE_WORDS)
    tech = bool(words & TECH_WORDS)
    volatile = bool(words & VOLATILE_WORDS)
    creation = bool(words & CREATION_WORDS)

    if stable and not lookup_like and not (volatile and words & {"news", "current", "latest"}):
        return FreshnessDecision(False, "stable", "timeless or interpretive topic", clean[:420], "local")
    if word_count > 100 and not lookup_like:
        return FreshnessDecision(False, "local_task", "long design or supplied-content task", clean[:420], "local")
    if creation and not lookup_like and not volatile:
        return FreshnessDecision(False, "creative", "creation does not require external freshness", clean[:420], "local")
    if tech and (question_like or lookup_like or volatile):
        query = optimize_search_query(clean, risk="volatile_tech")
        success = "high" if len(words - TECH_WORDS) >= 1 else "medium"
        return FreshnessDecision(True, "volatile_tech", "technical behavior may have changed", query, success)
    if volatile and (question_like or lookup_like or word_count <= 28):
        query = optimize_search_query(clean, risk="current_world")
        success = "high" if len(words - VOLATILE_WORDS) >= 1 else "low"
        return FreshnessDecision(True, "current_world", "claim is time-sensitive", query, success)
    return FreshnessDecision(False, "stable", "no material temporal risk detected", clean[:420], "local")


__all__ = ["FreshnessDecision", "assess_freshness", "optimize_search_query"]
