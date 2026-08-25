"""Deterministic response-mode selection for the physical 8K context."""

from __future__ import annotations

import re
from dataclasses import dataclass


DEEP_PATTERNS = (
    r"\bdeep(?:er)?\s+dive\b",
    r"\bin[- ]depth\b",
    r"\bcomprehensive\b",
    r"\bdetailed?\b",
    r"\bexplain (?:it )?(?:fully|thoroughly|step by step)\b",
    r"\bfull (?:explanation|guide|tutorial|analysis|breakdown)\b",
    r"\blong[- ]form\b",
    r"\bwrite (?:an?|the) (?:guide|tutorial|document|essay|report)\b",
)


@dataclass(frozen=True)
class ResponsePolicy:
    mode: str
    input_limit: int
    output_reserve: int
    sentence_target: int | None
    instruction: str


POLICIES = {
    "lookup": ResponsePolicy(
        mode="lookup",
        input_limit=2200,
        output_reserve=5800,
        sentence_target=4,
        instruction=(
            "[RESPONSE MODE: VERIFIED LOOKUP]\nAnswer the exact current question in no more "
            "than four sentences. Preserve the controller-verified value exactly, cite its supplied "
            "source ID, and add only context directly supported by the evidence. Never substitute a "
            "different number, version, date, channel, or source from model memory."
        ),
    ),
    "brief": ResponsePolicy(
        mode="brief",
        input_limit=7200,
        output_reserve=800,
        sentence_target=5,
        instruction=(
            "[RESPONSE MODE: BRIEF]\nKeep the visible response at or below five sentences. "
            "Lead with the useful answer. Include at least one purposeful emoji as a visual anchor, "
            "but never insert emojis into code, commands, XML, JSON, URLs, quotations, or citations."
        ),
    ),
    "normal": ResponsePolicy(
        mode="normal",
        input_limit=6800,
        output_reserve=1200,
        sentence_target=10,
        instruction=(
            "[RESPONSE MODE: NORMAL]\nKeep ordinary conversation at or below ten sentences. "
            "Expand only when the request genuinely requires it. Include at least one purposeful emoji "
            "as a visual anchor, but never insert emojis into code, commands, XML, JSON, URLs, "
            "quotations, or citations."
        ),
    ),
    "deep": ResponsePolicy(
        mode="deep",
        input_limit=5800,
        output_reserve=2200,
        sentence_target=None,
        instruction=(
            "[RESPONSE MODE: DEEP]\nThe user requested a substantial explanation. Use the available "
            "answer budget, organize the response into compact sections, and do not apply the normal "
            "ten-sentence ceiling. Use purposeful emoji anchors for navigation without decorating code, "
            "commands, XML, JSON, URLs, quotations, or citations."
        ),
    ),
    "create": ResponsePolicy(
        mode="create",
        input_limit=4000,
        output_reserve=4000,
        sentence_target=None,
        instruction=(
            "[RESPONSE MODE: CREATION STUDIO]\nUse the full safe long-form answer budget for a polished "
            "video script, lore chapter, narrative, campaign treatment, or other requested creative artifact. "
            "Preserve supplied canon, names, numbers, tone, audience, and format. Favor strong structure, "
            "scene rhythm, sensory specificity, and a complete ending over commentary about the writing process. "
            "Do not apply the normal sentence ceiling. Do not claim invented lore is a verified real-world fact."
        ),
    ),
    "workspace": ResponsePolicy(
        mode="workspace",
        input_limit=5200,
        output_reserve=2800,
        sentence_target=None,
        instruction=(
            "[RESPONSE MODE: WORKSPACE]\nPrioritize correct tool actions, complete file contents, and "
            "verification over conversational prose. Do not add emojis inside tool tags or generated files. "
            "Keep any visible progress note concise."
        ),
    ),
}


def requested_mode(prompt: str) -> str:
    matches = re.findall(
        r"(?:^|\s)/(brief|normal|deep|create)\b",
        prompt[:160],
        flags=re.IGNORECASE,
    )
    return matches[-1].casefold() if matches else ""


def select_response_policy(
    prompt: str,
    *,
    agent_mode: bool = False,
    stored_mode: str = "auto",
    fast_lookup: bool = False,
) -> ResponsePolicy:
    if agent_mode:
        return POLICIES["workspace"]
    explicit = requested_mode(prompt)
    if explicit:
        return POLICIES[explicit]
    normalized_mode = stored_mode.casefold().strip()
    if normalized_mode in {"brief", "normal", "deep", "create"}:
        return POLICIES[normalized_mode]
    if fast_lookup:
        return POLICIES["lookup"]
    clean = re.sub(r"\s+", " ", prompt.casefold()).strip()
    if any(re.search(pattern, clean) for pattern in DEEP_PATTERNS):
        return POLICIES["deep"]
    return POLICIES["normal"]


__all__ = ["POLICIES", "ResponsePolicy", "requested_mode", "select_response_policy"]
