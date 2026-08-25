#!/usr/bin/env python3
"""Fail closed when a Project Intermix source tree contains private artifacts.

This is deliberately independent of Git so it can inspect an extracted release
before a repository exists. It is a release guardrail, not a secret-management
product and not a substitute for manual review.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import sys
from pathlib import Path


MAX_PUBLIC_FILE_BYTES = 5 * 1024 * 1024

REQUIRED_FILES = {
    ".github/ISSUE_TEMPLATE/bug_report.yml",
    ".github/ISSUE_TEMPLATE/config.yml",
    ".github/ISSUE_TEMPLATE/device_report.yml",
    ".github/ISSUE_TEMPLATE/provider_adapter.yml",
    ".github/pull_request_template.md",
    ".github/workflows/release.yml",
    ".github/workflows/tests.yml",
    ".gitattributes",
    ".gitignore",
    "CHANGELOG.md",
    "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md",
    "LICENSE",
    "NOTICE",
    "README.md",
    "SECURITY.md",
    "VERSION",
    "install.sh",
}

REQUIRED_EXECUTABLES = {
    "install.sh",
    "bin/intermix",
    "bin/intermix-doctor",
    "bin/intermix-providers",
    "bin/intermix-rollback",
    "tools/device_probe.py",
    "tools/build_release.py",
    "tools/public_release_audit.py",
}

FORBIDDEN_DIRECTORY_NAMES = {
    ".shaders",
    "__pycache__",
    "archive",
    "memory",
    "models",
    "output",
    "sovereign_workspace",
    "workspace",
    "workspaces",
}

FORBIDDEN_SUFFIXES = {
    ".bin",
    ".db",
    ".flac",
    ".litertlm",
    ".m4a",
    ".mp3",
    ".onnx",
    ".pyc",
    ".pyo",
    ".sqlite",
    ".sqlite3",
    ".wav",
}

FORBIDDEN_FILENAMES = {
    ".env",
    "config.json",
    "identity.txt",
    "providers.env",
    "sovereign.db",
}

PRIVATE_PATH_FRAGMENTS = (
    "/data/data/" + "com.termux/files/home/",
    "/home/" + "droid/",
    "/storage/emulated/0/" + "Android/data/",
)

# The expressions are assembled in pieces so the audit source does not contain
# a complete credential-shaped example that would match itself.
SECRET_PATTERNS = {
    "GitHub token": re.compile(r"gh" + r"[pousr]_[A-Za-z0-9]{20,}"),
    "GitHub fine-grained token": re.compile(r"github" + r"_pat_[A-Za-z0-9_]{20,}"),
    "OpenAI/TinyFish-style token": re.compile(r"s" + r"k-(?:proj-|tinyfish-)?[A-Za-z0-9_-]{20,}"),
    "Google API key": re.compile(r"AI" + r"za[0-9A-Za-z_-]{30,}"),
    "AWS access key": re.compile(r"AK" + r"IA[0-9A-Z]{16}"),
    "private key block": re.compile(r"-----BEGIN " + r"(?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}

# Private reference-build display aliases are configuration, not public source.
PRIVATE_ALIASES = tuple(part_a + part_b for part_a, part_b in (
    ("Sol", "kara"),
    ("Yas", "seh"),
))


def _relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def _is_probably_text(data: bytes) -> bool:
    if b"\x00" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def audit_repository(root: Path) -> list[str]:
    root = root.expanduser().resolve()
    errors: list[str] = []
    if not root.is_dir():
        return [f"not a directory: {root}"]

    present: set[str] = set()
    for path in sorted(root.rglob("*")):
        relative = _relative(path, root)
        parts = Path(relative).parts
        if ".git" in parts:
            continue

        present.add(relative)
        if path.is_symlink():
            errors.append(f"symlink is not permitted in a source release: {relative}")
            continue

        if path.is_dir():
            if path.name in FORBIDDEN_DIRECTORY_NAMES:
                errors.append(f"private/generated directory present: {relative}/")
            continue

        if not path.is_file():
            errors.append(f"non-regular filesystem entry: {relative}")
            continue

        lowered = path.name.casefold()
        suffix = path.suffix.casefold()
        if lowered in FORBIDDEN_FILENAMES or suffix in FORBIDDEN_SUFFIXES:
            errors.append(f"private/generated file type present: {relative}")

        try:
            size = path.stat().st_size
            data = path.read_bytes()
        except OSError as exc:
            errors.append(f"cannot read {relative}: {exc}")
            continue

        if size > MAX_PUBLIC_FILE_BYTES:
            errors.append(f"unexpected file larger than 5 MiB: {relative} ({size} bytes)")

        if not _is_probably_text(data):
            continue

        text = data.decode("utf-8")
        for fragment in PRIVATE_PATH_FRAGMENTS:
            if fragment in text:
                errors.append(f"private absolute path in {relative}: {fragment}")
        for alias in PRIVATE_ALIASES:
            if re.search(rf"\b{re.escape(alias)}\b", text, re.IGNORECASE):
                errors.append(f"private reference alias in {relative}")
        for name, pattern in SECRET_PATTERNS.items():
            if pattern.search(text):
                errors.append(f"possible {name} in {relative}")

    for relative in sorted(REQUIRED_FILES - present):
        errors.append(f"required release file missing: {relative}")

    for relative in sorted(REQUIRED_EXECUTABLES):
        path = root / relative
        if path.is_file() and not (path.stat().st_mode & stat.S_IXUSR):
            errors.append(f"required executable bit missing: {relative}")

    version_path = root / "VERSION"
    if version_path.is_file():
        version = version_path.read_text(encoding="utf-8", errors="replace").strip()
        if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version):
            errors.append(f"VERSION is not a supported release identifier: {version!r}")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    parser.add_argument("--json", action="store_true", help="Emit JSON")
    args = parser.parse_args(argv)

    errors = audit_repository(args.root)
    result = {
        "status": "failed" if errors else "passed",
        "root": args.root.expanduser().resolve().name,
        "errors": errors,
    }
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif errors:
        print("PUBLIC RELEASE AUDIT FAILED", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
    else:
        print("PUBLIC RELEASE AUDIT PASSED")
        print("No forbidden private state, credential signatures, model assets, or generated caches found.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
