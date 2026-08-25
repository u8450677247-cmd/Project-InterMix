#!/usr/bin/env python3
"""Build a deterministic, audited Project Intermix source release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
import zipfile
from pathlib import Path

# Building a source release must not create a cache inside the tree it audits.
sys.dont_write_bytecode = True

from public_release_audit import audit_repository


RELEASE_TIMESTAMP = (2026, 8, 25, 0, 0, 0)
RELEASE_DATE = "2026-08-25T00:00:00Z"


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _inside(candidate: Path, parent: Path) -> bool:
    try:
        candidate.relative_to(parent)
    except ValueError:
        return False
    return True


def _source_files(root: Path) -> list[Path]:
    return [
        path
        for path in sorted(root.rglob("*"))
        if path.is_file() and ".git" not in path.relative_to(root).parts
    ]


def _zip_info(name: str, executable: bool = False) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, RELEASE_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    mode = stat.S_IFREG | (0o755 if executable else 0o644)
    info.external_attr = mode << 16
    info.create_system = 3
    return info


def build_release(root: Path, output_dir: Path) -> tuple[Path, Path, dict[str, object]]:
    root = root.expanduser().resolve()
    output_dir = output_dir.expanduser().resolve()
    if _inside(output_dir, root):
        raise ValueError("release output must be outside the audited source tree")

    errors = audit_repository(root)
    if errors:
        raise ValueError("public release audit failed:\n- " + "\n- ".join(errors))

    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    archive_root = f"Project_Intermix_v{version}"
    archive_name = f"Project_Intermix_v{version}_source.zip"
    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / archive_name
    checksum_path = output_dir / f"{archive_name}.sha256"

    records: list[dict[str, object]] = []
    payloads: list[tuple[str, bytes, bool]] = []
    for path in _source_files(root):
        relative = path.relative_to(root).as_posix()
        data = path.read_bytes()
        executable = bool(path.stat().st_mode & stat.S_IXUSR)
        records.append(
            {
                "path": relative,
                "bytes": len(data),
                "sha256": _digest(data),
                "mode": "0755" if executable else "0644",
            }
        )
        payloads.append((f"{archive_root}/{relative}", data, executable))

    manifest: dict[str, object] = {
        "schema": 1,
        "project": "Project Intermix",
        "version": version,
        "release_date": RELEASE_DATE,
        "archive_root": archive_root,
        "files": records,
        "privacy_boundary": "source-only; no models, memory, credentials, caches, audio, or workspaces",
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()
    payloads.append((f"{archive_root}/RELEASE_MANIFEST.json", manifest_bytes, False))

    temporary = archive_path.with_suffix(archive_path.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for name, data, executable in sorted(payloads):
            bundle.writestr(_zip_info(name, executable), data)
    os.replace(temporary, archive_path)

    archive_digest = _digest(archive_path.read_bytes())
    checksum_path.write_text(f"{archive_digest}  {archive_name}\n", encoding="utf-8")
    return archive_path, checksum_path, manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path.cwd().parent / "intermix-release",
        help="Directory outside the source tree",
    )
    args = parser.parse_args(argv)
    try:
        archive, checksum, manifest = build_release(args.root, args.output_dir)
    except (OSError, ValueError) as exc:
        print(f"RELEASE BUILD FAILED: {exc}", file=sys.stderr)
        return 1
    print("RELEASE BUILD PASSED")
    print(f"Version: {manifest['version']}")
    print(f"Files: {len(manifest['files'])}")
    print(f"Archive: {archive}")
    print(f"Checksum: {checksum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
