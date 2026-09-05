"""Zero-dependency web grounding with relevance and freshness gates."""

from __future__ import annotations

import html
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from typing import Any, Sequence

from claim_contracts import (
    classify_claim,
    extract_versions,
    is_prerelease,
    item_entails_claim,
    verified_fact_from_item,
    version_sort_key,
)
from provider_vault import load_provider_environment


load_provider_environment()


USER_AGENT = "Mozilla/5.0 (Android; Project Intermix/1.4.1-alpha.1) AppleWebKit/537.36"

STOPWORDS = {
    "a", "about", "an", "and", "are", "for", "from", "how", "in", "is",
    "me", "of", "on", "or", "the", "to", "what", "when", "where", "which",
    "who", "with",
}
VOLATILE_TERMS = {
    "available", "availability", "breaking", "ceo", "current", "currently",
    "deadline", "election", "latest", "law", "market", "newest", "news", "now",
    "president", "price", "recent", "regulation", "release", "released", "schedule",
    "score", "security", "stock", "today", "update", "updated", "updates", "version",
    "weather",
}
QUALIFIER_TERMS = {"fold", "max", "mini", "plus", "pro", "ultra", "xl"}
INTENT_GROUPS = {
    "update": {
        "build", "firmware", "ota", "patch", "release", "released", "security",
        "software", "update", "updated", "updates", "upgrade", "version",
    },
    "price": {"buy", "cost", "deal", "eur", "price", "pricing", "sale", "usd"},
    "score": {"defeated", "final", "game", "lost", "match", "score", "won"},
    "weather": {"celsius", "forecast", "rain", "temperature", "weather", "wind"},
    "availability": {"available", "availability", "launch", "released", "shipping", "stock"},
    "news": {"announced", "breaking", "news", "reported", "today"},
}

_PROVIDER_LOCK = threading.Lock()
_PROVIDER_STATE: dict[str, dict[str, float | int | str]] = {}
_REPORT_LOCK = threading.Lock()
_LAST_SEARCH_REPORT: dict[str, Any] = {}


def _publish_search_report(report: dict[str, Any]) -> None:
    with _REPORT_LOCK:
        _LAST_SEARCH_REPORT.clear()
        _LAST_SEARCH_REPORT.update(report)


def last_search_report() -> dict[str, Any]:
    """Return non-secret diagnostics for the most recently completed search."""
    with _REPORT_LOCK:
        return dict(_LAST_SEARCH_REPORT)


def _safe_error(error: Exception) -> str:
    """Return provider diagnostics without ever echoing credentials."""
    message = f"{type(error).__name__}: {error}"
    message = re.sub(
        r"(?i)(api[_-]?key|x-api-key|token|authorization)=?[^&\s]+",
        r"\1=[redacted]",
        message,
    )
    return message[:240]


def _fetch(
    url: str,
    timeout: int = 8,
    *,
    headers: dict[str, str] | None = None,
    data: bytes | None = None,
) -> str:
    request_headers = {
        "User-Agent": USER_AGENT,
        "Accept-Language": "en-US,en;q=0.8",
    }
    request_headers.update(headers or {})
    request = urllib.request.Request(
        url,
        headers=request_headers,
        data=data,
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def _provider_key(name: str) -> str:
    return "duckduckgo" if name.startswith("_duckduckgo") else name.removeprefix("_")


def _provider_ready(name: str) -> bool:
    key = _provider_key(name)
    with _PROVIDER_LOCK:
        state = _PROVIDER_STATE.get(key, {})
        return float(state.get("cooldown_until", 0.0)) <= time.monotonic()


def _provider_succeeded(name: str) -> None:
    key = _provider_key(name)
    with _PROVIDER_LOCK:
        _PROVIDER_STATE[key] = {
            "failures": 0,
            "cooldown_until": 0.0,
            "last_error": "",
        }


def _provider_failed(name: str, error: Exception) -> None:
    key = _provider_key(name)
    with _PROVIDER_LOCK:
        previous = _PROVIDER_STATE.get(key, {})
        failures = min(6, int(previous.get("failures", 0)) + 1)
        status = getattr(error, "code", None)
        base_seconds = 180 if status in {403, 429} else 30
        cooldown = min(1800, base_seconds * (2 ** (failures - 1)))
        _PROVIDER_STATE[key] = {
            "failures": failures,
            "cooldown_until": time.monotonic() + cooldown,
            "last_error": _safe_error(error),
        }


def provider_status() -> list[dict[str, Any]]:
    now = time.monotonic()
    configured = {
        "brave": bool(os.environ.get("BRAVE_SEARCH_API_KEY")),
        "tavily": bool(os.environ.get("TAVILY_API_KEY")),
        "exa": bool(os.environ.get("EXA_API_KEY")),
        "searxng": bool(os.environ.get("SEARXNG_URL")),
        "serpapi": bool(os.environ.get("SERPAPI_API_KEY")),
        "tinyfish": bool(os.environ.get("TINYFISH_API_KEY")),
        "python_org": True,
        "github": True,
        "pypi": True,
        "crossref": True,
        "arxiv": True,
        "google_news": True,
        "bing_news": True,
        "wikipedia": True,
        "duckduckgo": True,
    }
    names = tuple(configured)
    with _PROVIDER_LOCK:
        snapshot = {name: dict(_PROVIDER_STATE.get(name, {})) for name in names}
    return [
        {
            "provider": name.replace("_", " "),
            "configured": configured[name],
            "failures": int(snapshot[name].get("failures", 0)),
            "cooldown_seconds": max(0, round(float(snapshot[name].get("cooldown_until", 0.0)) - now)),
            "last_error": str(snapshot[name].get("last_error", "")),
        }
        for name in names
    ]


def _raise_if_blocked(page: str) -> None:
    lowered = page.casefold()
    if any(
        marker in lowered
        for marker in ("anomaly-modal", "captcha", "too many requests", "unusual traffic")
    ):
        raise RuntimeError("provider returned a rate-limit or challenge page")


def _clean(fragment: str) -> str:
    text = re.sub(r"<[^>]+>", " ", fragment)
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", text.casefold()))


def _is_volatile(query: str) -> bool:
    return bool(_tokens(query) & VOLATILE_TERMS)


def _query_intents(query_tokens: set[str]) -> list[set[str]]:
    intents: list[set[str]] = []
    if query_tokens & INTENT_GROUPS["update"]:
        intents.append(INTENT_GROUPS["update"])
    if query_tokens & INTENT_GROUPS["price"]:
        intents.append(INTENT_GROUPS["price"])
    if query_tokens & INTENT_GROUPS["score"]:
        intents.append(INTENT_GROUPS["score"])
    if query_tokens & INTENT_GROUPS["weather"]:
        intents.append(INTENT_GROUPS["weather"])
    if query_tokens & {"available", "availability", "stock"}:
        intents.append(INTENT_GROUPS["availability"])
    if query_tokens & {"breaking", "news", "today"}:
        intents.append(INTENT_GROUPS["news"])
    return intents


def _unwrap_ddg(url: str) -> str:
    absolute = html.unescape(url)
    if absolute.startswith("//"):
        absolute = "https:" + absolute
    parsed = urllib.parse.urlparse(absolute)
    query = urllib.parse.parse_qs(parsed.query)
    if "uddg" in query and query["uddg"]:
        return urllib.parse.unquote(query["uddg"][0])
    return absolute


def _duckduckgo_html(query: str, max_results: int) -> list[dict[str, str]]:
    url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote_plus(query)
    page = _fetch(url)
    _raise_if_blocked(page)
    links = re.findall(
        r'<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>',
        page,
        flags=re.IGNORECASE | re.DOTALL,
    )
    snippets = re.findall(
        r'<(?:a|div)[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div)>',
        page,
        flags=re.IGNORECASE | re.DOTALL,
    )
    results: list[dict[str, str]] = []
    for index, (href, title) in enumerate(links[: max_results * 2]):
        results.append(
            {
                "title": _clean(title),
                "url": _unwrap_ddg(href),
                "snippet": _clean(snippets[index]) if index < len(snippets) else "",
                "provider": "DuckDuckGo HTML",
                "published": "",
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _duckduckgo_lite(query: str, max_results: int) -> list[dict[str, str]]:
    url = "https://lite.duckduckgo.com/lite/?q=" + urllib.parse.quote_plus(query)
    page = _fetch(url)
    _raise_if_blocked(page)
    links = re.findall(
        r'<a[^>]+(?:class="result-link"|rel="nofollow")[^>]+href="([^"]+)"[^>]*>(.*?)</a>',
        page,
        flags=re.IGNORECASE | re.DOTALL,
    )
    snippets = re.findall(
        r'<td[^>]+class="result-snippet"[^>]*>(.*?)</td>',
        page,
        flags=re.IGNORECASE | re.DOTALL,
    )
    results: list[dict[str, str]] = []
    for index, (href, title) in enumerate(links[: max_results * 2]):
        results.append(
            {
                "title": _clean(title),
                "url": _unwrap_ddg(href),
                "snippet": _clean(snippets[index]) if index < len(snippets) else "",
                "provider": "DuckDuckGo Lite",
                "published": "",
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _rss_results(url: str, provider: str, max_results: int) -> list[dict[str, str]]:
    root = ET.fromstring(_fetch(url))
    results: list[dict[str, str]] = []
    for item in root.findall(".//item")[: max_results * 2]:
        title = _clean(item.findtext("title", default=""))
        link = html.unescape(item.findtext("link", default="").strip())
        description = _clean(item.findtext("description", default=""))
        published = _clean(item.findtext("pubDate", default=""))
        if title and link:
            results.append(
                {
                    "title": title,
                    "url": link,
                    "snippet": description,
                    "provider": provider,
                    "published": published,
                }
            )
    return results


def _google_news(query: str, max_results: int) -> list[dict[str, str]]:
    encoded = urllib.parse.quote_plus(query)
    return _rss_results(
        f"https://news.google.com/rss/search?q={encoded}&hl=en-US&gl=US&ceid=US:en",
        "Google News RSS",
        max_results,
    )


def _bing_news(query: str, max_results: int) -> list[dict[str, str]]:
    encoded = urllib.parse.quote_plus(query)
    return _rss_results(
        f"https://www.bing.com/news/search?q={encoded}&format=rss",
        "Bing News RSS",
        max_results,
    )


def _wikipedia(query: str, max_results: int) -> list[dict[str, str]]:
    params = urllib.parse.urlencode(
        {
            "action": "query",
            "list": "search",
            "srsearch": query,
            "srlimit": max_results * 2,
            "format": "json",
            "utf8": 1,
        }
    )
    raw = _fetch("https://en.wikipedia.org/w/api.php?" + params)
    payload = json.loads(raw)
    results: list[dict[str, str]] = []
    for item in payload.get("query", {}).get("search", [])[: max_results * 2]:
        title = str(item.get("title", "")).strip()
        if not title:
            continue
        results.append(
            {
                "title": title,
                "url": "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
                "snippet": _clean(str(item.get("snippet", ""))),
                "provider": "Wikipedia",
                "published": "",
            }
        )
    return results


def _brave(query: str, max_results: int) -> list[dict[str, str]]:
    key = os.environ.get("BRAVE_SEARCH_API_KEY", "").strip()
    if not key:
        return []
    params = urllib.parse.urlencode({"q": query, "count": min(max_results * 2, 20)})
    payload = json.loads(
        _fetch(
            "https://api.search.brave.com/res/v1/web/search?" + params,
            headers={"Accept": "application/json", "X-Subscription-Token": key},
        )
    )
    results: list[dict[str, str]] = []
    for item in payload.get("web", {}).get("results", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("url") or "").strip(),
                "snippet": _clean(str(item.get("description") or "")),
                "provider": "Brave Search API",
                "published": str(item.get("page_age") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _tavily(query: str, max_results: int) -> list[dict[str, str]]:
    key = os.environ.get("TAVILY_API_KEY", "").strip()
    if not key:
        return []
    request_data = json.dumps(
        {
            "query": query,
            "search_depth": "basic",
            "max_results": min(max_results * 2, 20),
            "include_answer": False,
            "include_raw_content": False,
        }
    ).encode("utf-8")
    payload = json.loads(
        _fetch(
            "https://api.tavily.com/search",
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
            data=request_data,
        )
    )
    results: list[dict[str, str]] = []
    for item in payload.get("results", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("url") or "").strip(),
                "snippet": re.sub(r"\s+", " ", str(item.get("content") or "")).strip(),
                "provider": "Tavily Search API",
                "published": str(item.get("published_date") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _exa(query: str, max_results: int) -> list[dict[str, str]]:
    key = os.environ.get("EXA_API_KEY", "").strip()
    if not key:
        return []
    request_data = json.dumps(
        {
            "query": query,
            "type": "auto",
            "numResults": min(max_results * 2, 10),
            "contents": {"highlights": {"maxCharacters": 700}},
        }
    ).encode("utf-8")
    payload = json.loads(
        _fetch(
            "https://api.exa.ai/search",
            headers={"Content-Type": "application/json", "x-api-key": key},
            data=request_data,
        )
    )
    results: list[dict[str, str]] = []
    for item in payload.get("results", [])[: max_results * 2]:
        highlights = item.get("highlights") or []
        snippet = " ".join(str(part) for part in highlights[:3])
        if not snippet:
            snippet = str(item.get("text") or "")[:700]
        results.append(
            {
                "title": str(item.get("title") or item.get("url") or "").strip(),
                "url": str(item.get("url") or "").strip(),
                "snippet": re.sub(r"\s+", " ", snippet).strip(),
                "provider": "Exa Search API",
                "published": str(item.get("publishedDate") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _searxng(query: str, max_results: int) -> list[dict[str, str]]:
    base = os.environ.get("SEARXNG_URL", "").strip().rstrip("/")
    if not base:
        return []
    params = urllib.parse.urlencode({"q": query, "format": "json", "language": "en"})
    payload = json.loads(_fetch(base + "/search?" + params, headers={"Accept": "application/json"}))
    results: list[dict[str, str]] = []
    for item in payload.get("results", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("url") or "").strip(),
                "snippet": re.sub(r"\s+", " ", str(item.get("content") or "")).strip(),
                "provider": "SearXNG JSON",
                "published": str(item.get("publishedDate") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _serpapi(query: str, max_results: int) -> list[dict[str, str]]:
    """Use the sanctioned Google Search API route when configured locally."""
    key = os.environ.get("SERPAPI_API_KEY", "").strip()
    if not key:
        return []
    params = urllib.parse.urlencode(
        {
            "engine": "google",
            "q": query,
            "api_key": key,
            "hl": "en",
            "safe": "active",
            "device": "mobile",
            "num": min(max_results * 2, 10),
            "output": "json",
        }
    )
    payload = json.loads(_fetch("https://serpapi.com/search?" + params))
    if payload.get("error"):
        raise RuntimeError("SerpAPI request was rejected by the provider")
    status = str(payload.get("search_metadata", {}).get("status") or "")
    if status and status.casefold() not in {"success", "cached"}:
        raise RuntimeError(f"SerpAPI returned status {status[:40]}")
    results: list[dict[str, str]] = []
    for item in payload.get("organic_results", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("link") or "").strip(),
                "snippet": re.sub(r"\s+", " ", str(item.get("snippet") or "")).strip(),
                "provider": "SerpAPI Google",
                "published": str(item.get("date") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _tinyfish(query: str, max_results: int) -> list[dict[str, str]]:
    """Use TinyFish's structured Search API, never its metered browser agent."""
    key = os.environ.get("TINYFISH_API_KEY", "").strip()
    if not key:
        return []
    parameters: dict[str, Any] = {
        "query": query,
        "purpose": "Retrieve current source evidence for a locally verified answer",
        "language": os.environ.get("INTERMIX_SEARCH_LANGUAGE", "en"),
        "location": os.environ.get("INTERMIX_SEARCH_LOCATION", "US"),
    }
    words = _tokens(query)
    if words & {"news", "breaking", "headline", "headlines"}:
        parameters["domain_type"] = "news"
        parameters["recency_minutes"] = 60 * 24 * 30
    payload = json.loads(
        _fetch(
            "https://api.search.tinyfish.ai?" + urllib.parse.urlencode(parameters),
            headers={"Accept": "application/json", "X-API-Key": key},
        )
    )
    results: list[dict[str, str]] = []
    for item in payload.get("results", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("url") or "").strip(),
                "snippet": re.sub(r"\s+", " ", str(item.get("snippet") or "")).strip(),
                "provider": "TinyFish Search API",
                "published": str(item.get("date") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _python_org(query: str, max_results: int) -> list[dict[str, str]]:
    """Resolve exact Python release claims from Python's official downloads page."""
    contract = classify_claim(query)
    if (
        not contract.exact
        or contract.subject.casefold() != "python"
        or not contract.kind.startswith("latest_")
    ):
        return []
    visible = _clean(_fetch("https://www.python.org/downloads/"))
    versions = extract_versions(visible)
    candidates = [
        value
        for value in versions
        if is_prerelease(value) == (contract.channel == "prerelease")
    ]
    if not candidates:
        return []
    value = max(candidates, key=version_sort_key)
    channel_label = "pre-release" if contract.channel == "prerelease" else "stable"
    return [
        {
            "title": f"Python {value}",
            "url": "https://www.python.org/downloads/",
            "snippet": f"Python.org lists Python {value} as the latest {channel_label} release.",
            "provider": "Python.org Official",
            "published": "",
            "claim_type": contract.kind,
            "verified_value": value,
            "channel": contract.channel,
            "authority": "official",
            "retrieved_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        }
    ]


def _github(query: str, max_results: int) -> list[dict[str, str]]:
    params = urllib.parse.urlencode({"q": query, "per_page": min(max_results * 2, 10)})
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = json.loads(_fetch("https://api.github.com/search/issues?" + params, headers=headers))
    results: list[dict[str, str]] = []
    for item in payload.get("items", [])[: max_results * 2]:
        results.append(
            {
                "title": str(item.get("title") or "").strip(),
                "url": str(item.get("html_url") or "").strip(),
                "snippet": re.sub(r"\s+", " ", str(item.get("body") or ""))[:700].strip(),
                "provider": "GitHub REST",
                "published": str(item.get("updated_at") or ""),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _package_name(query: str) -> str:
    patterns = (
        r"\b(?:pypi|package|library)\s+([a-z0-9_.-]+)",
        r"\blatest\s+([a-z0-9_.-]+)\s+version\b",
        r"\b([a-z0-9_.-]+)\s+(?:pypi|package)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, query.casefold())
        if match:
            return match.group(1)
    return ""


def _pypi(query: str, max_results: int) -> list[dict[str, str]]:
    package = _package_name(query)
    if not package:
        return []
    payload = json.loads(_fetch("https://pypi.org/pypi/" + urllib.parse.quote(package) + "/json"))
    info = payload.get("info", {})
    version = str(info.get("version") or "").strip()
    if not version:
        return []
    return [
        {
            "title": f"{info.get('name') or package} {version}",
            "url": str(info.get("package_url") or f"https://pypi.org/project/{package}/"),
            "snippet": f"Current PyPI release: {version}. {info.get('summary') or ''}".strip(),
            "provider": "PyPI JSON API",
            "published": "",
        }
    ]


def _crossref(query: str, max_results: int) -> list[dict[str, str]]:
    params = urllib.parse.urlencode({"query": query, "rows": min(max_results * 2, 10), "select": "DOI,title,URL,published"})
    payload = json.loads(_fetch("https://api.crossref.org/works?" + params))
    results: list[dict[str, str]] = []
    for item in payload.get("message", {}).get("items", [])[: max_results * 2]:
        title_value = item.get("title") or []
        title = str(title_value[0] if title_value else item.get("DOI") or "").strip()
        results.append(
            {
                "title": title,
                "url": str(item.get("URL") or ""),
                "snippet": f"DOI: {item.get('DOI') or 'not supplied'}",
                "provider": "Crossref API",
                "published": json.dumps(item.get("published") or {}, separators=(",", ":")),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _arxiv(query: str, max_results: int) -> list[dict[str, str]]:
    params = urllib.parse.urlencode({"search_query": "all:" + query, "start": 0, "max_results": min(max_results * 2, 10)})
    root = ET.fromstring(_fetch("https://export.arxiv.org/api/query?" + params))
    namespace = {"atom": "http://www.w3.org/2005/Atom"}
    results: list[dict[str, str]] = []
    for entry in root.findall("atom:entry", namespace)[: max_results * 2]:
        results.append(
            {
                "title": re.sub(r"\s+", " ", entry.findtext("atom:title", default="", namespaces=namespace)).strip(),
                "url": entry.findtext("atom:id", default="", namespaces=namespace).strip(),
                "snippet": re.sub(r"\s+", " ", entry.findtext("atom:summary", default="", namespaces=namespace)).strip()[:900],
                "provider": "arXiv API",
                "published": entry.findtext("atom:published", default="", namespaces=namespace).strip(),
            }
        )
    return [item for item in results if item["title"] and item["url"]]


def _result_is_relevant(query: str, item: dict[str, str]) -> bool:
    query_tokens = _tokens(query)
    subject_tokens = query_tokens - STOPWORDS - VOLATILE_TERMS
    haystack = " ".join((item.get("title", ""), item.get("snippet", "")))
    evidence_tokens = _tokens(haystack)

    numeric_terms = {token for token in subject_tokens if token.isdigit()}
    if numeric_terms and not numeric_terms.issubset(evidence_tokens):
        return False
    qualifiers = subject_tokens & QUALIFIER_TERMS
    if qualifiers and not qualifiers.issubset(evidence_tokens):
        return False

    overlap = subject_tokens & evidence_tokens
    required_overlap = 1 if len(subject_tokens) <= 2 else 2
    if subject_tokens and len(overlap) < required_overlap:
        return False

    for intent_words in _query_intents(query_tokens):
        if not evidence_tokens & intent_words:
            return False

    if _is_volatile(query):
        provider = item.get("provider", "").casefold()
        host = urllib.parse.urlparse(item.get("url", "")).hostname or ""
        if "wikipedia" in provider or "wikipedia.org" in host.casefold():
            return False
        published = item.get("published", "").strip()
        if published and "news" in provider:
            try:
                try:
                    published_at = parsedate_to_datetime(published)
                except (TypeError, ValueError):
                    published_at = datetime.fromisoformat(published.replace("Z", "+00:00"))
                if published_at.tzinfo is None:
                    published_at = published_at.replace(tzinfo=timezone.utc)
                if published_at < datetime.now(timezone.utc) - timedelta(days=240):
                    return False
            except (TypeError, ValueError, OverflowError):
                pass

    return item_entails_claim(classify_claim(query), item)


def _format_results(query: str, results: list[dict[str, str]], max_results: int) -> str:
    lines = [f"Query: {query}"]
    for index, item in enumerate(results[:max_results], start=1):
        lines.extend(
            [
                f"[{index}] {item['title']}",
                f"Source: {item['url']}",
                f"Evidence: {item['snippet'] or 'No snippet supplied.'}",
                f"Provider: {item['provider']}",
            ]
        )
        if item.get("published"):
            lines.append(f"Published: {item['published']}")
        for key, label in (
            ("claim_type", "Claim-Type"),
            ("verified_value", "Verified-Value"),
            ("channel", "Channel"),
            ("authority", "Authority"),
            ("retrieved_at", "Retrieved"),
            ("release_date", "Release-Date"),
        ):
            if item.get(key):
                lines.append(f"{label}: {item[key]}")
    return "\n".join(lines)


def _parse_formatted_evidence(evidence: str) -> list[dict[str, str]]:
    results: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for line in evidence.splitlines():
        match = re.match(r"^\[(\d+)\]\s+(.+)$", line.strip())
        if match:
            if current:
                results.append(current)
            current = {
                "title": match.group(2).strip(),
                "url": "",
                "snippet": "",
                "provider": "",
                "published": "",
                "claim_type": "",
                "verified_value": "",
                "channel": "",
                "authority": "",
                "retrieved_at": "",
                "release_date": "",
            }
            continue
        if current is None:
            continue
        for prefix, key in (
            ("Source: ", "url"),
            ("Evidence: ", "snippet"),
            ("Provider: ", "provider"),
            ("Published: ", "published"),
            ("Claim-Type: ", "claim_type"),
            ("Verified-Value: ", "verified_value"),
            ("Channel: ", "channel"),
            ("Authority: ", "authority"),
            ("Retrieved: ", "retrieved_at"),
            ("Release-Date: ", "release_date"),
        ):
            if line.startswith(prefix):
                current[key] = line[len(prefix):].strip()
                break
    if current:
        results.append(current)
    return results


def validate_web_evidence(query: str, evidence: str) -> bool:
    """Return true only when cached/formatted evidence still passes the gate."""
    if not evidence or evidence.startswith(("No current web results", "No search query")):
        return False
    return any(_result_is_relevant(query, item) for item in _parse_formatted_evidence(evidence))


def extract_verified_fact(query: str, evidence: str) -> dict[str, Any] | None:
    """Return a controller-owned exact fact only when its evidence entails it."""
    contract = classify_claim(query)
    if not contract.exact:
        return None
    for source_id, item in enumerate(_parse_formatted_evidence(evidence), start=1):
        fact = verified_fact_from_item(contract, item, source_id=source_id)
        if fact:
            return fact.as_dict()
    return None


def evidence_source_ids(evidence: str) -> set[int]:
    return {
        int(match.group(1))
        for match in re.finditer(r"(?m)^\[(\d+)\]\s+", evidence)
    }


def _deduplicate_and_rank(
    query: str,
    candidates: list[dict[str, str]],
    max_results: int,
) -> list[dict[str, str]]:
    authority = {
        "brave search api": 0.92,
        "tavily search api": 0.90,
        "exa search api": 0.90,
        "searxng json": 0.78,
        "serpapi google": 0.90,
        "tinyfish search api": 0.90,
        "python.org official": 1.00,
        "github rest": 0.96,
        "pypi json api": 0.98,
        "crossref api": 0.98,
        "arxiv api": 0.96,
        "google news rss": 0.84,
        "bing news rss": 0.80,
        "wikipedia": 0.72,
        "duckduckgo html": 0.64,
        "duckduckgo lite": 0.62,
    }
    query_tokens = _tokens(query) - STOPWORDS - VOLATILE_TERMS
    best_by_url: dict[str, tuple[float, dict[str, str]]] = {}
    for item in candidates:
        url = str(item.get("url") or "").strip()
        if not url or not _result_is_relevant(query, item):
            continue
        parsed = urllib.parse.urlparse(url)
        normalized_url = urllib.parse.urlunparse(
            (parsed.scheme.casefold(), parsed.netloc.casefold(), parsed.path.rstrip("/"), "", parsed.query, "")
        )
        evidence_tokens = _tokens(item.get("title", "") + " " + item.get("snippet", ""))
        overlap = len(query_tokens & evidence_tokens) / max(1, len(query_tokens))
        provider_score = authority.get(item.get("provider", "").casefold(), 0.55)
        score = overlap * 0.62 + provider_score * 0.38
        previous = best_by_url.get(normalized_url)
        if previous is None or score > previous[0]:
            best_by_url[normalized_url] = (score, item)
    ranked = sorted(best_by_url.values(), key=lambda pair: pair[0], reverse=True)
    return [item for _, item in ranked[:max_results]]


def _configured_wave() -> list[tuple[str, Any]]:
    providers: list[tuple[str, Any]] = []
    if os.environ.get("BRAVE_SEARCH_API_KEY"):
        providers.append(("_brave", _brave))
    if os.environ.get("TAVILY_API_KEY"):
        providers.append(("_tavily", _tavily))
    if os.environ.get("EXA_API_KEY"):
        providers.append(("_exa", _exa))
    if os.environ.get("SEARXNG_URL"):
        providers.append(("_searxng", _searxng))
    if os.environ.get("SERPAPI_API_KEY"):
        providers.append(("_serpapi", _serpapi))
    if os.environ.get("TINYFISH_API_KEY"):
        providers.append(("_tinyfish", _tinyfish))
    return providers


def _specialized_wave(query: str) -> list[tuple[str, Any]]:
    words = _tokens(query)
    providers: list[tuple[str, Any]] = []
    if words & {
        "github", "repository", "repo", "issue", "issues", "commit",
        "litert", "textual", "termux", "kokoro", "onnx",
    }:
        providers.append(("_github", _github))
    if _package_name(query):
        providers.append(("_pypi", _pypi))
    if words & {"paper", "papers", "research", "study", "doi", "journal", "citation"}:
        providers.extend((("_crossref", _crossref), ("_arxiv", _arxiv)))
    return providers


def _run_wave(
    wave: list[tuple[str, Any]],
    query: str,
    max_results: int,
    request_slots: int,
) -> tuple[list[dict[str, str]], list[str], int, list[str]]:
    selected = [item for item in wave if _provider_ready(item[0])][: max(0, request_slots)]
    if not selected:
        cooling = [f"{name}: cooling down" for name, _ in wave if not _provider_ready(name)]
        return [], cooling, 0, []
    results: list[dict[str, str]] = []
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=min(3, len(selected))) as executor:
        futures = {
            executor.submit(provider, query, max_results): name
            for name, provider in selected
        }
        for future in as_completed(futures):
            name = futures[future]
            try:
                batch = future.result()
            except Exception as exc:
                _provider_failed(name, exc)
                errors.append(f"{name}: {_safe_error(exc)}")
            else:
                _provider_succeeded(name)
                results.extend(batch)
    return results, errors, len(selected), [_provider_key(name) for name, _ in selected]


def _distinct_hosts(results: Sequence[dict[str, str]]) -> set[str]:
    return {
        (urllib.parse.urlparse(str(item.get("url") or "")).hostname or "").casefold()
        for item in results
        if item.get("url")
    } - {""}


def _evidence_is_sufficient(
    query: str,
    results: Sequence[dict[str, str]],
    max_results: int,
) -> bool:
    """Decide whether another query can add material corroboration."""
    if not results:
        return False
    if classify_claim(query).exact:
        return True
    target = min(2, max_results)
    if len(results) < target:
        return False
    return len(_distinct_hosts(results)) >= target


def _adaptive_provider_wave(query: str, gate_query: str) -> list[tuple[str, Any]]:
    """Select diverse providers for one follow-up query without model judgment."""
    configured = _configured_wave()[:2]
    specialized = _specialized_wave(query + " " + gate_query)
    discovery = (
        [("_google_news", _google_news), ("_bing_news", _bing_news)]
        if _is_volatile(gate_query)
        else [("_wikipedia", _wikipedia)]
    )
    combined = configured + specialized + discovery
    unique: list[tuple[str, Any]] = []
    seen: set[str] = set()
    for name, provider in combined:
        key = _provider_key(name)
        if key in seen:
            continue
        seen.add(key)
        unique.append((name, provider))
    return unique


def _run_adaptive_round(
    queries: Sequence[str],
    gate_query: str,
    max_results: int,
    request_slots: int,
) -> tuple[list[dict[str, str]], list[str], int, list[str], list[str]]:
    """Run one bounded, parallel follow-up round across distinct query wordings."""
    jobs: list[tuple[str, Any, str]] = []
    cooling: list[str] = []
    clean_queries: list[str] = []
    query_waves: list[tuple[str, list[tuple[str, Any]]]] = []
    for raw_query in queries[:2]:
        query = re.sub(r"\s+", " ", str(raw_query)).strip()[:420]
        if not query or query in clean_queries:
            continue
        clean_queries.append(query)
        query_waves.append((query, _adaptive_provider_wave(query, gate_query)))

    # Round-robin scheduling guarantees that two distinct follow-up wordings
    # both run when at least two request slots remain.
    wave_index = 0
    while query_waves and len(jobs) < max(0, request_slots):
        made_progress = False
        for query, wave in query_waves:
            if wave_index >= len(wave):
                continue
            made_progress = True
            name, provider = wave[wave_index]
            if _provider_ready(name):
                jobs.append((name, provider, query))
                if len(jobs) >= max(0, request_slots):
                    break
            else:
                cooling.append(f"{name}: cooling down")
        if not made_progress:
            break
        wave_index += 1

    # Keep a keyless public fallback in the adaptive round. Only one DDG route
    # is scheduled so its shared circuit breaker remains meaningful.
    if clean_queries and len(jobs) < max(0, request_slots) and _provider_ready("_duckduckgo_html"):
        jobs.append(("_duckduckgo_html", _duckduckgo_html, clean_queries[0]))

    if not jobs:
        return [], cooling, 0, [], []

    results: list[dict[str, str]] = []
    errors = list(cooling)
    providers_used: list[str] = []
    queries_used: list[str] = []
    with ThreadPoolExecutor(max_workers=min(4, len(jobs))) as executor:
        futures = {
            executor.submit(provider, query, max_results): (name, query)
            for name, provider, query in jobs
        }
        for future in as_completed(futures):
            name, query = futures[future]
            providers_used.append(_provider_key(name))
            if query not in queries_used:
                queries_used.append(query)
            try:
                batch = future.result()
            except Exception as exc:
                _provider_failed(name, exc)
                errors.append(f"{name}: {_safe_error(exc)}")
            else:
                _provider_succeeded(name)
                results.extend(batch)
    return results, errors, len(jobs), queries_used, providers_used


def search_web(
    query: str,
    max_results: int = 4,
    *,
    evaluation_query: str | None = None,
    follow_up_queries: Sequence[str] | None = None,
) -> str:
    clean_query = query.strip()
    if not clean_query:
        _publish_search_report(
            {
                "status": "invalid",
                "primary_query": "",
                "follow_up_queries": [],
                "executed_queries": [],
                "requests_used": 0,
            }
        )
        return "No search query supplied."
    gate_query = (evaluation_query or clean_query).strip()

    max_results = max(1, min(int(max_results), 8))
    planned_follow_ups: list[str] = []
    for raw_query in follow_up_queries or ():
        value = re.sub(r"\s+", " ", str(raw_query)).strip()[:420]
        if value and value != clean_query and value not in planned_follow_ups:
            planned_follow_ups.append(value)
        if len(planned_follow_ups) >= 2:
            break

    request_budget = 8 if planned_follow_ups else 6
    follow_up_reserve = min(3, request_budget - 1) if planned_follow_ups else 0
    primary_budget = request_budget - follow_up_reserve
    requests_used = 0
    errors: list[str] = []
    rejected = 0
    candidates: list[dict[str, str]] = []
    executed_queries = [clean_query]
    providers_used: list[str] = []
    adaptive_used = False

    def finish(status: str, ranked: list[dict[str, str]] | None = None) -> str:
        accepted = ranked or []
        _publish_search_report(
            {
                "status": status,
                "primary_query": clean_query,
                "evaluation_query": gate_query,
                "follow_up_queries": list(planned_follow_ups),
                "executed_queries": list(executed_queries),
                "adaptive_follow_up_used": adaptive_used,
                "requests_used": requests_used,
                "request_budget": request_budget,
                "accepted_results": len(accepted),
                "rejected_results": rejected,
                "distinct_sources": len(_distinct_hosts(accepted)),
                "providers_used": sorted(set(providers_used)),
                "provider_errors": errors[-3:],
            }
        )
        if accepted:
            return _format_results(gate_query, accepted, max_results)
        details: list[str] = []
        if rejected:
            details.append(f"rejected {rejected} irrelevant or stale candidate(s)")
        if errors:
            details.append("provider errors: " + "; ".join(errors[-2:]))
        details.append(f"request budget {requests_used}/{request_budget}")
        suffix = " " + "; ".join(details) if details else ""
        return "No current web results were available." + suffix

    # Exact supported facts use the shortest authoritative path. One verified
    # official result is sufficient; broad discovery is neither faster nor
    # more authoritative for the same claim.
    contract = classify_claim(gate_query)
    if contract.exact and contract.subject.casefold() == "python":
        batch, wave_errors, used, wave_providers = _run_wave(
            [("_python_org", _python_org)],
            gate_query,
            1,
            request_budget,
        )
        requests_used += used
        providers_used.extend(wave_providers)
        errors.extend(wave_errors)
        official = [item for item in batch if _result_is_relevant(gate_query, item)]
        if official:
            return finish("verified_official", official[:1])

    configured = _configured_wave()
    specialized = _specialized_wave(gate_query)
    discovery: list[tuple[str, Any]] = (
        [("_google_news", _google_news), ("_bing_news", _bing_news)]
        if _is_volatile(gate_query)
        else [("_wikipedia", _wikipedia)]
    )
    waves = [configured[:3], configured[3:] + specialized, discovery]
    for wave in waves:
        if not wave or requests_used >= primary_budget:
            continue
        batch, wave_errors, used, wave_providers = _run_wave(
            wave,
            clean_query,
            max_results,
            primary_budget - requests_used,
        )
        requests_used += used
        providers_used.extend(wave_providers)
        errors.extend(wave_errors)
        accepted_batch = [item for item in batch if _result_is_relevant(gate_query, item)]
        rejected += len(batch) - len(accepted_batch)
        candidates.extend(accepted_batch)
        ranked = _deduplicate_and_rank(gate_query, candidates, max_results)
        if _evidence_is_sufficient(gate_query, ranked, max_results):
            return finish("verified_primary", ranked)

    ranked = _deduplicate_and_rank(gate_query, candidates, max_results)
    if planned_follow_ups and not _evidence_is_sufficient(gate_query, ranked, max_results):
        adaptive_used = True
        batch, wave_errors, used, queries_used, adaptive_providers = _run_adaptive_round(
            planned_follow_ups,
            gate_query,
            max_results,
            request_budget - requests_used,
        )
        requests_used += used
        errors.extend(wave_errors)
        providers_used.extend(adaptive_providers)
        for executed in queries_used:
            if executed not in executed_queries:
                executed_queries.append(executed)
        accepted_batch = [item for item in batch if _result_is_relevant(gate_query, item)]
        rejected += len(batch) - len(accepted_batch)
        candidates.extend(accepted_batch)
        ranked = _deduplicate_and_rank(gate_query, candidates, max_results)
        if ranked:
            status = (
                "verified_adaptive"
                if _evidence_is_sufficient(gate_query, ranked, max_results)
                else "verified_partial"
            )
            return finish(status, ranked)

    # DuckDuckGo routes share one circuit breaker and remain the final public
    # fallback. Keep them sequential so a 403/429 on HTML cools the Lite route.
    for provider_name, provider in (
        ("_duckduckgo_html", _duckduckgo_html),
        ("_duckduckgo_lite", _duckduckgo_lite),
    ):
        if requests_used >= request_budget:
            break
        if not _provider_ready(provider_name):
            errors.append(f"{provider_name}: cooling down")
            continue
        requests_used += 1
        providers_used.append(_provider_key(provider_name))
        try:
            batch = provider(clean_query, max_results)
        except Exception as exc:
            _provider_failed(provider_name, exc)
            errors.append(f"{provider_name}: {_safe_error(exc)}")
            continue
        _provider_succeeded(provider_name)
        accepted_batch = [item for item in batch if _result_is_relevant(gate_query, item)]
        rejected += len(batch) - len(accepted_batch)
        candidates.extend(accepted_batch)
        ranked = _deduplicate_and_rank(gate_query, candidates, max_results)
        if _evidence_is_sufficient(gate_query, ranked, max_results):
            return finish("verified_primary", ranked)

    ranked = _deduplicate_and_rank(gate_query, candidates, max_results)
    if ranked:
        return finish("verified_partial", ranked)
    return finish("unavailable")


__all__ = [
    "evidence_source_ids",
    "extract_verified_fact",
    "last_search_report",
    "provider_status",
    "search_web",
    "validate_web_evidence",
]
