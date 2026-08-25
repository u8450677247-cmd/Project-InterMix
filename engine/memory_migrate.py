"""Non-destructive migration from Intermix TXT and legacy SQLite memory."""

from __future__ import annotations

import argparse
import os
import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from memory_store import DEFAULT_DB, MemoryStore, normalize_text, stable_hash, utc_now
from runtime_config import CONFIG


DEFAULT_CONVO = str(CONFIG.project_dir / "memory" / "convo.txt")


@dataclass
class LegacyMessage:
    source: str
    source_key: str
    created_at: str
    speaker: str
    role: str
    content: str

    @property
    def fingerprint(self) -> str:
        return stable_hash(self.source, self.source_key, self.speaker, self.content)


def map_role(speaker: str) -> tuple[str, str]:
    normalized = speaker.strip().casefold()
    if normalized in {"operator", "user", "human", CONFIG.user_name.casefold()}:
        return "user", CONFIG.user_name
    if normalized in {
        "intermix core",
        "gemma 4",
        "assistant",
        "ai",
        CONFIG.assistant_name.casefold(),
    }:
        return "assistant", CONFIG.assistant_name
    if "sandbox" in normalized or normalized in {"tool", "execution"}:
        return "tool", "System Sandbox"
    return "system", speaker.strip()[:80] or "Legacy System"


def load_txt(path: str) -> list[LegacyMessage]:
    source = Path(path).expanduser()
    if not source.exists():
        return []
    raw = source.read_text(encoding="utf-8", errors="replace")
    pattern = re.compile(
        r"^\[(?P<time>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+"
        r"(?P<speaker>[^:\n]+):\s*\n(?P<content>.*?)(?=^\[\d{4}-\d{2}-\d{2} |\Z)",
        re.MULTILINE | re.DOTALL,
    )
    messages: list[LegacyMessage] = []
    for index, match in enumerate(pattern.finditer(raw), start=1):
        content = re.sub(r"(?m)^\s{3}", "", match.group("content")).strip()
        if not content:
            continue
        role, display = map_role(match.group("speaker"))
        created = match.group("time").replace(" ", "T")
        messages.append(
            LegacyMessage(
                source="legacy_txt",
                source_key=str(index),
                created_at=created,
                speaker=display,
                role=role,
                content=content,
            )
        )
    return messages


def _legacy_memory_table(db: sqlite3.Connection) -> bool:
    row = db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='memory'"
    ).fetchone()
    if not row:
        return False
    columns = {entry[1] for entry in db.execute("PRAGMA table_info(memory)")}
    return {"time", "role", "text"}.issubset(columns)


def load_legacy_db(path: str) -> list[LegacyMessage]:
    source = Path(path).expanduser()
    if not source.exists():
        return []
    db = sqlite3.connect(f"file:{source.resolve()}?mode=ro", uri=True)
    try:
        if not _legacy_memory_table(db):
            return []
        rows = db.execute("SELECT ROWID, time, role, text FROM memory ORDER BY ROWID ASC").fetchall()
    finally:
        db.close()

    file_date = datetime.fromtimestamp(source.stat().st_mtime, tz=timezone.utc).date().isoformat()
    messages: list[LegacyMessage] = []
    for rowid, raw_time, speaker, text in rows:
        content = str(text or "").strip()
        if not content:
            continue
        role, display = map_role(str(speaker or ""))
        time_text = str(raw_time or "").strip()
        if re.fullmatch(r"\d{2}:\d{2}:\d{2}", time_text):
            created = f"{file_date}T{time_text}+00:00"
        elif time_text:
            created = time_text.replace(" ", "T")
        else:
            created = utc_now()
        messages.append(
            LegacyMessage(
                source="legacy_sqlite",
                source_key=str(rowid),
                created_at=created,
                speaker=display,
                role=role,
                content=content,
            )
        )
    return messages


def _existing_fingerprints(db_path: str) -> set[str]:
    source = Path(db_path).expanduser()
    if not source.exists():
        return set()
    db = sqlite3.connect(f"file:{source.resolve()}?mode=ro", uri=True)
    try:
        table = db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages'"
        ).fetchone()
        if not table:
            return set()
        return {
            str(row[0]) for row in db.execute(
                "SELECT legacy_fingerprint FROM messages WHERE legacy_fingerprint IS NOT NULL"
            )
        }
    finally:
        db.close()


def collect_candidates(db_path: str, convo_path: str) -> list[LegacyMessage]:
    # TXT history historically preceded the compact SQLite implementation.
    return load_txt(convo_path) + load_legacy_db(db_path)


def migration_report(db_path: str, convo_path: str) -> dict[str, int]:
    candidates = collect_candidates(db_path, convo_path)
    existing = _existing_fingerprints(db_path)
    seen_fingerprints: set[str] = set()
    seen_long_content: set[tuple[str, str]] = set()
    importable = 0
    duplicate = 0
    for item in candidates:
        content_key = (item.role, normalize_text(item.content))
        cross_source_duplicate = len(content_key[1]) >= 40 and content_key in seen_long_content
        if item.fingerprint in existing or item.fingerprint in seen_fingerprints or cross_source_duplicate:
            duplicate += 1
            continue
        seen_fingerprints.add(item.fingerprint)
        if len(content_key[1]) >= 40:
            seen_long_content.add(content_key)
        importable += 1
    return {
        "txt_candidates": len(load_txt(convo_path)),
        "sqlite_candidates": len(load_legacy_db(db_path)),
        "importable": importable,
        "duplicates": duplicate,
    }


def apply_migration(db_path: str, convo_path: str) -> dict[str, int | str]:
    store = MemoryStore(db_path)
    session_id = store.get_setting("legacy_migration_session_id")
    if not session_id or not store.get_session(session_id):
        session = store.create_session("Recovered Intermix History", activate=False)
        session_id = session["id"]
        store.set_setting("legacy_migration_session_id", session_id)

    candidates = collect_candidates(db_path, convo_path)
    seen_long_content: set[tuple[str, str]] = set()
    imported = 0
    duplicates = 0
    for item in candidates:
        content_key = (item.role, normalize_text(item.content))
        cross_source_duplicate = len(content_key[1]) >= 40 and content_key in seen_long_content
        if cross_source_duplicate or store.content_exists(item.role, item.content):
            duplicates += 1
            continue
        before = store.message_count(session_id)
        store.append_message(
            session_id,
            item.role,
            item.content,
            speaker=item.speaker,
            created_at=item.created_at,
            source=item.source,
            legacy_fingerprint=item.fingerprint,
            metadata={"legacy_source_key": item.source_key},
        )
        after = store.message_count(session_id)
        if after > before:
            imported += 1
            if len(content_key[1]) >= 40:
                seen_long_content.add(content_key)
        else:
            duplicates += 1

    store.update_session(
        session_id,
        summary=(
            "Recovered conversation history imported from the original Intermix TXT and SQLite memory systems. "
            "Relevant details remain searchable and can be consolidated into durable memories as topics return."
        ),
        current_task="Build the versioned Project Intermix memory architecture.",
        active_project="Project Intermix",
    )
    store.activate_session(session_id)
    store.set_setting("legacy_migration_completed_at", utc_now())
    return {
        "session_id": session_id,
        "imported": imported,
        "duplicates": duplicates,
        "total_candidates": len(candidates),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--convo", default=DEFAULT_CONVO)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Apply the non-destructive import")
    mode.add_argument("--dry-run", action="store_true", help="Report what would be imported (default)")
    args = parser.parse_args()

    if args.apply:
        report = apply_migration(args.db, args.convo)
        print("MIGRATION APPLIED")
    else:
        report = migration_report(args.db, args.convo)
        print("DRY RUN - NO CONVERSATION DATA CHANGED")
    for key, value in report.items():
        print(f"{key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
