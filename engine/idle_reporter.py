"""Cancellable, inference-free Markdown and PDF status reports."""

from __future__ import annotations

import hashlib
import json
import os
import re
import textwrap
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from memory_store import MemoryStore
from workspace_state import WORKSPACE_DIR, atomic_write_text


REPORT_DIR = WORKSPACE_DIR / "reports"


def _report_state(store: MemoryStore, include_sensitive: bool) -> dict[str, Any]:
    session = store.get_active_session(create=False) or {}
    timeline = store.list_memory_events(
        include_sensitive=include_sensitive,
        limit=80,
    )
    memories = [
        item for item in store.list_memories(60)
        if include_sensitive or not item.get("sensitive")
    ]
    return {
        "status": store.status(),
        "session": {
            key: session.get(key)
            for key in (
                "id", "title", "summary", "current_task", "open_loops",
                "decisions", "active_project", "updated_at",
            )
        },
        "timeline": [
            {
                key: item.get(key)
                for key in ("id", "domain", "event_type", "content", "occurred_at", "sensitive")
            }
            for item in timeline
        ],
        "memories": [
            {
                key: item.get(key)
                for key in ("id", "kind", "memory_key", "value", "updated_at", "sensitive", "pinned")
            }
            for item in memories
        ],
        "project_events": store.recent_project_events(40),
        "watchlist": store.list_fact_watches(40),
        "include_sensitive": include_sensitive,
    }


def _state_hash(state: dict[str, Any]) -> str:
    encoded = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8", "replace")).hexdigest()


def _render_markdown(state: dict[str, Any], generated_at: str) -> str:
    status = state["status"]
    session = state["session"]
    lines = [
        "# Project Intermix Cognitive Snapshot",
        "",
        f"Generated: `{generated_at}`",
        "",
        "> Controller-rendered while idle. No Gemma inference was used to create this report.",
        "",
        "## System Matrix",
        "",
        f"- Active session: **{session.get('title') or 'None'}**",
        f"- Messages: **{status.get('messages', 0)}**",
        f"- Durable semantic memories: **{status.get('memories', 0)}**",
        f"- Typed timeline events: **{status.get('events', 0)}**",
        f"- Private facts/events: **{status.get('sensitive', 0)} / {status.get('sensitive_events', 0)}**",
        f"- Freshness watchlist: **{status.get('watchlist', 0)}**",
        f"- Memory schema: **v{status.get('schema_version', '?')}**",
        "",
        "## Active Direction",
        "",
    ]
    for label, value in (
        ("Project", session.get("active_project")),
        ("Current task", session.get("current_task")),
        ("Checkpoint", session.get("summary")),
    ):
        if value:
            lines.append(f"- **{label}:** {value}")
    for decision in (session.get("decisions") or [])[:10]:
        lines.append(f"- **Decision:** {decision}")
    for item in (session.get("open_loops") or [])[:10]:
        lines.append(f"- **Open loop:** {item}")
    if lines[-1] == "":
        lines.append("- No active checkpoint has been recorded.")

    lines.extend(["", "## Typed Timeline", ""])
    visible_timeline = [
        item for item in state["timeline"]
        if state["include_sensitive"] or not item.get("sensitive")
    ]
    for item in visible_timeline[:30]:
        private = " **[PRIVATE]**" if item.get("sensitive") else ""
        lines.append(
            f"- `{item.get('occurred_at')}` **{item.get('domain')}/{item.get('event_type')}**{private}: "
            f"{item.get('content')}"
        )
    if not visible_timeline:
        lines.append("- No eligible timeline entries.")
    if not state["include_sensitive"] and status.get("sensitive_events", 0):
        lines.append(f"- _{status['sensitive_events']} private wellbeing event(s) intentionally omitted._")

    lines.extend(["", "## Durable Memory", ""])
    for item in state["memories"][:30]:
        pin = " · pinned" if item.get("pinned") else ""
        lines.append(f"- **{item.get('kind')}/{item.get('memory_key')}**{pin}: {item.get('value')}")
    if not state["memories"]:
        lines.append("- No eligible semantic memories.")

    lines.extend(["", "## Verified Workspace Trajectory", ""])
    for event in state["project_events"][-24:]:
        detail = str(event.get("result") or "").replace("\n", " ")[:220]
        lines.append(
            f"- `{event.get('created_at')}` **{event.get('action')}** `{event.get('path') or ''}`"
            + (f" — {detail}" if detail else "")
        )
    if not state["project_events"]:
        lines.append("- No verified workspace events recorded.")

    lines.extend(["", "## Freshness Watchlist", ""])
    for watch in state["watchlist"][:30]:
        lines.append(
            f"- **{watch.get('query')}** — {watch.get('last_status')}; next check `{watch.get('next_check_at')}`"
        )
    if not state["watchlist"]:
        lines.append("- No volatile facts are being watched.")

    lines.extend(
        [
            "",
            "## Privacy Boundary",
            "",
            "- Sensitive recall is query-gated and never treated as a diagnosis.",
            "- Private wellbeing text is omitted from reports unless `report_include_sensitive` is explicitly enabled.",
            "- SQLite is not database-encrypted by Intermix; local protection relies on Android/Termux app-private storage.",
            "",
        ]
    )
    return "\n".join(lines)


def _pdf_escape(text: str) -> str:
    ascii_text = text.encode("ascii", "replace").decode("ascii")
    return ascii_text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def _markdown_to_lines(markdown: str) -> list[str]:
    output: list[str] = []
    for raw in markdown.splitlines():
        clean = re.sub(r"[`*_>#]", "", raw).strip()
        if not clean:
            output.append("")
            continue
        output.extend(textwrap.wrap(clean, width=92, break_long_words=False) or [""])
    return output


def _minimal_pdf(markdown: str) -> bytes:
    lines = _markdown_to_lines(markdown)
    pages = [lines[index:index + 50] for index in range(0, len(lines), 50)] or [["Project Intermix"]]
    objects: list[bytes] = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    page_object_numbers = [4 + index * 2 for index in range(len(pages))]
    kids = " ".join(f"{number} 0 R" for number in page_object_numbers)
    objects.append(f"<< /Type /Pages /Count {len(pages)} /Kids [{kids}] >>".encode())
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for index, page_lines in enumerate(pages):
        page_no = page_object_numbers[index]
        content_no = page_no + 1
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_no} 0 R >>".encode()
        )
        commands = ["BT", "/F1 9 Tf", "12 TL", "42 755 Td"]
        for line in page_lines:
            commands.append(f"({_pdf_escape(line)}) Tj")
            commands.append("T*")
        commands.extend(["T*", f"(Page {index + 1} of {len(pages)}) Tj", "ET"])
        stream = "\n".join(commands).encode("ascii")
        objects.append(b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream")

    document = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(document))
        document.extend(f"{number} 0 obj\n".encode())
        document.extend(obj)
        document.extend(b"\nendobj\n")
    xref = len(document)
    document.extend(f"xref\n0 {len(objects) + 1}\n".encode())
    document.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        document.extend(f"{offset:010d} 00000 n \n".encode())
    document.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    )
    return bytes(document)


def _atomic_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    try:
        temporary.write_bytes(data)
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def generate_idle_report(
    store: MemoryStore,
    *,
    cancel_event: threading.Event | None = None,
    force: bool = False,
) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    today = now.date().isoformat()
    include_sensitive = store.get_setting("report_include_sensitive", "off").casefold() == "on"
    state = _report_state(store, include_sensitive)
    digest = _state_hash(state)
    latest = store.latest_report_run("daily_status")
    if latest and not force:
        latest_day = str(latest.get("created_at") or "")[:10]
        if latest_day == today:
            return {"status": "not_due_today", "latest": latest.get("pdf_path", "")}
        if str(latest.get("state_hash") or "") == digest:
            return {"status": "unchanged", "latest": latest.get("pdf_path", "")}
    if cancel_event is not None and cancel_event.is_set():
        return {"status": "cancelled"}

    generated_at = now.replace(microsecond=0).isoformat()
    markdown = _render_markdown(state, generated_at)
    suffix = now.strftime("%Y%m%d_%H%M%S") if force else now.strftime("%Y%m%d")
    markdown_path = REPORT_DIR / f"Intermix_Cognitive_Snapshot_{suffix}.md"
    pdf_path = REPORT_DIR / f"Intermix_Cognitive_Snapshot_{suffix}.pdf"
    if cancel_event is not None and cancel_event.is_set():
        return {"status": "cancelled"}
    atomic_write_text(markdown_path, markdown)
    if cancel_event is not None and cancel_event.is_set():
        try:
            markdown_path.unlink()
        except OSError:
            pass
        return {"status": "cancelled"}
    _atomic_bytes(pdf_path, _minimal_pdf(markdown))
    store.record_report_run(
        report_type="daily_status",
        state_hash=digest,
        markdown_path=str(markdown_path),
        pdf_path=str(pdf_path),
        status="generated",
        metadata={"include_sensitive": include_sensitive, "generated_without_inference": True},
    )
    return {
        "status": "generated",
        "markdown": str(markdown_path),
        "pdf": str(pdf_path),
        "include_sensitive": include_sensitive,
        "state_hash": digest,
    }


__all__ = ["REPORT_DIR", "generate_idle_report"]
