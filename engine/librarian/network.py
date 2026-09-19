"""Narrow network/resource abstractions for platform-specific sentinels."""

from __future__ import annotations

import os
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class NetworkSnapshot:
    lan_reachable: bool | None = None
    nas_reachable: bool | None = None
    cortex_reachable: bool | None = None
    wifi_internet_validated: bool | None = None
    cellular_internet_available: bool | None = None
    active_wan: str = "unknown"
    metered: bool | None = None
    secure_overlay: bool | None = None

    def as_mapping(self) -> dict[str, object]:
        return asdict(self)


class NetworkSentinel(Protocol):
    def snapshot(self) -> NetworkSnapshot: ...


class UnknownNetworkSentinel:
    """Safe non-Android default: report unknown instead of guessing reachability."""

    def snapshot(self) -> NetworkSnapshot:
        return NetworkSnapshot()


class StaticNetworkSentinel:
    """Deterministic test/provider bridge used by Android or a future native adapter."""

    def __init__(self, state: NetworkSnapshot) -> None:
        self.state = state

    def snapshot(self) -> NetworkSnapshot:
        return self.state


@dataclass(frozen=True)
class ResourceSnapshot:
    free_ram_mib: int | None
    total_ram_mib: int | None
    free_storage_mib: int | None
    total_storage_mib: int | None
    battery_pct: float | None
    battery_temperature_c: float | None
    charging: bool | None
    thermal_temperature_c: float | None

    def as_mapping(self) -> dict[str, object]:
        return asdict(self)


class ResourceProvider(Protocol):
    def snapshot(self) -> ResourceSnapshot: ...


class LocalResourceProvider:
    """Read-only Linux/Android metrics with unknown values when a surface is absent."""

    def __init__(self, storage_path: str | os.PathLike[str]) -> None:
        self.storage_path = Path(storage_path).expanduser().resolve(strict=False)

    @staticmethod
    def _memory() -> tuple[int | None, int | None]:
        try:
            values: dict[str, int] = {}
            for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
                name, _, raw = line.partition(":")
                if name in {"MemTotal", "MemAvailable"}:
                    values[name] = int(raw.strip().split()[0]) // 1024
            return values.get("MemAvailable"), values.get("MemTotal")
        except (OSError, ValueError, IndexError):
            return None, None

    @staticmethod
    def _read_number(path: Path, divisor: float = 1.0) -> float | None:
        try:
            return float(path.read_text(encoding="utf-8").strip()) / divisor
        except (OSError, ValueError):
            return None

    @staticmethod
    def _temperature(path: Path) -> float | None:
        value = LocalResourceProvider._read_number(path)
        if value is None:
            return None
        # Android kernels expose degrees, tenths, or millidegrees depending on
        # the driver. Normalize conservatively without hard-coding one device.
        while value > 150:
            value /= 10.0
        return value if -20 <= value <= 150 else None

    @staticmethod
    def _battery() -> tuple[float | None, float | None, bool | None]:
        root = Path("/sys/class/power_supply/battery")
        percentage = LocalResourceProvider._read_number(root / "capacity")
        temperature = LocalResourceProvider._temperature(root / "temp")
        try:
            status = (root / "status").read_text(encoding="utf-8").strip().casefold()
            charging = status in {"charging", "full"}
        except OSError:
            charging = None
        return percentage, temperature, charging

    @staticmethod
    def _thermal() -> float | None:
        values = [
            value
            for path in Path("/sys/class/thermal").glob("thermal_zone*/temp")
            if (value := LocalResourceProvider._temperature(path)) is not None
        ]
        return max(values) if values else None

    def snapshot(self) -> ResourceSnapshot:
        free_ram, total_ram = self._memory()
        try:
            usage = shutil.disk_usage(self.storage_path)
            free_storage = usage.free // (1024 * 1024)
            total_storage = usage.total // (1024 * 1024)
        except OSError:
            free_storage = total_storage = None
        battery, battery_temp, charging = self._battery()
        return ResourceSnapshot(
            free_ram_mib=free_ram,
            total_ram_mib=total_ram,
            free_storage_mib=free_storage,
            total_storage_mib=total_storage,
            battery_pct=battery,
            battery_temperature_c=battery_temp,
            charging=charging,
            thermal_temperature_c=self._thermal(),
        )
