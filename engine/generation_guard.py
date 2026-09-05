"""Deterministic safety controls for streamed local generation.

The guard only reacts to observable transport/generation failures. It does not
score opinions, tone, or subject matter. Partial output from a stopped turn is
displayable by the UI but must never be committed as assistant memory.
"""

from __future__ import annotations

import re
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any


WORD_TOKEN = re.compile(r"[\w'-]+", flags=re.UNICODE)
CONTROL_TOKEN = re.compile(
    r"(?:<\|(?:im_start|im_end|endoftext|assistant|user|system)\|>"
    r"|<\/?(?:start_of_turn|end_of_turn|bos|eos)>)",
    flags=re.IGNORECASE,
)
LEGACY_MEMORY_MARKER = re.compile(
    r"\[\s*(?:INTERNAL\s+)?MEMORY\s+DELTA\b",
    flags=re.IGNORECASE,
)
INVALID_STREAM_CHARACTER = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\ufffd]")


@dataclass(frozen=True)
class GuardDecision:
    triggered: bool
    reason: str = ""
    detail: str = ""
    repeated_text: str = ""
    repetitions: int = 0


class RepetitionWatchdog:
    """Detect conservative, mechanically repeated suffixes in visible output."""

    def __init__(self, *, max_tail_tokens: int = 256, max_tail_chars: int = 16_384):
        self.max_tail_tokens = max(64, max_tail_tokens)
        self.max_tail_chars = max(2048, max_tail_chars)
        self._text_tail = ""
        self._tokens: list[str] = []
        self._decision = GuardDecision(False)

    @property
    def decision(self) -> GuardDecision:
        return self._decision

    def feed(self, text: str) -> GuardDecision:
        if self._decision.triggered or not text:
            return self._decision

        self._text_tail = (self._text_tail + text)[-self.max_tail_chars :]
        if INVALID_STREAM_CHARACTER.search(text):
            self._decision = GuardDecision(
                True,
                reason="stream_artifact",
                detail="Invalid control or replacement characters appeared in the stream.",
            )
            return self._decision
        if LEGACY_MEMORY_MARKER.search(self._text_tail):
            self._decision = GuardDecision(
                True,
                reason="memory_protocol_leak",
                detail="A legacy memory-control marker leaked into the visible response.",
            )
            return self._decision
        if CONTROL_TOKEN.search(self._text_tail):
            self._decision = GuardDecision(
                True,
                reason="control_token_leak",
                detail="A model control token leaked into the visible response.",
            )
            return self._decision

        # Re-tokenize the bounded tail so words split across LiteRT chunks are
        # reconstructed before repetition analysis.
        self._tokens = [
            token.casefold() for token in WORD_TOKEN.findall(self._text_tail)
        ][-self.max_tail_tokens :]

        # A single ordinary word must repeat many times before it is treated as
        # corruption. Longer repeated phrases need fewer cycles but more total
        # evidence. These thresholds catch the observed "the" loop while
        # preserving lists, poetry, code, and deliberate rhetorical repetition.
        thresholds = (
            (1, 16),
            (2, 9),
            (3, 7),
            (4, 6),
            (6, 5),
            (8, 4),
            (12, 4),
            *((phrase_size, 3) for phrase_size in range(13, 65)),
        )
        for phrase_size, repetitions in thresholds:
            required = phrase_size * repetitions
            if len(self._tokens) < max(24, required):
                continue
            suffix = self._tokens[-phrase_size:]
            if all(
                self._tokens[-(cycle + 1) * phrase_size : -cycle * phrase_size or None]
                == suffix
                for cycle in range(repetitions)
            ):
                repeated = " ".join(suffix)
                self._decision = GuardDecision(
                    True,
                    reason="repetition_loop",
                    detail=(
                        "Generation entered a repeated word or phrase loop "
                        f"({repetitions} consecutive cycles)."
                    ),
                    repeated_text=repeated[:160],
                    repetitions=repetitions,
                )
                return self._decision
        return self._decision


class GenerationRuntime:
    """Thread-safe lifecycle and cancellation state for one foreground turn."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cancel_event = threading.Event()
        self._active = False
        self._request_id = ""
        self._started_at = 0.0
        self._reason = ""
        self._detail = ""
        self._last_outcome = "idle"

    def begin(self) -> str:
        with self._lock:
            self._cancel_event.clear()
            self._active = True
            self._request_id = uuid.uuid4().hex[:12]
            self._started_at = time.monotonic()
            self._reason = ""
            self._detail = ""
            self._last_outcome = "running"
            return self._request_id

    def cancel(self, reason: str = "user", detail: str = "") -> bool:
        with self._lock:
            if not self._active:
                return False
            self._reason = reason or "user"
            self._detail = detail
            self._last_outcome = "stopping"
            self._cancel_event.set()
            return True

    def finish(self, outcome: str = "completed") -> None:
        with self._lock:
            if self._cancel_event.is_set() and outcome == "completed":
                outcome = "stopped"
            self._active = False
            self._last_outcome = outcome

    @property
    def cancelled(self) -> bool:
        return self._cancel_event.is_set()

    @property
    def cancel_event(self) -> threading.Event:
        return self._cancel_event

    def stop_payload(self) -> dict[str, Any]:
        with self._lock:
            return {
                "request_id": self._request_id,
                "reason": self._reason or "cancelled",
                "detail": self._detail or "Generation stopped before completion.",
                "incomplete": True,
            }

    def status(self) -> dict[str, Any]:
        with self._lock:
            elapsed = time.monotonic() - self._started_at if self._active else 0.0
            return {
                "active": self._active,
                "request_id": self._request_id,
                "state": self._last_outcome,
                "reason": self._reason,
                "detail": self._detail,
                "elapsed_seconds": round(elapsed, 3),
            }


GENERATION_RUNTIME = GenerationRuntime()


__all__ = [
    "GENERATION_RUNTIME",
    "GenerationRuntime",
    "GuardDecision",
    "RepetitionWatchdog",
]
