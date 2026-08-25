#!/usr/bin/env python3
"""Private, controller-only provider configuration for Project Intermix.

Secrets are entered outside the cockpit, stored in an app-private mode-600
file, and loaded only into the controller process environment. Values are
never returned by status commands or placed in SQLite/model context.
"""

from __future__ import annotations

import argparse
import getpass
import os
import shlex
import stat
import sys
import tempfile
from pathlib import Path


VAULT_FILE = Path(os.path.expanduser("~/.config/intermix/providers.env"))
SECRET_FIELDS = {
    "SERPAPI_API_KEY": "SerpAPI",
    "TINYFISH_API_KEY": "TinyFish Search/Fetch",
    "BRAVE_SEARCH_API_KEY": "Brave Search",
    "TAVILY_API_KEY": "Tavily",
    "EXA_API_KEY": "Exa",
    "GITHUB_TOKEN": "GitHub",
}
PLAIN_FIELDS = {
    "SEARXNG_URL": "SearXNG URL",
    "INTERMIX_SEARCH_LOCATION": "Search location code",
    "INTERMIX_SEARCH_LANGUAGE": "Search language code",
}
ALLOWED_FIELDS = {**SECRET_FIELDS, **PLAIN_FIELDS}


def _read_values(path: Path = VAULT_FILE) -> dict[str, str]:
    if not path.is_file():
        return {}
    try:
        if stat.S_IMODE(path.stat().st_mode) & 0o077:
            return {}
    except OSError:
        return {}
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        key, separator, encoded = line.partition("=")
        if not separator or key not in ALLOWED_FIELDS:
            continue
        try:
            parsed = shlex.split(encoded, posix=True)
        except ValueError:
            continue
        if len(parsed) == 1:
            values[key] = parsed[0]
    return values


def load_provider_environment(path: Path = VAULT_FILE) -> set[str]:
    """Load allowlisted values without overwriting an explicit environment."""
    loaded: set[str] = set()
    for key, value in _read_values(path).items():
        if value and key not in os.environ:
            os.environ[key] = value
            loaded.add(key)
    return loaded


def _write_values(values: dict[str, str], path: Path = VAULT_FILE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    lines = [
        "# Project Intermix Provider Vault — never paste this file into chat.",
        "# Managed by intermix-providers; values are loaded controller-side only.",
    ]
    for key in ALLOWED_FIELDS:
        value = values.get(key, "").strip()
        if value:
            lines.append(f"export {key}={shlex.quote(value)}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".providers.", suffix=".tmp", dir=path.parent
    )
    try:
        os.fchmod(descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
        os.chmod(path, 0o600)
    except Exception:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise


def _status(values: dict[str, str]) -> None:
    print("PROJECT INTERMIX · PROVIDER VAULT")
    print(f"Vault: {VAULT_FILE}")
    for key, label in ALLOWED_FIELDS.items():
        print(f"{label}: {'configured' if values.get(key) else 'not configured'}")
    print("Secret values are intentionally never displayed.")


def _select_field() -> str:
    fields = list(ALLOWED_FIELDS)
    print("\nChoose a provider to configure:")
    for index, key in enumerate(fields, start=1):
        print(f"  {index}. {ALLOWED_FIELDS[key]}")
    print("  R. Remove a configured provider")
    print("  Q. Save and quit")
    choice = input("Selection: ").strip().casefold()
    if choice in {"q", "quit"}:
        return ""
    if choice in {"r", "remove"}:
        return "__remove__"
    try:
        return fields[int(choice) - 1]
    except (ValueError, IndexError):
        print("Unknown selection.")
        return "__retry__"


def _interactive() -> int:
    values = _read_values()
    _status(values)
    while True:
        selected = _select_field()
        if not selected:
            _write_values(values)
            print("Vault saved. Relaunch sovereign to apply changes.")
            return 0
        if selected == "__retry__":
            continue
        if selected == "__remove__":
            configured = [key for key in ALLOWED_FIELDS if values.get(key)]
            if not configured:
                print("No configured provider can be removed.")
                continue
            for index, key in enumerate(configured, start=1):
                print(f"  {index}. {ALLOWED_FIELDS[key]}")
            try:
                key = configured[int(input("Remove number: ").strip()) - 1]
            except (ValueError, IndexError):
                print("Removal cancelled.")
                continue
            values.pop(key, None)
            print(f"{ALLOWED_FIELDS[key]} removed from the pending vault state.")
            continue
        label = ALLOWED_FIELDS[selected]
        if selected in SECRET_FIELDS:
            value = getpass.getpass(f"{label} key (hidden): ").strip()
        else:
            value = input(f"{label}: ").strip()
        if not value:
            print("Empty input ignored.")
            continue
        values[selected] = value
        print(f"{label}: configured (value hidden)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Configure Project Intermix web providers without exposing keys to the model."
    )
    parser.add_argument("command", nargs="?", choices=("status", "setup"), default="setup")
    args = parser.parse_args(argv)
    if args.command == "status":
        _status(_read_values())
        return 0
    if not sys.stdin.isatty():
        print("Provider setup requires an interactive terminal.", file=sys.stderr)
        return 2
    return _interactive()


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["ALLOWED_FIELDS", "VAULT_FILE", "load_provider_environment", "main"]
