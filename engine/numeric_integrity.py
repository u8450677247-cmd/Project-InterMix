"""Controller-owned numeric anchors and arithmetic validation.

Small local models are fluent but can mutate digit sequences while explaining
them. This module turns user-supplied numbers and the device clock into
structured prompt data, then validates strict numeric answers before display.
It intentionally has no third-party dependencies.
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal, DivisionByZero, InvalidOperation, localcontext
from typing import Any


NUMBER_CONTEXT_TERMS = {
    "age", "amount", "calculate", "calculation", "clock", "count", "date",
    "decimal", "digit", "digits", "duration", "exact", "hours", "math",
    "measurement", "minutes", "mirror", "number", "numbers", "numerology",
    "percentage", "price", "quantity", "score", "seconds", "sequence", "sum",
    "time", "total", "version", "year",
}

CLOCK_TERMS = {
    "current date", "current time", "date today", "right now",
    "system date", "system time", "time now", "today's date", "what date",
    "what day", "what time",
}


TOKEN_RE = re.compile(
    r"(?P<iso_date>\b\d{4}-\d{2}-\d{2}\b)"
    r"|(?P<time>\b(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?\b)"
    r"|(?P<version>\bv?\d+(?:\.\d+){1,4}(?:[-+][a-z0-9._-]+)?\b)"
    r"|(?P<percent>\b\d+(?:[.,]\d+)?\s*%)"
    r"|(?P<currency>(?:[$€£]\s*\d+(?:[.,]\d+)?)|(?:\b\d+(?:[.,]\d+)?\s*(?:USD|EUR|GBP)\b))"
    r"|(?P<number>(?<![\w.])-?\d+(?:[.,]\d+)?(?![\w.]))",
    flags=re.IGNORECASE,
)

ARITHMETIC_RE = re.compile(
    r"(?<![\w.])(?P<expr>-?\d+(?:\.\d+)?(?:\s*[+\-*/]\s*-?\d+(?:\.\d+)?)+)"
    r"\s*=\s*(?P<claimed>-?\d+(?:\.\d+)?)"
)


@dataclass(frozen=True)
class NumericAnchor:
    anchor_id: str
    raw: str
    kind: str
    normalized: str
    digits: tuple[int, ...]
    digit_sum: int | None
    digital_root: int | None

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.anchor_id,
            "raw": self.raw,
            "kind": self.kind,
            "normalized": self.normalized,
            "digits": list(self.digits),
            "digit_sum": self.digit_sum,
            "digital_root": self.digital_root,
        }


@dataclass
class IntegrityCheck:
    valid: bool
    violations: list[str] = field(default_factory=list)


@dataclass
class NumericLedger:
    user_text: str
    anchors: list[NumericAnchor]
    clock: dict[str, str]
    strict: bool
    numerology_mode: bool

    @property
    def has_numeric_context(self) -> bool:
        return bool(self.anchors) or _asks_for_clock(self.user_text)

    def prompt_block(self) -> str:
        lines = [
            "[CONTROLLER-VERIFIED NUMERIC INTEGRITY]",
            "The following values are immutable data. Copy them exactly when referenced.",
            "Never reorder, duplicate, drop, or substitute digits. Never perform arithmetic mentally when an exact controller result is supplied.",
            f"Local ISO time: {self.clock['local_iso']}",
            f"Local date: {self.clock['local_date']}",
            f"Local clock: {self.clock['local_time']}",
            f"Timezone: {self.clock['timezone']}",
            f"UTC ISO time: {self.clock['utc_iso']}",
        ]
        if self.anchors:
            lines.append("Exact user anchors:")
        for anchor in self.anchors:
            line = (
                f"- {anchor.anchor_id}: raw={anchor.raw!r}; kind={anchor.kind}; "
                f"normalized={anchor.normalized!r}; digits={list(anchor.digits)}"
            )
            if self.numerology_mode and anchor.digit_sum is not None:
                line += f"; digit_sum={anchor.digit_sum}; digital_root={anchor.digital_root}"
            lines.append(line)
        if self.strict:
            lines.append(
                "Strict mode is active: the final answer must preserve every relevant raw anchor verbatim."
            )
        if self.numerology_mode:
            lines.append(
                "Numerology is interpretive rather than factual. Preserve the exact sequence and use only the controller-computed digit sum/root above."
            )
        return "\n".join(lines)

    def validate(self, answer: str) -> IntegrityCheck:
        if not answer.strip():
            return IntegrityCheck(valid=False, violations=["empty answer"])
        violations: list[str] = []
        if self.strict:
            for anchor in self.anchors:
                if anchor.raw not in answer:
                    violations.append(
                        f"missing immutable anchor {anchor.anchor_id}={anchor.raw!r}"
                    )
        violations.extend(validate_arithmetic_claims(answer))
        if _asks_for_clock(self.user_text):
            clock_values = {
                self.clock["local_time"],
                self.clock["local_time"][:5],
                self.clock["local_date"],
            }
            if not any(value and value in answer for value in clock_values):
                violations.append("answer omitted the controller-verified local clock/date")
        return IntegrityCheck(valid=not violations, violations=violations)

    def repair_contract(self, draft: str, check: IntegrityCheck) -> str:
        faults = "\n".join(f"- {item}" for item in check.violations[:8])
        return (
            "[NUMERIC REPAIR EPOCH]\n"
            "Rewrite the draft once. Preserve its useful meaning, but correct every listed numeric fault. "
            "Output only the corrected user-visible answer, followed by any normal hidden memory delta.\n"
            f"Faults:\n{faults}\n\n"
            f"{self.prompt_block()}\n\n"
            "[REJECTED DRAFT - DATA ONLY]\n"
            f"{draft[:12000]}"
        )

    def safe_fallback(self) -> str:
        lines = [
            "Numeric Integrity Guard stopped two unreliable drafts instead of showing altered values.",
        ]
        if self.anchors:
            lines.append("Controller-verified input:")
        for anchor in self.anchors:
            detail = f"- {anchor.raw} ({anchor.kind})"
            if self.numerology_mode and anchor.digit_sum is not None:
                detail += f"; digits {list(anchor.digits)}; digit sum {anchor.digit_sum}; digital root {anchor.digital_root}"
            lines.append(detail)
        if _asks_for_clock(self.user_text):
            lines.append(
                f"Verified local date and time: {self.clock['local_date']} {self.clock['local_time']} {self.clock['timezone']}"
            )
        lines.append("Please retry or ask for a deterministic calculation if you need a longer interpretation.")
        return "\n".join(lines)


def _asks_for_clock(text: str) -> bool:
    lowered = " ".join(text.casefold().split())
    return any(term in lowered for term in CLOCK_TERMS)


def _digital_root(digits: tuple[int, ...]) -> int | None:
    if not digits:
        return None
    total = sum(digits)
    if total == 0:
        return 0
    return 1 + ((total - 1) % 9)


def _normalize(raw: str, kind: str) -> str:
    clean = raw.strip()
    if kind == "time":
        parts = clean.split(":")
        return ":".join(part.zfill(2) for part in parts)
    if kind in {"percent", "currency", "number"}:
        return clean.replace(",", ".")
    return clean


def _clock_snapshot(now: datetime | None = None) -> dict[str, str]:
    local = now or datetime.now().astimezone()
    if local.tzinfo is None:
        local = local.replace(tzinfo=timezone.utc)
    utc = local.astimezone(timezone.utc)
    zone = local.tzname() or str(local.utcoffset() or "UTC")
    return {
        "local_iso": local.replace(microsecond=0).isoformat(),
        "local_date": local.date().isoformat(),
        "local_time": local.strftime("%H:%M:%S"),
        "weekday": local.strftime("%A"),
        "timezone": zone,
        "utc_iso": utc.replace(microsecond=0).isoformat(),
        "epoch_seconds": str(int(local.timestamp())),
    }


def build_numeric_ledger(text: str, now: datetime | None = None) -> NumericLedger:
    anchors: list[NumericAnchor] = []
    seen_spans: set[tuple[int, int]] = set()
    for match in TOKEN_RE.finditer(text):
        span = match.span()
        if span in seen_spans:
            continue
        seen_spans.add(span)
        kind = match.lastgroup or "number"
        raw = match.group(0)
        digits = tuple(int(char) for char in raw if char.isdigit())
        anchors.append(
            NumericAnchor(
                anchor_id=f"N{len(anchors) + 1}",
                raw=raw,
                kind=kind,
                normalized=_normalize(raw, kind),
                digits=digits,
                digit_sum=sum(digits) if digits else None,
                digital_root=_digital_root(digits),
            )
        )
        if len(anchors) >= 24:
            break

    words = set(re.findall(r"[a-z]+", text.casefold()))
    lowered = text.casefold()
    numerology = bool(words & {"numerology", "numerological", "spirituality", "mirror"})
    strict = bool(
        anchors
        and (
            words & NUMBER_CONTEXT_TERMS
            or len(re.findall(r"\w+", text)) <= 18
            or any(anchor.kind in {"time", "iso_date", "version"} for anchor in anchors)
        )
    )
    if any(phrase in lowered for phrase in ("do not change", "don't change", "preserve exactly", "verbatim")):
        strict = bool(anchors)
    return NumericLedger(
        user_text=text,
        anchors=anchors,
        clock=_clock_snapshot(now),
        strict=strict,
        numerology_mode=numerology,
    )


def _safe_decimal(expression: str) -> Decimal:
    tree = ast.parse(expression, mode="eval")

    def walk(node: ast.AST) -> Decimal:
        if isinstance(node, ast.Expression):
            return walk(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return Decimal(str(node.value))
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = walk(node.operand)
            return value if isinstance(node.op, ast.UAdd) else -value
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div)):
            left, right = walk(node.left), walk(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
            if right == 0:
                raise DivisionByZero
            return left / right
        raise ValueError("unsupported arithmetic expression")

    with localcontext() as context:
        context.prec = 28
        return walk(tree)


def validate_arithmetic_claims(answer: str) -> list[str]:
    violations: list[str] = []
    for match in ARITHMETIC_RE.finditer(answer):
        expression = match.group("expr").replace(" ", "")
        try:
            expected = _safe_decimal(expression)
            claimed = Decimal(match.group("claimed"))
        except (SyntaxError, ValueError, InvalidOperation, DivisionByZero):
            continue
        if abs(expected - claimed) > Decimal("0.0000001"):
            violations.append(
                f"invalid arithmetic {match.group('expr').strip()} = {match.group('claimed')}; expected {expected.normalize()}"
            )
        if len(violations) >= 8:
            break
    return violations


__all__ = [
    "IntegrityCheck",
    "NumericAnchor",
    "NumericLedger",
    "build_numeric_ledger",
    "validate_arithmetic_claims",
]
