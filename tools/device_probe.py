#!/usr/bin/env python3
"""Generate a share-safe Project Intermix capability report.

The default probe does not load a model, run inference, inspect personal
files, or collect identifiers such as serial numbers, Android IDs, usernames,
hostnames, IP addresses, or absolute paths.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import platform
import shutil
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone
from importlib import metadata
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1


def _getprop(name: str) -> str:
    executable = shutil.which("getprop")
    if not executable:
        return ""
    try:
        result = subprocess.run(
            [executable, name],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip()[:160]


def _memory() -> tuple[int | None, int | None]:
    total = None
    available = None
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition(":")
            if key not in {"MemTotal", "MemAvailable"}:
                continue
            kib = int(value.strip().split()[0])
            if key == "MemTotal":
                total = kib // 1024
            else:
                available = kib // 1024
    except (OSError, ValueError, IndexError):
        pass
    return total, available


def _distribution_version(name: str) -> str:
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return "not installed"


def _fts5_available() -> bool:
    try:
        with sqlite3.connect(":memory:") as db:
            db.execute("CREATE VIRTUAL TABLE probe_fts USING fts5(content)")
        return True
    except sqlite3.DatabaseError:
        return False


def _memory_tier(total_mb: int | None) -> str:
    if total_mb is None:
        return "unknown"
    if total_mb >= 14 * 1024:
        return "recommended-e4b"
    if total_mb >= 12 * 1024:
        return "candidate-e4b"
    if total_mb >= 8 * 1024:
        return "reduced-model-or-context-recommended"
    return "below-community-minimum"


def collect(storage_path: Path | None = None) -> dict[str, Any]:
    total_mb, available_mb = _memory()
    path = (storage_path or Path.home()).expanduser()
    try:
        storage = shutil.disk_usage(path)
        storage_free_mb = storage.free // (1024 * 1024)
        storage_total_mb = storage.total // (1024 * 1024)
    except OSError:
        storage_free_mb = None
        storage_total_mb = None
    terminal = shutil.get_terminal_size(fallback=(0, 0))
    prefix = os.environ.get("PREFIX", "")

    return {
        "schema": SCHEMA_VERSION,
        "scope": "share-safe-no-inference",
        "collected_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "platform": {
            "system": platform.system(),
            "machine": platform.machine(),
            "android_release": _getprop("ro.build.version.release"),
            "manufacturer": _getprop("ro.product.manufacturer"),
            "model": _getprop("ro.product.model"),
            "soc": _getprop("ro.soc.model"),
            "termux": "com.termux" in prefix,
        },
        "resources": {
            "memory_total_mb": total_mb,
            "memory_available_mb": available_mb,
            "storage_total_mb": storage_total_mb,
            "storage_available_mb": storage_free_mb,
            "terminal_columns": terminal.columns,
            "terminal_lines": terminal.lines,
            "memory_tier": _memory_tier(total_mb),
        },
        "runtime": {
            "python": platform.python_version(),
            "sqlite": sqlite3.sqlite_version,
            "sqlite_fts5": _fts5_available(),
            "textual": _distribution_version("textual"),
            "litert_lm": _distribution_version("litert-lm"),
            "litert_lm_api": _distribution_version("litert-lm-api"),
            "litert_lm_importable": importlib.util.find_spec("litert_lm") is not None,
        },
        "privacy": {
            "model_loaded": False,
            "inference_run": False,
            "personal_files_read": False,
            "identifiers_collected": False,
        },
    }


def _human(report: dict[str, Any]) -> str:
    platform_data = report["platform"]
    resources = report["resources"]
    runtime = report["runtime"]
    device = " ".join(
        item for item in (platform_data["manufacturer"], platform_data["model"]) if item
    ) or "unidentified platform"
    lines = [
        "PROJECT INTERMIX · SHARE-SAFE DEVICE REPORT",
        f"Device: {device}",
        f"SoC / architecture: {platform_data['soc'] or 'unknown'} / {platform_data['machine']}",
        f"Android: {platform_data['android_release'] or 'not detected'}",
        f"Termux: {'yes' if platform_data['termux'] else 'no'}",
        f"Memory: {resources['memory_total_mb'] or 'unknown'} MiB total; "
        f"{resources['memory_available_mb'] or 'unknown'} MiB available",
        f"Memory tier: {resources['memory_tier']}",
        f"Storage available: {resources['storage_available_mb'] or 'unknown'} MiB",
        f"Terminal: {resources['terminal_columns']} × {resources['terminal_lines']}",
        f"Python / SQLite: {runtime['python']} / {runtime['sqlite']} (FTS5: {runtime['sqlite_fts5']})",
        f"Textual / LiteRT-LM API: {runtime['textual']} / {runtime['litert_lm_api']}",
        "Privacy: no model load, inference, personal-file scan, or unique identifiers.",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    parser.add_argument(
        "--storage-path",
        type=Path,
        default=Path.home(),
        help="Filesystem whose free capacity should be measured; the path is not reported",
    )
    parser.add_argument("--output", type=Path, help="Write the report to this local file")
    args = parser.parse_args(argv)
    report = collect(args.storage_path)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) if args.json else _human(report)
    if args.output:
        args.output.expanduser().write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
