"""Incremental, tokenizer-free workspace structure documentation."""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import Any

from workspace_state import (
    META_DIR,
    WORKSPACE_DIR,
    atomic_write_json,
    file_hash,
    iter_workspace_files,
    read_json,
    relative_path,
    utc_now,
    write_text,
)


MANIFEST_FILE = META_DIR / "project_manifest.json"
DOCUMENT_PATH = "PROJECT_STATE.md"
MAX_INDEX_BYTES = 1_000_000
MAX_DOCUMENTED_FILES = 400


def _python_summary(path: Path) -> str:
    try:
        source = path.read_text(encoding="utf-8")
        tree = ast.parse(source)
    except (OSError, UnicodeDecodeError, SyntaxError):
        return "Python source"
    classes: list[str] = []
    functions: list[str] = []
    imports: list[str] = []
    for node in tree.body:
        if isinstance(node, (ast.ClassDef,)):
            classes.append(node.name)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            functions.append(node.name)
        elif isinstance(node, ast.Import):
            imports.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imports.append(node.module)
    pieces: list[str] = []
    if classes:
        pieces.append("classes: " + ", ".join(classes[:8]))
    if functions:
        pieces.append("functions: " + ", ".join(functions[:12]))
    if imports:
        pieces.append("imports: " + ", ".join(dict.fromkeys(imports[:12])))
    return "; ".join(pieces)[:600] or "Python source"


def _text_summary(path: Path) -> str:
    if path.suffix.casefold() == ".py":
        return _python_summary(path)
    try:
        raw = path.read_bytes()[:8192]
    except OSError:
        return "unreadable"
    if b"\x00" in raw:
        return "binary file"
    lines = [line.strip() for line in raw.decode("utf-8", "replace").splitlines() if line.strip()]
    first = lines[0] if lines else "empty text file"
    return first[:300]


def _render_document(manifest: dict[str, dict[str, Any]], changed: list[str]) -> str:
    extensions: dict[str, int] = {}
    for path in manifest:
        extension = Path(path).suffix.casefold() or "[none]"
        extensions[extension] = extensions.get(extension, 0) + 1
    lines = [
        "# Workspace Project State",
        "",
        "> Generated locally by Project Intermix from verified filesystem state.",
        "",
        f"Updated: `{utc_now()}`  ",
        f"Indexed files: **{len(manifest)}**  ",
        f"Changed in this pass: **{len(changed)}**",
        "",
        "## Structure",
        "",
    ]
    for extension, count in sorted(extensions.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"- `{extension}`: {count}")
    lines.extend(["", "## Files", "", "| Path | Size | Structure |", "| --- | ---: | --- |"])
    for path, item in sorted(manifest.items())[:MAX_DOCUMENTED_FILES]:
        summary = str(item.get("summary", "")).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{path}` | {int(item.get('size', 0))} B | {summary} |")
    if len(manifest) > MAX_DOCUMENTED_FILES:
        lines.append(
            f"\n_Only the first {MAX_DOCUMENTED_FILES} paths are rendered; the local manifest retains all indexed metadata._"
        )
    lines.extend(["", "## Latest Changed Paths", ""])
    lines.extend(f"- `{path}`" for path in changed[-50:])
    if not changed:
        lines.append("- No structural changes detected.")
    return "\n".join(lines).rstrip() + "\n"


def update_project_documentation() -> dict[str, Any]:
    previous = read_json(MANIFEST_FILE, {})
    if not isinstance(previous, dict):
        previous = {}
    current: dict[str, dict[str, Any]] = {}
    changed: list[str] = []
    for path in iter_workspace_files(limit=3000):
        relative = relative_path(path)
        if relative == DOCUMENT_PATH:
            continue
        try:
            stat = path.stat()
        except OSError:
            continue
        if stat.st_size > MAX_INDEX_BYTES:
            continue
        old = previous.get(relative, {})
        unchanged = (
            int(old.get("size", -1)) == stat.st_size
            and int(old.get("mtime_ns", -1)) == stat.st_mtime_ns
        )
        if unchanged:
            current[relative] = old
            continue
        item = {
            "size": stat.st_size,
            "mtime_ns": stat.st_mtime_ns,
            "sha256": file_hash(path),
            "summary": _text_summary(path),
        }
        current[relative] = item
        changed.append(relative)
    removed = sorted(set(previous) - set(current))
    changed.extend(f"{path} [removed]" for path in removed)
    structural_change = bool(changed) or not MANIFEST_FILE.exists()
    if structural_change:
        atomic_write_json(MANIFEST_FILE, current)
        document = _render_document(current, changed)
        write_text(DOCUMENT_PATH, document, source="documenter")
    return {
        "changed": structural_change,
        "changed_paths": changed,
        "indexed_files": len(current),
        "document": str(WORKSPACE_DIR / DOCUMENT_PATH),
    }


__all__ = ["DOCUMENT_PATH", "MANIFEST_FILE", "update_project_documentation"]
