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
    battery_temperature_sensor_type: str | None = None
    battery_temperature_sensor_zone: str | None = None
    thermal_sensor_type: str | None = None
    thermal_sensor_zone: str | None = None

    def as_mapping(self) -> dict[str, object]:
        return asdict(self)


class ResourceProvider(Protocol):
    def snapshot(self) -> ResourceSnapshot: ...


@dataclass(frozen=True)
class ThermalReading:
    temperature_c: float
    sensor_type: str
    zone: str


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
    def _is_temperature_zone(sensor_type: str) -> bool:
        """Reject Android control channels that share the thermal-zone ABI.

        Qualcomm kernels expose battery state-of-charge, current and voltage
        control inputs beside real temperature sensors. Their raw values look
        temperature-like after unit normalization (for example, ``vbat=3737``
        became 37.37 C), so provenance must be checked before conversion.
        """

        normalized = sensor_type.strip().casefold().replace("_", "-")
        tokens = {token for token in normalized.split("-") if token}
        if normalized in {"soc", "socd"}:
            return False
        return tokens.isdisjoint({"ibat", "vbat", "vph", "bcl", "voltage", "current"})

    @staticmethod
    def _is_battery_temperature_zone(sensor_type: str) -> bool:
        normalized = sensor_type.strip().casefold().replace("_", "-")
        tokens = {token for token in normalized.split("-") if token}
        return normalized in {"battery", "bms"} or bool(
            tokens.intersection({"battery", "batt"})
            and tokens.intersection({"temp", "therm", "thermal"})
        )

    @classmethod
    def _thermal_readings(cls, root: Path | None = None) -> list[ThermalReading]:
        root = root or Path("/sys/class/thermal")
        readings: list[ThermalReading] = []
        for path in root.glob("thermal_zone*/temp"):
            try:
                sensor_type = (path.parent / "type").read_text(encoding="utf-8").strip()
            except OSError:
                sensor_type = "unknown"
            if not cls._is_temperature_zone(sensor_type):
                continue
            temperature = cls._temperature(path)
            if temperature is not None:
                readings.append(
                    ThermalReading(
                        temperature_c=temperature,
                        sensor_type=sensor_type or "unknown",
                        zone=path.parent.name,
                    )
                )
        return readings

    @classmethod
    def _thermal(cls, root: Path | None = None) -> ThermalReading | None:
        readings = [
            reading
            for reading in cls._thermal_readings(root)
            if not cls._is_battery_temperature_zone(reading.sensor_type)
        ]
        return max(readings, key=lambda reading: reading.temperature_c) if readings else None

    @classmethod
    def _battery_thermal(cls, root: Path | None = None) -> ThermalReading | None:
        readings = [
            reading
            for reading in cls._thermal_readings(root)
            if cls._is_battery_temperature_zone(reading.sensor_type)
        ]
        return max(readings, key=lambda reading: reading.temperature_c) if readings else None

    def snapshot(self) -> ResourceSnapshot:
        free_ram, total_ram = self._memory()
        try:
            usage = shutil.disk_usage(self.storage_path)
            free_storage = usage.free // (1024 * 1024)
            total_storage = usage.total // (1024 * 1024)
        except OSError:
            free_storage = total_storage = None
        battery, battery_temp, charging = self._battery()
        thermal_readings = self._thermal_readings()
        battery_thermal = max(
            (
                reading
                for reading in thermal_readings
                if self._is_battery_temperature_zone(reading.sensor_type)
            ),
            key=lambda reading: reading.temperature_c,
            default=None,
        )
        thermal = max(
            (
                reading
                for reading in thermal_readings
                if not self._is_battery_temperature_zone(reading.sensor_type)
            ),
            key=lambda reading: reading.temperature_c,
            default=None,
        )
        battery_sensor_type = "power_supply" if battery_temp is not None else None
        battery_sensor_zone = "battery/temp" if battery_temp is not None else None
        if battery_temp is None and battery_thermal is not None:
            battery_temp = battery_thermal.temperature_c
            battery_sensor_type = battery_thermal.sensor_type
            battery_sensor_zone = battery_thermal.zone
        return ResourceSnapshot(
            free_ram_mib=free_ram,
            total_ram_mib=total_ram,
            free_storage_mib=free_storage,
            total_storage_mib=total_storage,
            battery_pct=battery,
            battery_temperature_c=battery_temp,
            charging=charging,
            thermal_temperature_c=thermal.temperature_c if thermal else None,
            battery_temperature_sensor_type=battery_sensor_type,
            battery_temperature_sensor_zone=battery_sensor_zone,
            thermal_sensor_type=thermal.sensor_type if thermal else None,
            thermal_sensor_zone=thermal.zone if thermal else None,
        )
