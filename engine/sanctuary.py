"""Fail-closed Sanctuary capability contract for pre-APK runtimes."""

from __future__ import annotations

from typing import Any

from memory_store import MemoryStore


SANCTUARY_MODE_KEY = "sanctuary_mode"


def sanctuary_status(store: MemoryStore) -> dict[str, Any]:
    requested = store.get_setting(SANCTUARY_MODE_KEY, "off").casefold()
    return {
        "requested_mode": requested if requested in {"off", "encrypted"} else "off",
        "available": False,
        "active": False,
        "encryption_backend": "none",
        "biometric_unlock": False,
        "raw_conversation_capture": False,
        "reason": (
            "Encrypted Sanctuary is reserved for the APK-backed Android Keystore and "
            "biometric implementation. Termux SQLite is not an encrypted vault."
        ),
    }


def request_sanctuary(store: MemoryStore, enabled: bool) -> dict[str, Any]:
    if not enabled:
        store.set_setting(SANCTUARY_MODE_KEY, "off")
        return sanctuary_status(store)
    # Fail closed: do not set an enabled flag or begin extra raw capture until
    # an authenticated encryption backend can prove that it is active.
    store.set_setting(SANCTUARY_MODE_KEY, "off")
    return sanctuary_status(store)


__all__ = ["SANCTUARY_MODE_KEY", "request_sanctuary", "sanctuary_status"]
