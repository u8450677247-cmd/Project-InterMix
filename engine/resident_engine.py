"""Persistent LiteRT-LM inference worker for Project Intermix.

The native Engine is created, used, and destroyed on one dedicated thread.
This keeps model initialization off Textual's event loop and reuses the loaded
GPU engine across otherwise stateless prompt conversations.
"""

from __future__ import annotations

import asyncio
import atexit
import importlib
import json
import os
import queue
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, AsyncGenerator, Callable

from runtime_config import CONFIG


DEFAULT_MODEL = str(CONFIG.model_path)
DEFAULT_CACHE_DIR = str(CONFIG.model_cache_dir)


class ResidentEngineError(RuntimeError):
    """The resident backend could not complete an inference request."""


@dataclass(frozen=True)
class EngineProfile:
    name: str
    label: str
    model_path: str
    cache_dir: str
    context_tokens: int

    @property
    def available(self) -> bool:
        return Path(self.model_path).expanduser().is_file()


REASONING_PROFILE = EngineProfile(
    name="reasoning",
    label=CONFIG.model_label,
    model_path=str(CONFIG.model_path),
    cache_dir=str(CONFIG.model_cache_dir),
    context_tokens=CONFIG.context_tokens,
)
LIBRARIAN_PROFILE = EngineProfile(
    name="librarian",
    label=CONFIG.librarian_model_label,
    model_path=str(CONFIG.librarian_model_path),
    cache_dir=str(CONFIG.model_cache_dir),
    context_tokens=CONFIG.librarian_context_tokens,
)
ENGINE_PROFILES = {
    REASONING_PROFILE.name: REASONING_PROFILE,
    LIBRARIAN_PROFILE.name: LIBRARIAN_PROFILE,
}


@dataclass
class _InferenceRequest:
    prompt: str
    loop: asyncio.AbstractEventLoop
    events: asyncio.Queue[tuple[str, str]]
    profile: EngineProfile | None = None
    request_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    cancelled: threading.Event = field(default_factory=threading.Event)


@dataclass
class _WarmRequest:
    loop: asyncio.AbstractEventLoop
    events: asyncio.Queue[tuple[str, str]]
    profile: EngineProfile | None = None
    request_id: str = field(default_factory=lambda: "warm-" + uuid.uuid4().hex[:8])
    cancelled: threading.Event = field(default_factory=threading.Event)


@dataclass
class _Control:
    command: str


def _phase(name: str, detail: str = "", **metrics: Any) -> str:
    payload = {"name": name, "detail": detail}
    payload.update(metrics)
    return json.dumps(payload, ensure_ascii=False)


def _extract_text(chunk: Any) -> str:
    if not hasattr(chunk, "get"):
        return ""
    content = chunk.get("content", [])
    if isinstance(content, str):
        return content
    pieces: list[str] = []
    for item in content or []:
        if isinstance(item, dict) and item.get("type") == "text":
            pieces.append(str(item.get("text", "")))
    return "".join(pieces)


def available_memory_mb() -> int | None:
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


class ResidentEngineManager:
    """Owns a lazily loaded native engine and streams results into asyncio."""

    def __init__(
        self,
        model_path: str = DEFAULT_MODEL,
        *,
        cache_dir: str = DEFAULT_CACHE_DIR,
        context_tokens: int = CONFIG.context_tokens,
        idle_seconds: int | None = None,
        low_memory_mb: int | None = None,
        pressure_idle_seconds: int | None = None,
        critical_memory_mb: int | None = None,
        keep_hot_memory_mb: int | None = None,
        prewarm_memory_mb: int | None = None,
        request_timeout: int | None = None,
        module_loader: Callable[[], Any] | None = None,
        profiles: dict[str, EngineProfile] | None = None,
    ):
        self.model_path = str(Path(model_path).expanduser())
        self.cache_dir = str(Path(cache_dir).expanduser())
        self.context_tokens = context_tokens
        self.default_profile = EngineProfile(
            name="reasoning",
            label=Path(self.model_path).stem,
            model_path=self.model_path,
            cache_dir=self.cache_dir,
            context_tokens=self.context_tokens,
        )
        self.profiles = dict(profiles or {})
        self.profiles.setdefault(self.default_profile.name, self.default_profile)
        self.idle_seconds = idle_seconds if idle_seconds is not None else int(
            os.environ.get("INTERMIX_ENGINE_IDLE_SECONDS", "600")
        )
        self.low_memory_mb = low_memory_mb if low_memory_mb is not None else int(
            os.environ.get("INTERMIX_ENGINE_LOW_MEMORY_MB", "2048")
        )
        self.pressure_idle_seconds = (
            pressure_idle_seconds if pressure_idle_seconds is not None else int(
                os.environ.get("INTERMIX_ENGINE_PRESSURE_IDLE_SECONDS", "20")
            )
        )
        self.critical_memory_mb = (
            critical_memory_mb if critical_memory_mb is not None else int(
                os.environ.get("INTERMIX_ENGINE_CRITICAL_MEMORY_MB", "1280")
            )
        )
        self.keep_hot_memory_mb = (
            keep_hot_memory_mb if keep_hot_memory_mb is not None else int(
                os.environ.get("INTERMIX_ENGINE_KEEP_HOT_MEMORY_MB", "2560")
            )
        )
        self.prewarm_memory_mb = (
            prewarm_memory_mb if prewarm_memory_mb is not None else int(
                os.environ.get("INTERMIX_ENGINE_PREWARM_MEMORY_MB", "5632")
            )
        )
        self.request_timeout = request_timeout if request_timeout is not None else int(
            os.environ.get("INTERMIX_INFERENCE_TIMEOUT", "900")
        )
        self._module_loader = module_loader or (lambda: importlib.import_module("litert_lm"))
        self._requests: queue.Queue[_InferenceRequest | _WarmRequest | _Control] = queue.Queue()
        self._thread: threading.Thread | None = None
        self._thread_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._engine: Any = None
        self._active_profile = ""
        self._state = "cold"
        self._last_used = 0.0
        self._init_seconds: float | None = None
        self._last_inference_seconds: float | None = None
        self._last_first_text_seconds: float | None = None
        self._requests_completed = 0
        self._failures = 0
        self._profile_failures: dict[str, int] = {}
        self._last_error = ""
        self._shutdown_requested = False

    def _set_state(self, state: str) -> None:
        with self._state_lock:
            self._state = state

    def status(self) -> dict[str, Any]:
        with self._state_lock:
            state = self._state
            loaded = self._engine is not None
            snapshot = {
                "state": state,
                "loaded": loaded,
                "init_seconds": self._init_seconds,
                "last_inference_seconds": self._last_inference_seconds,
                "last_first_text_seconds": self._last_first_text_seconds,
                "requests_completed": self._requests_completed,
                "failures": self._failures,
                "profile_failures": dict(self._profile_failures),
                "last_error": self._last_error,
                "active_profile": self._active_profile,
            }
        memory_mb = available_memory_mb()
        profile, effective_idle = self._residency_profile(memory_mb)
        snapshot["available_memory_mb"] = memory_mb
        snapshot["residency_profile"] = profile
        snapshot["effective_idle_unload_seconds"] = effective_idle
        snapshot["idle_unload_seconds"] = self.idle_seconds
        snapshot["low_memory_mb"] = self.low_memory_mb
        snapshot["pressure_idle_seconds"] = self.pressure_idle_seconds
        snapshot["critical_memory_mb"] = self.critical_memory_mb
        snapshot["keep_hot_memory_mb"] = self.keep_hot_memory_mb
        snapshot["prewarm_memory_mb"] = self.prewarm_memory_mb
        snapshot["profiles"] = {
            name: {
                "label": profile.label,
                "available": profile.available,
                "context_tokens": profile.context_tokens,
            }
            for name, profile in self.profiles.items()
        }
        return snapshot

    def _residency_profile(self, memory_mb: int | None) -> tuple[str, int]:
        if memory_mb is None:
            return "balanced", min(self.idle_seconds, 90)
        if memory_mb >= self.keep_hot_memory_mb:
            return "performance", self.idle_seconds
        if memory_mb >= self.low_memory_mb:
            return "balanced", min(self.idle_seconds, 90)
        if memory_mb >= self.critical_memory_mb:
            return "pressure", min(self.pressure_idle_seconds, 20)
        return "critical", 5

    def _resolve_profile(self, name: str | None) -> EngineProfile:
        if not name:
            return self.default_profile
        try:
            return self.profiles[name]
        except KeyError as exc:
            raise ResidentEngineError(f"Unknown model profile: {name}") from exc

    def can_prewarm(self, profile: str | None = None) -> bool:
        selected = self._resolve_profile(profile)
        if not selected.available:
            return False
        with self._state_lock:
            if self._engine is not None and self._active_profile == selected.name:
                return True
        memory_mb = available_memory_mb()
        return memory_mb is not None and memory_mb >= self.prewarm_memory_mb

    def can_attempt(self, force: bool = False, profile: str | None = None) -> bool:
        if self._shutdown_requested:
            return False
        selected = self._resolve_profile(profile)
        with self._state_lock:
            return force or self._profile_failures.get(selected.name, 0) < 2

    def _ensure_thread(self) -> None:
        with self._thread_lock:
            if self._thread and self._thread.is_alive():
                return
            if self._shutdown_requested:
                raise ResidentEngineError("Resident engine manager is shutting down")
            self._thread = threading.Thread(
                target=self._worker,
                name="intermix-litert-engine",
                daemon=True,
            )
            self._thread.start()

    @staticmethod
    def _emit(request: _InferenceRequest | _WarmRequest, event_type: str, payload: str) -> None:
        if request.cancelled.is_set() or request.loop.is_closed():
            return

        def deliver() -> None:
            if not request.cancelled.is_set():
                request.events.put_nowait((event_type, payload))

        try:
            request.loop.call_soon_threadsafe(deliver)
        except RuntimeError:
            request.cancelled.set()

    def _suppress_native_logs(self, module: Any) -> None:
        setter = getattr(module, "set_min_log_severity", None)
        severity_type = getattr(module, "LogSeverity", None)
        severity = getattr(severity_type, "ERROR", None) if severity_type else None
        if callable(setter) and severity is not None:
            setter(severity)

    def _ensure_engine(self, request: _InferenceRequest | _WarmRequest) -> float:
        profile = request.profile or self.default_profile
        if self._engine is not None and self._active_profile == profile.name:
            self._emit(
                request,
                "phase",
                _phase("engine_hot", f"Resident {profile.name} GPU engine reused"),
            )
            return 0.0
        if self._engine is not None:
            self._emit(
                request,
                "phase",
                _phase(
                    "model_switch",
                    f"Releasing {self._active_profile or 'previous'} model before loading {profile.name}",
                ),
            )
            self._close_engine("model_switch")
        if not Path(profile.model_path).is_file():
            raise FileNotFoundError(f"Model file not found: {profile.model_path}")

        self._set_state("warming")
        self._emit(
            request,
            "phase",
            _phase("warming", f"Loading {profile.name} GPU engine", model_role=profile.name),
        )
        started = time.perf_counter()
        module = self._module_loader()
        self._suppress_native_logs(module)
        Path(profile.cache_dir).mkdir(parents=True, exist_ok=True)
        self._engine = module.Engine(
            profile.model_path,
            backend=module.Backend.GPU(),
            max_num_tokens=profile.context_tokens,
            cache_dir=profile.cache_dir,
        )
        elapsed = time.perf_counter() - started
        with self._state_lock:
            self._init_seconds = elapsed
            self._state = "hot"
            self._active_profile = profile.name
        self._emit(
            request,
            "phase",
            _phase(
                "engine_ready",
                f"Resident {profile.name} GPU engine ready",
                seconds=round(elapsed, 3),
                model_role=profile.name,
            ),
        )
        return elapsed

    def _handle_request(self, request: _InferenceRequest) -> None:
        request_started = time.perf_counter()
        init_seconds = self._ensure_engine(request)
        if request.cancelled.is_set():
            return

        self._set_state("generating")
        self._emit(request, "phase", _phase("generating", "Running local inference"))
        first_text_seconds: float | None = None
        conversation = self._engine.create_conversation()
        try:
            for chunk in conversation.send_message_async(request.prompt):
                if request.cancelled.is_set():
                    continue
                text = _extract_text(chunk)
                if not text:
                    continue
                if first_text_seconds is None:
                    first_text_seconds = time.perf_counter() - request_started
                    self._emit(
                        request,
                        "phase",
                        _phase(
                            "streaming",
                            "First local tokens received",
                            seconds=round(first_text_seconds, 3),
                        ),
                    )
                self._emit(request, "token", text)
        finally:
            close = getattr(conversation, "close", None)
            if callable(close):
                close()

        elapsed = time.perf_counter() - request_started
        with self._state_lock:
            self._last_inference_seconds = elapsed
            self._last_first_text_seconds = first_text_seconds
            self._requests_completed += 1
            self._profile_failures[(request.profile or self.default_profile).name] = 0
            self._last_used = time.monotonic()
            self._state = "hot"
        self._emit(
            request,
            "_backend_meta",
            json.dumps(
                {
                    "backend": "resident",
                    "return_code": 0,
                    "init_seconds": round(init_seconds, 3),
                    "first_text_seconds": (
                        round(first_text_seconds, 3) if first_text_seconds is not None else None
                    ),
                    "total_seconds": round(elapsed, 3),
                    "model_role": (request.profile or self.default_profile).name,
                    "model_label": (request.profile or self.default_profile).label,
                }
            ),
        )
        self._emit(request, "_done", "")

    def _handle_warm(self, request: _WarmRequest) -> None:
        self._ensure_engine(request)
        with self._state_lock:
            self._last_used = time.monotonic()
            self._state = "hot"
        self._emit(request, "_done", "")

    def _close_engine(self, reason: str = "manual") -> None:
        engine = self._engine
        if engine is None:
            self._set_state("cold")
            return
        self._set_state("unloading")
        try:
            close = getattr(engine, "close", None)
            if callable(close):
                close()
        finally:
            with self._state_lock:
                self._engine = None
                self._state = "cold"
                self._active_profile = ""
                if reason == "error":
                    self._last_used = 0.0

    def _maintenance(self) -> None:
        if self._engine is None or not self._last_used:
            return
        idle_for = time.monotonic() - self._last_used
        memory_mb = available_memory_mb()
        profile, effective_idle = self._residency_profile(memory_mb)
        if idle_for >= effective_idle:
            reason = "memory_pressure" if profile in {"pressure", "critical"} else "idle"
            self._close_engine(reason)

    def _worker(self) -> None:
        while True:
            try:
                item = self._requests.get(timeout=1.0)
            except queue.Empty:
                self._maintenance()
                continue

            if isinstance(item, _Control):
                if item.command == "shutdown":
                    self._close_engine("shutdown")
                    self._set_state("stopped")
                    return
                if item.command == "unload":
                    self._close_engine("manual")
                continue

            try:
                if isinstance(item, _WarmRequest):
                    self._handle_warm(item)
                else:
                    self._handle_request(item)
            except Exception as exc:
                failed_profile = (item.profile or self.default_profile).name
                with self._state_lock:
                    self._failures += 1
                    self._profile_failures[failed_profile] = (
                        self._profile_failures.get(failed_profile, 0) + 1
                    )
                    self._last_error = f"{type(exc).__name__}: {exc}"
                    self._state = "error"
                try:
                    self._close_engine("error")
                except Exception:
                    pass
                self._emit(item, "error", self._last_error)
                self._emit(item, "_done", "")

    async def stream(
        self, prompt: str, *, force: bool = False, profile: str | None = None
    ) -> AsyncGenerator[tuple[str, str], None]:
        selected = self._resolve_profile(profile)
        if not self.can_attempt(force=force, profile=selected.name):
            raise ResidentEngineError("Resident backend disabled after repeated failures")
        self._ensure_thread()
        loop = asyncio.get_running_loop()
        events: asyncio.Queue[tuple[str, str]] = asyncio.Queue()
        request = _InferenceRequest(
            prompt=prompt,
            loop=loop,
            events=events,
            profile=selected,
        )
        self._requests.put(request)
        try:
            while True:
                try:
                    event_type, payload = await asyncio.wait_for(
                        events.get(), timeout=self.request_timeout
                    )
                except asyncio.TimeoutError as exc:
                    raise ResidentEngineError(
                        f"Resident inference exceeded {self.request_timeout} seconds"
                    ) from exc
                if event_type == "_done":
                    return
                if event_type == "error":
                    raise ResidentEngineError(payload)
                yield event_type, payload
        finally:
            request.cancelled.set()

    async def warm(self, profile: str | None = None) -> bool:
        """Load the engine without generating text when measured headroom is safe."""
        selected = self._resolve_profile(profile)
        if not self.can_attempt(profile=selected.name) or not self.can_prewarm(selected.name):
            return False
        with self._state_lock:
            if self._engine is not None and self._active_profile == selected.name:
                return True
        self._ensure_thread()
        loop = asyncio.get_running_loop()
        events: asyncio.Queue[tuple[str, str]] = asyncio.Queue()
        request = _WarmRequest(loop=loop, events=events, profile=selected)
        self._requests.put(request)
        try:
            while True:
                try:
                    event_type, payload = await asyncio.wait_for(
                        events.get(), timeout=self.request_timeout
                    )
                except asyncio.TimeoutError as exc:
                    raise ResidentEngineError(
                        f"Resident warm-up exceeded {self.request_timeout} seconds"
                    ) from exc
                if event_type == "_done":
                    return True
                if event_type == "error":
                    raise ResidentEngineError(payload)
        finally:
            request.cancelled.set()

    def request_unload(self) -> None:
        if self._thread and self._thread.is_alive():
            self._requests.put(_Control("unload"))

    def shutdown(self, wait: bool = False) -> None:
        if self._shutdown_requested:
            return
        self._shutdown_requested = True
        if self._thread and self._thread.is_alive():
            self._requests.put(_Control("shutdown"))
            if wait and threading.current_thread() is not self._thread:
                self._thread.join(timeout=12)


RESIDENT_ENGINE = ResidentEngineManager(profiles=ENGINE_PROFILES)
atexit.register(RESIDENT_ENGINE.shutdown)


__all__ = [
    "ENGINE_PROFILES",
    "EngineProfile",
    "LIBRARIAN_PROFILE",
    "REASONING_PROFILE",
    "RESIDENT_ENGINE",
    "ResidentEngineError",
    "ResidentEngineManager",
    "available_memory_mb",
]
