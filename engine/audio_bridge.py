"""Safe completed-response audio queue and Android-native playback for Intermix."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import time
import uuid
import wave
from pathlib import Path
from typing import Any, Sequence

from runtime_config import CONFIG


PROTOCOL_VERSION = 1
HEARTBEAT_MAX_AGE_SECONDS = 10.0
AUDIO_ARCHIVE_LIMIT = 25
PARTIAL_RETENTION_SECONDS = 60 * 60
PLAYBACK_RECOVERY_SECONDS = 2 * 60
BRIDGE_DIR = CONFIG.audio_bridge_dir
AUDIO_ARCHIVE_DIR = BRIDGE_DIR / "audio"
PINS_FILE = BRIDGE_DIR / "pins.json"

_REQUEST_ID = re.compile(r"[a-f0-9]{32}")
_MANAGED_AUDIO = re.compile(
    r"(?:[a-f0-9]{32}|\d{8}_\d{6}_intermix_[a-f0-9]{8})\.wav",
    re.IGNORECASE,
)
_ACTIVE_PLAYERS: dict[str, asyncio.subprocess.Process] = {}


def _read_json(path: Path) -> dict[str, Any] | None:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    return payload if isinstance(payload, dict) else None


def _atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bridge_status(root: Path = BRIDGE_DIR) -> dict[str, Any]:
    payload = _read_json(Path(root) / "status.json") or {}
    try:
        age = max(0.0, time.time() - float(payload.get("timestamp", 0)))
    except (TypeError, ValueError):
        age = float("inf")
    connected = bool(
        payload.get("protocol") == PROTOCOL_VERSION
        and payload.get("service") == "project-intermix-kokoro"
        and payload.get("state") != "offline"
        and age <= HEARTBEAT_MAX_AGE_SECONDS
    )
    return {
        "connected": connected,
        "age_seconds": age,
        "state": str(payload.get("state", "offline")),
        "model_loaded": bool(payload.get("model_loaded", False)),
        "instance_id": str(payload.get("instance_id", "")),
        "safety": str(payload.get("safety", "")),
        "active_request_id": str(payload.get("active_request_id", "")),
        "max_text_chars": int(payload.get("max_text_chars", 20_000) or 20_000),
    }


def prepare_spoken_text(markdown: str) -> str:
    """Reduce visual Markdown to natural speech without interpreting it as commands."""
    text = markdown.strip()
    text = re.sub(
        r"```[^\n]*\n.*?```",
        "\nCode block omitted from narration.\n",
        text,
        flags=re.DOTALL,
    )
    text = re.sub(r"!\[([^]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"https?://\S+", "linked source", text)
    text = re.sub(r"(?m)^\s{0,3}#{1,6}\s*", "", text)
    text = re.sub(r"(?m)^\s*[-*+]\s+", "", text)
    text = re.sub(r"(?m)^\s*\d+[.)]\s+", "", text)
    text = re.sub(r"[*_~`]", "", text)
    text = re.sub(r"<[^>]{1,200}>", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def submit_completed_response(
    response: str,
    *,
    generation_complete: bool,
    session_id: str = "",
    voice: str = "af_heart",
    speed: float = 1.0,
    autoplay: bool = True,
    root: Path = BRIDGE_DIR,
) -> str:
    """Queue one immutable response only after the controller stream has closed."""
    if not generation_complete:
        raise ValueError("Audio requests require a fully completed response.")
    status = bridge_status(root)
    if not status["connected"]:
        raise ConnectionError("The Debian Kokoro bridge heartbeat is not fresh.")
    text = prepare_spoken_text(response)
    if not text:
        raise ValueError("The completed response contained no speakable text.")
    remote_ceiling = max(500, int(status.get("max_text_chars", 20_000)))
    if len(text) > remote_ceiling:
        raise ValueError(
            f"The speakable response exceeds the Debian bridge ceiling of {remote_ceiling:,} characters."
        )
    if not 0.5 <= float(speed) <= 2.0:
        raise ValueError("Voice speed must be between 0.5 and 2.0.")

    request_id = uuid.uuid4().hex
    payload = {
        "protocol": PROTOCOL_VERSION,
        "id": request_id,
        "kind": "synthesize_completed_response",
        "created_at": time.time(),
        "generation_complete": True,
        "session_id": session_id,
        "text": text,
        "response_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "voice": voice,
        "speed": float(speed),
        "autoplay": bool(autoplay),
        "source": "project-intermix-termux",
    }
    _atomic_json(Path(root) / "requests" / f"{request_id}.json", payload)
    return request_id


def request_result(request_id: str, root: Path = BRIDGE_DIR) -> dict[str, Any] | None:
    if not _REQUEST_ID.fullmatch(request_id):
        return None
    return _read_json(Path(root) / "results" / f"{request_id}.json")


def _write_result(request_id: str, root: Path, **changes: Any) -> dict[str, Any]:
    result_path = Path(root) / "results" / f"{request_id}.json"
    payload = _read_json(result_path)
    if not payload or str(payload.get("id", "")) != request_id:
        raise ValueError("Playback result is missing or does not match its request ID.")
    payload.update(changes)
    payload["state_updated_at"] = time.time()
    _atomic_json(result_path, payload)
    return payload


def _validated_audio_path(result: dict[str, Any], root: Path) -> Path:
    raw_path = str(result.get("audio_path", ""))
    if not raw_path or Path(raw_path).is_absolute():
        raise ValueError("The bridge returned an invalid audio path.")
    archive = (Path(root) / "audio").resolve()
    unresolved = Path(root) / raw_path
    if unresolved.is_symlink():
        raise ValueError("The bridge audio file may not be a symbolic link.")
    candidate = unresolved.resolve(strict=True)
    if candidate.parent != archive or candidate.suffix.lower() != ".wav":
        raise ValueError("The bridge audio path escaped the managed archive.")
    if not candidate.is_file():
        raise ValueError("The bridge audio file is unavailable or unsafe.")
    expected = str(result.get("audio_sha256", ""))
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise ValueError("The bridge result did not include an audio integrity hash.")
    if _sha256(candidate) != expected:
        raise ValueError("The committed WAV failed its integrity check.")
    return candidate


def _claim_path(request_id: str, root: Path) -> Path:
    return Path(root) / "playback" / f"{request_id}.claim"


def claim_audio_ready(request_id: str, root: Path = BRIDGE_DIR) -> Path | None:
    """Atomically claim a committed WAV for the Termux Android audio player."""
    if not _REQUEST_ID.fullmatch(request_id):
        return None
    result = request_result(request_id, root)
    if not result or result.get("status") != "audio_ready":
        return None
    try:
        audio_path = _validated_audio_path(result, Path(root))
    except Exception as exc:
        _write_result(request_id, Path(root), status="failed", detail=str(exc))
        (Path(root) / "processing" / f"{request_id}.json").unlink(missing_ok=True)
        return None

    claim = _claim_path(request_id, Path(root))
    claim.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(claim, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return None
    try:
        os.write(descriptor, f"{os.getpid()} {time.time()}\n".encode("ascii"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    try:
        _write_result(
            request_id,
            Path(root),
            status="starting",
            detail="Committed WAV claimed by Termux; Android playback is starting.",
            playback_owner="termux-play-audio",
        )
    except Exception:
        claim.unlink(missing_ok=True)
        raise
    return audio_path


async def play_claimed_request(
    request_id: str,
    *,
    root: Path = BRIDGE_DIR,
    player_command: Sequence[str] = ("play-audio",),
) -> dict[str, Any]:
    """Play a previously claimed WAV and publish truthful lifecycle states."""
    root = Path(root)
    claim = _claim_path(request_id, root)
    result = request_result(request_id, root)
    if not claim.is_file() or not result or result.get("status") != "starting":
        raise ValueError("The audio request is not claimed for playback.")
    try:
        audio_path = _validated_audio_path(result, root)
        process = await asyncio.create_subprocess_exec(
            *tuple(player_command),
            str(audio_path),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _ACTIVE_PLAYERS[request_id] = process
        _write_result(
            request_id,
            root,
            status="playing",
            detail="Android-native Termux playback is active.",
            playback_pid=process.pid,
        )
        _, stderr = await process.communicate()
        if process.returncode == 0:
            outcome = "completed"
            detail = "Android-native playback completed successfully."
        elif process.returncode in (-15, 143):
            outcome = "cancelled"
            detail = "Playback was stopped by the user."
        else:
            outcome = "failed"
            detail = (
                stderr.decode("utf-8", "replace").strip()
                if stderr
                else f"play-audio exited with status {process.returncode}."
            )
        return _write_result(request_id, root, status=outcome, detail=detail)
    except asyncio.CancelledError:
        process = _ACTIVE_PLAYERS.get(request_id)
        if process and process.returncode is None:
            process.terminate()
            await process.wait()
        _write_result(
            request_id,
            root,
            status="cancelled",
            detail="Playback worker was cancelled.",
        )
        raise
    except Exception as exc:
        return _write_result(request_id, root, status="failed", detail=str(exc))
    finally:
        _ACTIVE_PLAYERS.pop(request_id, None)
        claim.unlink(missing_ok=True)
        (root / "processing" / f"{request_id}.json").unlink(missing_ok=True)
        prune_audio_archive(root=root)


def cancel_request_playback(request_id: str) -> bool:
    process = _ACTIVE_PLAYERS.get(request_id)
    if process is None or process.returncode is not None:
        return False
    process.terminate()
    return True


def recover_stale_playbacks(root: Path = BRIDGE_DIR) -> int:
    """Return abandoned starting/playing results to audio_ready after a crash."""
    root = Path(root)
    recovered = 0
    now = time.time()
    for result_path in (root / "results").glob("*.json"):
        payload = _read_json(result_path)
        if not payload or payload.get("status") not in {"starting", "playing"}:
            continue
        request_id = result_path.stem
        if request_id in _ACTIVE_PLAYERS:
            continue
        updated = float(payload.get("state_updated_at", payload.get("completed_at", 0)) or 0)
        if now - updated < PLAYBACK_RECOVERY_SECONDS:
            continue
        _claim_path(request_id, root).unlink(missing_ok=True)
        _write_result(
            request_id,
            root,
            status="audio_ready",
            detail="Recovered committed WAV after an interrupted Termux playback.",
        )
        recovered += 1
    return recovered


def next_audio_ready_request(root: Path = BRIDGE_DIR) -> str:
    root = Path(root)
    recover_stale_playbacks(root)
    ready: list[tuple[float, str]] = []
    for result_path in (root / "results").glob("*.json"):
        payload = _read_json(result_path)
        if payload and payload.get("status") == "audio_ready" and _REQUEST_ID.fullmatch(result_path.stem):
            ready.append((float(payload.get("completed_at", 0) or 0), result_path.stem))
    return min(ready)[1] if ready else ""


def _pins(root: Path) -> set[str]:
    payload = _read_json(Path(root) / "pins.json") or {}
    values = payload.get("files", [])
    return {str(name) for name in values if isinstance(name, str)} if isinstance(values, list) else set()


def set_audio_pinned(path: Path, pinned: bool, root: Path = BRIDGE_DIR) -> bool:
    archive = (Path(root) / "audio").resolve()
    unresolved = Path(path).expanduser()
    if unresolved.is_symlink():
        raise ValueError("Symbolic links cannot be pinned as voice recordings.")
    candidate = unresolved.resolve(strict=True)
    if candidate.parent != archive or not _MANAGED_AUDIO.fullmatch(candidate.name):
        raise ValueError("Only Intermix-managed archive WAVs can be pinned.")
    pins = _pins(Path(root))
    if pinned:
        pins.add(candidate.name)
    else:
        pins.discard(candidate.name)
    _atomic_json(Path(root) / "pins.json", {"files": sorted(pins), "updated_at": time.time()})
    return pinned


def audio_archive_entries(root: Path = BRIDGE_DIR) -> list[dict[str, Any]]:
    root = Path(root)
    archive = root / "audio"
    pins = _pins(root)
    entries: list[dict[str, Any]] = []
    for path in archive.glob("*.wav"):
        if not _MANAGED_AUDIO.fullmatch(path.name) or path.is_symlink():
            continue
        try:
            stat = path.stat()
        except OSError:
            continue
        entries.append(
            {
                "name": path.name,
                "path": str(path),
                "size_bytes": stat.st_size,
                "modified_at": stat.st_mtime,
                "pinned": path.name in pins,
            }
        )
    return sorted(entries, key=lambda item: float(item["modified_at"]), reverse=True)


def audio_archive_details(path: Path, root: Path = BRIDGE_DIR) -> dict[str, Any]:
    archive = (Path(root) / "audio").resolve()
    unresolved = Path(path).expanduser()
    if unresolved.is_symlink():
        raise ValueError("Symbolic links are not valid archive recordings.")
    candidate = unresolved.resolve(strict=True)
    if candidate.parent != archive or not _MANAGED_AUDIO.fullmatch(candidate.name):
        raise ValueError("The selected file is outside the managed voice archive.")
    stat = candidate.stat()
    details: dict[str, Any] = {
        "name": candidate.name,
        "path": str(candidate),
        "size_bytes": stat.st_size,
        "modified_at": stat.st_mtime,
        "pinned": candidate.name in _pins(Path(root)),
        "sample_rate": None,
        "channels": None,
        "duration_seconds": None,
    }
    try:
        with wave.open(str(candidate), "rb") as source:
            rate = source.getframerate()
            details.update(
                sample_rate=rate,
                channels=source.getnchannels(),
                duration_seconds=(source.getnframes() / rate if rate else 0.0),
            )
    except (OSError, EOFError, wave.Error):
        pass
    return details


def prune_audio_archive(
    *,
    root: Path = BRIDGE_DIR,
    limit: int = AUDIO_ARCHIVE_LIMIT,
) -> list[str]:
    """Rotate managed WAVs while preserving active, pinned, and external exports."""
    root = Path(root)
    archive = root / "audio"
    archive.mkdir(parents=True, exist_ok=True)
    now = time.time()
    for partial in archive.glob("*.part"):
        try:
            if now - partial.stat().st_mtime >= PARTIAL_RETENTION_SECONDS:
                partial.unlink(missing_ok=True)
        except OSError:
            continue

    protected = _pins(root)
    for result_path in (root / "results").glob("*.json"):
        payload = _read_json(result_path)
        if not payload or payload.get("status") not in {"audio_ready", "starting", "playing"}:
            continue
        audio_path = Path(str(payload.get("audio_path", "")))
        if audio_path.parent == Path("audio"):
            protected.add(audio_path.name)

    candidates = []
    for path in archive.glob("*.wav"):
        if path.is_symlink() or not _MANAGED_AUDIO.fullmatch(path.name) or path.name in protected:
            continue
        try:
            candidates.append((path.stat().st_mtime, path))
        except OSError:
            continue
    candidates.sort(reverse=True)
    removed: list[str] = []
    for _, path in candidates[max(0, int(limit)) :]:
        path.unlink(missing_ok=True)
        removed.append(path.name)
    return removed
