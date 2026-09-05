"""Bounded, deterministic query planning for adaptive web grounding.

The planner deliberately stays outside the language models.  Search wording is
derived only from the user's question and controller-owned vocabulary, which
prevents a small model from silently adding a different product, version, date,
or claim before retrieval begins.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any

from freshness_policy import FreshnessDecision, assess_freshness


MAX_PLANNED_QUERIES = 3
MAX_QUERY_CHARACTERS = 420

_FILLER = {
    "actually", "also", "answer", "basically", "can", "could", "explain",
    "find", "give", "help", "i", "information", "know", "like", "look",
    "me", "my", "please", "question", "search", "tell", "think", "want",
    "we", "would", "you",
}
_STOPWORDS = {
    "a", "about", "an", "and", "are", "as", "at", "be", "by", "do",
    "does", "for", "from", "has", "have", "how", "if", "in", "is", "it",
    "of", "on", "or", "that", "the", "this", "to", "was", "what", "when",
    "where", "which", "who", "why", "will", "with",
}
_TECHNICAL = {
    "android", "api", "backend", "browser", "cli", "code", "cuda",
    "dependency", "driver", "framework", "github", "gpu", "library",
    "litert", "linux", "model", "npu", "onnx", "opencl", "package", "pixel",
    "python", "repository", "runtime", "sdk", "software", "termux", "textual",
    "torch", "tpu", "version", "vulkan", "xnnpack",
}
_RESEARCH = {
    "citation", "doi", "evidence", "journal", "paper", "papers", "research",
    "study", "studies", "systematic",
}
_CURRENT = {
    "available", "current", "currently", "latest", "newest", "news", "now",
    "price", "recent", "release", "released", "today", "update", "updated",
    "version",
}
_DESIGN = {
    "architecture", "build", "built", "create", "created", "design",
    "designed", "implementation", "internals", "mechanism", "works",
}


@dataclass(frozen=True)
class SearchQueryPlan:
    original_query: str
    primary_query: str
    follow_up_queries: tuple[str, ...]
    risk: str
    reason: str
    expected_success: str
    expansion_focus: str

    @property
    def queries(self) -> tuple[str, ...]:
        return (self.primary_query, *self.follow_up_queries)

    def as_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["queries"] = list(self.queries)
        payload["follow_up_queries"] = list(self.follow_up_queries)
        return payload


def _clean(text: str) -> str:
    value = re.sub(r"```.*?```", " ", str(text), flags=re.DOTALL)
    value = re.sub(r"[\x00-\x1f\x7f]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def _tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9][a-z0-9_.+/-]*", text.casefold())


def _anchor_query(text: str) -> str:
    seen: set[str] = set()
    anchors: list[str] = []
    for token in _tokens(text):
        if token in _STOPWORDS or token in _FILLER or token in seen:
            continue
        seen.add(token)
        anchors.append(token)
        if len(anchors) >= 16:
            break
    return " ".join(anchors)


def _append_unique(target: list[str], value: str) -> None:
    clean = _clean(value)[:MAX_QUERY_CHARACTERS].strip()
    normalized = re.sub(r"[^a-z0-9]+", " ", clean.casefold()).strip()
    if not clean or any(
        re.sub(r"[^a-z0-9]+", " ", item.casefold()).strip() == normalized
        for item in target
    ):
        return
    target.append(clean)


def build_search_query_plan(
    text: str,
    *,
    forced: bool = False,
    decision: FreshnessDecision | None = None,
    max_queries: int = MAX_PLANNED_QUERIES,
) -> SearchQueryPlan:
    """Create one primary query and at most two anchored follow-up queries."""
    original = _clean(text)
    freshness = decision or assess_freshness(original, forced=forced)
    maximum = max(1, min(int(max_queries), MAX_PLANNED_QUERIES))
    planned: list[str] = []
    _append_unique(planned, freshness.optimized_query or original)

    anchors = _anchor_query(original)
    words = set(_tokens(original))
    year = str(datetime.now().astimezone().year)
    if not anchors:
        anchors = (freshness.optimized_query or original)[:240]

    if words & _RESEARCH:
        _append_unique(planned, f"{anchors} paper study DOI")
        _append_unique(planned, f"{anchors} review evidence limitations")
        expansion_focus = "methods, strength of evidence, and limitations"
    elif words & _DESIGN:
        _append_unique(planned, f"{anchors} official architecture implementation design")
        _append_unique(planned, f"{anchors} engineering rationale tradeoffs limitations")
        expansion_focus = "implementation, design rationale, and trade-offs"
    elif words & _TECHNICAL or freshness.risk == "volatile_tech":
        current_suffix = f" {year}" if words & _CURRENT else ""
        _append_unique(planned, f"{anchors} official documentation release notes{current_suffix}")
        _append_unique(planned, f"{anchors} GitHub issue support limitation{current_suffix}")
        expansion_focus = "implementation, compatibility, and known limitations"
    elif freshness.risk == "current_world" or words & _CURRENT:
        _append_unique(planned, f"{anchors} official announcement {year}")
        _append_unique(planned, f"{anchors} recent reporting {year}")
        expansion_focus = "causes, practical impact, and what may change next"
    else:
        _append_unique(planned, f"{anchors} primary source")
        _append_unique(planned, f"{anchors} evidence limitations")
        expansion_focus = "origin, mechanism, and limitations"

    if not planned:
        planned.append(original[:MAX_QUERY_CHARACTERS])
    planned = planned[:maximum]
    return SearchQueryPlan(
        original_query=original,
        primary_query=planned[0],
        follow_up_queries=tuple(planned[1:]),
        risk=freshness.risk,
        reason=freshness.reason,
        expected_success=freshness.expected_success,
        expansion_focus=expansion_focus,
    )


__all__ = [
    "MAX_PLANNED_QUERIES",
    "MAX_QUERY_CHARACTERS",
    "SearchQueryPlan",
    "build_search_query_plan",
]
