"""Selective long-term recall for Project Intermix."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from memory_store import MemoryStore, estimate_tokens
from second_brain import sensitive_recall_allowed


@dataclass
class RecallResult:
    text: str
    memory_ids: list[int]
    message_ids: list[int]
    estimated_tokens: int


def _fit_lines(lines: list[str], token_budget: int) -> list[str]:
    selected: list[str] = []
    used = 0
    for line in lines:
        cost = estimate_tokens(line) + 2
        if selected and used + cost > token_budget:
            break
        if cost > token_budget:
            continue
        selected.append(line)
        used += cost
    return selected


def retrieve_context(
    store: MemoryStore,
    query: str,
    *,
    session_id: str,
    token_budget: int = 800,
    memory_limit: int = 8,
    archive_limit: int = 4,
) -> RecallResult:
    memories = store.search_memories(
        query,
        limit=memory_limit,
        include_sensitive=sensitive_recall_allowed(query),
    )
    archived = store.search_messages(query, limit=archive_limit + 8)

    current_recent_ids = {
        int(item["id"])
        for item in store.get_messages(session_id, limit=16, ascending=False)
    }
    archived = [item for item in archived if int(item["id"]) not in current_recent_ids][:archive_limit]

    memory_lines: list[str] = []
    memory_ids: list[int] = []
    for item in memories:
        label = item["kind"].replace("_", " ")
        flags = []
        if item.get("pinned"):
            flags.append("pinned")
        if item.get("sensitive"):
            flags.append("sensitive")
        flag_text = f" ({', '.join(flags)})" if flags else ""
        memory_lines.append(f"- [{label}{flag_text}] {item['value']}")
        memory_ids.append(int(item["id"]))

    archive_lines: list[str] = []
    message_ids: list[int] = []
    for item in archived:
        content = " ".join(str(item["content"]).split())
        if len(content) > 360:
            content = content[:357].rstrip() + "..."
        archive_lines.append(f"- [{item['created_at']}] {item['speaker']}: {content}")
        message_ids.append(int(item["id"]))

    memory_budget = max(0, int(token_budget * 0.68))
    archive_budget = max(0, token_budget - memory_budget)
    memory_lines = _fit_lines(memory_lines, memory_budget)
    archive_lines = _fit_lines(archive_lines, archive_budget)

    blocks: list[str] = []
    if memory_lines:
        blocks.append("[RELEVANT LONG-TERM MEMORY]\n" + "\n".join(memory_lines))
    if archive_lines:
        blocks.append("[RECALLED CONVERSATION]\n" + "\n".join(archive_lines))
    text = "\n\n".join(blocks)
    return RecallResult(
        text=text,
        memory_ids=memory_ids[: len(memory_lines)],
        message_ids=message_ids[: len(archive_lines)],
        estimated_tokens=estimate_tokens(text),
    )


__all__ = ["RecallResult", "retrieve_context"]
