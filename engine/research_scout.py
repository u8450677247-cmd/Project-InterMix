"""Idle-only, read-only research scout for workspace dependencies and remotes.

Findings are documentary leads, never executable instructions.  Official
repository releases rank above verified registry metadata, while community
issues are explicitly labeled as unverified leads.
"""

from __future__ import annotations

import json
import os
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from workspace_state import (
    META_DIR,
    WORKSPACE_DIR,
    atomic_write_json,
    atomic_write_text,
    read_json,
    utc_now,
)


POLICY_FILE = META_DIR / "research_policy.json"
STATE_FILE = META_DIR / "research_state.json"
FINDINGS_FILE = META_DIR / "research_findings.json"
FINDINGS_MARKDOWN = META_DIR / "RESEARCH_FINDINGS.md"

DEFAULT_POLICY = {
    "enabled": True,
    "run_condition": "app_open_and_idle",
    "idle_seconds": 60,
    "minimum_interval_seconds": 21600,
    "maximum_requests_per_scan": 6,
    "source_policy": {
        "official_repositories_and_documentation": True,
        "verified_registries_and_releases": True,
        "community_issues_and_discussions": True,
        "general_web_results": False,
    },
}

_SCOUT_LOCK = threading.RLock()
_CANCEL_EVENT = threading.Event()


def ensure_policy() -> dict[str, Any]:
    policy = read_json(POLICY_FILE, {})
    if not isinstance(policy, dict):
        policy = {}
    merged = {**DEFAULT_POLICY, **policy}
    merged["source_policy"] = {
        **DEFAULT_POLICY["source_policy"],
        **(policy.get("source_policy") if isinstance(policy.get("source_policy"), dict) else {}),
    }
    if merged != policy:
        atomic_write_json(POLICY_FILE, merged)
    return merged


def _github_repository() -> str:
    config = WORKSPACE_DIR / ".git" / "config"
    try:
        text = config.read_text(encoding="utf-8")
    except OSError:
        return ""
    urls = re.findall(r"(?m)^\s*url\s*=\s*(\S+)\s*$", text)
    for url in urls:
        match = re.search(
            r"github\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?$",
            url,
        )
        if match:
            return f"{match.group(1)}/{match.group(2)}"
    return ""


def _requirements() -> list[tuple[str, str]]:
    candidates = [WORKSPACE_DIR / "requirements.txt"]
    results: list[tuple[str, str]] = []
    seen: set[str] = set()
    for path in candidates:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        for line in lines:
            clean = line.split("#", 1)[0].strip()
            match = re.match(r"([A-Za-z0-9_.-]+)\s*(?:==\s*([^;\s]+))?", clean)
            if not match:
                continue
            name = match.group(1)
            key = name.casefold().replace("_", "-")
            if key in seen:
                continue
            seen.add(key)
            results.append((name, match.group(2) or ""))
    return results[:20]


def discovered_sources() -> dict[str, Any]:
    return {
        "github_repository": _github_repository(),
        "python_requirements": _requirements(),
    }


def _fetch_json(url: str, state: dict[str, Any]) -> tuple[Any, dict[str, Any], bool]:
    cache = dict(state.get("http_cache") or {})
    cached = dict(cache.get(url) or {})
    headers = {
        "Accept": "application/vnd.github+json, application/json",
        "User-Agent": "Project-Intermix-Research-Scout/1.2",
    }
    if cached.get("etag"):
        headers["If-None-Match"] = str(cached["etag"])
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            data = json.loads(response.read().decode("utf-8", "replace"))
            cache[url] = {
                "etag": response.headers.get("ETag", ""),
                "checked_at": utc_now(),
                "data": data,
            }
            state["http_cache"] = cache
            return data, state, True
    except urllib.error.HTTPError as exc:
        if exc.code == 304 and "data" in cached:
            cached["checked_at"] = utc_now()
            cache[url] = cached
            state["http_cache"] = cache
            return cached["data"], state, False
        raise


def _release_findings(repository: str, data: Any) -> list[dict[str, Any]]:
    if not isinstance(data, list):
        return []
    findings: list[dict[str, Any]] = []
    for item in data[:5]:
        if not isinstance(item, dict):
            continue
        findings.append(
            {
                "tier": "official_release",
                "repository": repository,
                "title": str(item.get("name") or item.get("tag_name") or "Release")[:300],
                "version": str(item.get("tag_name") or "")[:120],
                "published_at": str(item.get("published_at") or ""),
                "url": str(item.get("html_url") or ""),
                "summary": str(item.get("body") or "")[:1200],
                "authority": "official repository release metadata",
            }
        )
    return findings


def _issue_findings(repository: str, data: Any) -> list[dict[str, Any]]:
    if not isinstance(data, list):
        return []
    findings: list[dict[str, Any]] = []
    for item in data[:10]:
        if not isinstance(item, dict) or item.get("pull_request"):
            continue
        findings.append(
            {
                "tier": "community_lead",
                "repository": repository,
                "title": str(item.get("title") or "Issue")[:300],
                "number": item.get("number"),
                "updated_at": str(item.get("updated_at") or ""),
                "url": str(item.get("html_url") or ""),
                "summary": str(item.get("body") or "")[:1200],
                "authority": "unverified community report; corroboration required",
            }
        )
    return findings


def _registry_finding(name: str, pinned: str, data: Any) -> dict[str, Any] | None:
    if not isinstance(data, dict) or not isinstance(data.get("info"), dict):
        return None
    info = data["info"]
    latest = str(info.get("version") or "")
    if not latest:
        return None
    return {
        "tier": "verified_registry",
        "package": name,
        "installed_or_pinned": pinned,
        "latest": latest,
        "title": f"{name} registry release {latest}",
        "url": str(info.get("project_url") or info.get("package_url") or ""),
        "summary": str(info.get("summary") or "")[:500],
        "authority": "verified package registry metadata",
    }


def _finding_key(item: dict[str, Any]) -> str:
    return "|".join(
        str(item.get(key, ""))
        for key in ("tier", "repository", "package", "number", "version", "latest", "url")
    )


def _render_findings(findings: list[dict[str, Any]], errors: list[str]) -> str:
    lines = [
        "# Research Scout Findings",
        "",
        "> Read-only leads collected while Project Intermix was open and idle. Nothing here was executed.",
        "",
        f"Updated: `{utc_now()}`",
        "",
    ]
    if not findings:
        lines.extend(["No configured or discoverable sources produced findings.", ""])
    for item in findings:
        lines.extend(
            [
                f"## {item.get('title', 'Finding')}",
                "",
                f"- Trust tier: `{item.get('tier', 'unknown')}`",
                f"- Authority: {item.get('authority', 'unclassified')}",
                f"- Source: {item.get('url', '') or 'not exposed'}",
                "",
                str(item.get("summary", "")).strip() or "No summary supplied.",
                "",
            ]
        )
    if errors:
        lines.extend(["## Provider Notes", ""])
        lines.extend(f"- {error}" for error in errors[-10:])
    return "\n".join(lines).rstrip() + "\n"


def scan_research() -> dict[str, Any]:
    """Perform one bounded, read-only scan. Safe to call from a worker thread."""
    with _SCOUT_LOCK:
        _CANCEL_EVENT.clear()
        policy = ensure_policy()
        if not policy.get("enabled", True):
            return {"status": "disabled", "findings": 0, "errors": []}
        source_policy = dict(policy.get("source_policy") or {})
        maximum = max(1, min(int(policy.get("maximum_requests_per_scan", 6)), 12))
        sources = discovered_sources()
        state = read_json(STATE_FILE, {})
        if not isinstance(state, dict):
            state = {}
        findings: list[dict[str, Any]] = []
        errors: list[str] = []
        requests_used = 0
        repository = str(sources.get("github_repository") or "")

        if repository and source_policy.get("official_repositories_and_documentation"):
            url = f"https://api.github.com/repos/{repository}/releases?per_page=5"
            try:
                data, state, _ = _fetch_json(url, state)
                findings.extend(_release_findings(repository, data))
            except Exception as exc:
                errors.append(f"GitHub releases: {type(exc).__name__}: {exc}")
            requests_used += 1

        if (
            repository
            and requests_used < maximum
            and source_policy.get("community_issues_and_discussions")
            and not _CANCEL_EVENT.is_set()
        ):
            url = (
                f"https://api.github.com/repos/{repository}/issues"
                "?state=open&sort=updated&direction=desc&per_page=10"
            )
            try:
                data, state, _ = _fetch_json(url, state)
                findings.extend(_issue_findings(repository, data))
            except Exception as exc:
                errors.append(f"GitHub issues: {type(exc).__name__}: {exc}")
            requests_used += 1

        if source_policy.get("verified_registries_and_releases"):
            for name, pinned in sources.get("python_requirements") or []:
                if requests_used >= maximum or _CANCEL_EVENT.is_set():
                    break
                url = f"https://pypi.org/pypi/{urllib.parse.quote(name)}/json"
                try:
                    data, state, _ = _fetch_json(url, state)
                    finding = _registry_finding(name, pinned, data)
                    if finding:
                        findings.append(finding)
                except Exception as exc:
                    errors.append(f"Registry {name}: {type(exc).__name__}: {exc}")
                requests_used += 1

        unique: dict[str, dict[str, Any]] = {}
        for finding in findings:
            finding["retrieved_at"] = utc_now()
            finding["execution_allowed"] = False
            unique[_finding_key(finding)] = finding
        findings = list(unique.values())[:60]
        if _CANCEL_EVENT.is_set():
            return {
                "status": "cancelled",
                "findings": 0,
                "errors": errors,
                "requests_used": requests_used,
                "sources": sources,
            }
        state.update(
            {
                "last_scan": utc_now(),
                "last_errors": errors[-20:],
                "requests_used": requests_used,
                "sources": sources,
            }
        )
        atomic_write_json(STATE_FILE, state)
        atomic_write_json(FINDINGS_FILE, findings)
        atomic_write_text(FINDINGS_MARKDOWN, _render_findings(findings, errors))
        return {
            "status": "cancelled" if _CANCEL_EVENT.is_set() else "complete",
            "findings": len(findings),
            "errors": errors,
            "requests_used": requests_used,
            "sources": sources,
        }


def cancel_research() -> None:
    _CANCEL_EVENT.set()


def research_status() -> dict[str, Any]:
    policy = ensure_policy()
    state = read_json(STATE_FILE, {})
    findings = read_json(FINDINGS_FILE, [])
    return {
        "policy": policy,
        "last_scan": state.get("last_scan") if isinstance(state, dict) else None,
        "requests_used": state.get("requests_used", 0) if isinstance(state, dict) else 0,
        "last_errors": state.get("last_errors", []) if isinstance(state, dict) else [],
        "discovered_sources": discovered_sources(),
        "findings": len(findings) if isinstance(findings, list) else 0,
        "document": str(FINDINGS_MARKDOWN),
    }


def research_findings(limit: int = 20) -> list[dict[str, Any]]:
    """Return recent read-only findings for local inspection."""
    findings = read_json(FINDINGS_FILE, [])
    if not isinstance(findings, list):
        return []
    safe_limit = max(1, min(int(limit), 60))
    return [item for item in findings[-safe_limit:] if isinstance(item, dict)]


__all__ = [
    "DEFAULT_POLICY",
    "cancel_research",
    "discovered_sources",
    "ensure_policy",
    "research_findings",
    "research_status",
    "scan_research",
]
