"""Small, local-only runtime configuration contract for Project Intermix.

The public source tree must not assume one person's name, one model filename,
or one Android storage layout.  Configuration is read from a mode-600 JSON
file created by the installer, with environment variables taking precedence
for tests and advanced deployments.  Secret provider keys deliberately live
in the separate provider vault and are never accepted here.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PUBLIC_RELEASE = "1.4.1-alpha.1"
DEFAULT_CONFIG_FILE = Path(
    os.environ.get("INTERMIX_CONFIG_FILE", "~/.config/intermix/config.json")
).expanduser()


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    return value if isinstance(value, dict) else {}


def _label(value: Any, fallback: str, *, maximum: int = 64) -> str:
    clean = re.sub(r"[\x00-\x1f\x7f]+", " ", str(value or ""))
    clean = re.sub(r"\s+", " ", clean).strip()
    return (clean[:maximum] or fallback).strip()


def _integer(value: Any, fallback: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = fallback
    return max(minimum, min(maximum, parsed))


def _path(value: Any, fallback: str | Path) -> Path:
    raw = str(value or fallback)
    return Path(os.path.expandvars(raw)).expanduser().resolve(strict=False)


def _configured(
    payload: dict[str, Any],
    key: str,
    env_key: str,
    fallback: Any,
) -> Any:
    if env_key in os.environ:
        return os.environ[env_key]
    return payload.get(key, fallback)


@dataclass(frozen=True)
class RuntimeConfig:
    config_file: Path
    project_name: str
    user_name: str
    assistant_name: str
    model_label: str
    project_dir: Path
    model_path: Path
    model_cache_dir: Path
    memory_db: Path
    identity_file: Path
    archive_dir: Path
    workspace_dir: Path
    audio_bridge_dir: Path
    context_tokens: int

    def public_status(self) -> dict[str, Any]:
        """Return non-secret settings suitable for local diagnostics."""
        return {
            "release": PUBLIC_RELEASE,
            "project_name": self.project_name,
            "user_name": self.user_name,
            "assistant_name": self.assistant_name,
            "model_label": self.model_label,
            "project_dir": str(self.project_dir),
            "model_path": str(self.model_path),
            "workspace_dir": str(self.workspace_dir),
            "context_tokens": self.context_tokens,
        }


def load_runtime_config(path: Path | None = None) -> RuntimeConfig:
    config_file = (path or DEFAULT_CONFIG_FILE).expanduser().resolve(strict=False)
    payload = _load_json(config_file)

    project_dir = _path(
        _configured(
            payload,
            "project_dir",
            "INTERMIX_PROJECT_DIR",
            "~/project-intermix",
        ),
        "~/project-intermix",
    )
    default_model = project_dir / "models" / "gemma-4-E4B-it.litertlm"
    model_path = _path(
        _configured(payload, "model_path", "INTERMIX_MODEL_PATH", default_model),
        default_model,
    )
    model_cache_dir = _path(
        _configured(
            payload,
            "model_cache_dir",
            "INTERMIX_MODEL_CACHE_DIR",
            model_path.parent,
        ),
        model_path.parent,
    )

    return RuntimeConfig(
        config_file=config_file,
        project_name=_label(
            _configured(payload, "project_name", "INTERMIX_PROJECT_NAME", "Project Intermix"),
            "Project Intermix",
        ),
        user_name=_label(
            _configured(payload, "user_name", "INTERMIX_USER_NAME", "Operator"),
            "Operator",
        ),
        assistant_name=_label(
            _configured(
                payload,
                "assistant_name",
                "INTERMIX_ASSISTANT_NAME",
                "Intermix Core",
            ),
            "Intermix Core",
        ),
        model_label=_label(
            _configured(payload, "model_label", "INTERMIX_MODEL_LABEL", model_path.stem),
            model_path.stem,
        ),
        project_dir=project_dir,
        model_path=model_path,
        model_cache_dir=model_cache_dir,
        memory_db=_path(
            _configured(
                payload,
                "memory_db",
                "INTERMIX_MEMORY_DB",
                project_dir / "memory" / "sovereign.db",
            ),
            project_dir / "memory" / "sovereign.db",
        ),
        identity_file=_path(
            _configured(
                payload,
                "identity_file",
                "INTERMIX_IDENTITY_FILE",
                project_dir / "memory" / "identity.txt",
            ),
            project_dir / "memory" / "identity.txt",
        ),
        archive_dir=_path(
            _configured(
                payload,
                "archive_dir",
                "INTERMIX_ARCHIVE_DIR",
                project_dir / "archive",
            ),
            project_dir / "archive",
        ),
        workspace_dir=_path(
            _configured(
                payload,
                "workspace_dir",
                "INTERMIX_WORKSPACE_DIR",
                "~/storage/downloads/intermix_workspace",
            ),
            "~/storage/downloads/intermix_workspace",
        ),
        audio_bridge_dir=_path(
            _configured(
                payload,
                "audio_bridge_dir",
                "INTERMIX_AUDIO_BRIDGE",
                "~/storage/downloads/IntermixAudioBridge",
            ),
            "~/storage/downloads/IntermixAudioBridge",
        ),
        context_tokens=_integer(
            _configured(payload, "context_tokens", "INTERMIX_CONTEXT_TOKENS", 8000),
            8000,
            1024,
            32768,
        ),
    )


CONFIG = load_runtime_config()


__all__ = [
    "CONFIG",
    "DEFAULT_CONFIG_FILE",
    "PUBLIC_RELEASE",
    "RuntimeConfig",
    "load_runtime_config",
]
