# Web providers and grounding policy

Intermix routes search outside the model. A deterministic freshness policy classifies the question, builds a bounded query plan, chooses provider waves, validates relevance, and supplies only compact evidence to inference.

The goal is not to call every provider on every question. The goal is the smallest reliable source set with clear failure behavior.

## Built-in routes

| Route | Credential | Best use | Authority |
|---|---|---|---|
| Python.org resolver | None | Exact latest stable Python release | Official primary source |
| GitHub REST | Optional token for stronger limits | Repositories, releases, issues, commits | Official platform API; repository authority varies |
| PyPI JSON | None | Python package metadata and releases | Official registry |
| Crossref API | None | Scholarly metadata and DOI lookup | Verified registry |
| arXiv API | None | Preprints and technical papers | Primary repository, not peer-review proof |
| Google News RSS | None | Current news discovery | Discovery feed; article authority varies |
| Bing News RSS | None | Current news discovery | Discovery feed; article authority varies |
| Wikipedia API | None | Stable background context | Tertiary fallback; rejected for volatile exact claims |
| SerpAPI | Optional key | Broad sanctioned search route | Search aggregation; validate underlying source |
| TinyFish | Optional key | Search/fetch expansion | Search/fetch provider; validate underlying source |
| Brave Search | Optional key | Independent broad search | Search API; validate underlying source |
| Tavily | Optional key | Broad research retrieval | Search API; validate underlying source |
| Exa | Optional key | Semantic web discovery | Search API; validate underlying source |
| SearXNG | Optional URL | User-controlled metasearch | Depends on configured instance and upstreams |
| DuckDuckGo Lite | None | Last-resort broad discovery | Cooled fallback; rate limits and relevance vary |

Provider availability can change. `/web status` reports configured/readiness state without displaying keys.

## Search waves

1. **Exact official resolver:** used first when a controller claim contract exists, such as the latest stable Python release.
2. **Configured APIs:** up to a bounded number of independent authorized routes.
3. **Query-specific sources:** GitHub, PyPI, Crossref, or arXiv when the intent matches.
4. **News or stable-background discovery:** Google/Bing news for current events; Wikipedia only for stable context.
5. **Fallback:** cooled DuckDuckGo routes when permitted.

An official exact result can short-circuit later waves. This reduces latency, API cost, rate-limit exposure, and contradictory snippets.

## Validation

Candidate evidence is scored for:

- lexical and entity relevance to the original question;
- requested time/channel semantics such as current, stable, beta, or release date;
- source authority and provider provenance;
- publication/retrieval recency for volatile topics; and
- whether the evidence actually entails the requested claim.

Old news cannot prove a current update. A compatibility mention cannot prove the latest release. Wikipedia cannot settle a volatile exact fact. Related snippets that do not answer the question are discarded.

## Forced grounding contract

`/web` requires supplied source identifiers in the visible answer. An uncited draft is hidden. If the evidence is missing or inadequate, Intermix emits a grounding guard instead of filling the gap from model memory.

Normal automatic grounding may use the same evidence pipeline, but stable topics such as basic arithmetic or timeless conceptual questions stay local unless explicitly forced.

## Configure optional credentials

Close the cockpit, then run:

```bash
intermix-providers
```

Supported secret fields:

- `SERPAPI_API_KEY`
- `TINYFISH_API_KEY`
- `BRAVE_SEARCH_API_KEY`
- `TAVILY_API_KEY`
- `EXA_API_KEY`
- `GITHUB_TOKEN`

Supported non-secret fields:

- `SEARXNG_URL`
- `INTERMIX_SEARCH_LOCATION`
- `INTERMIX_SEARCH_LANGUAGE`

The tool uses hidden input for keys, writes an allowlisted mode-600 vault atomically, and never prints secret values. The model cannot request or retrieve them.

If a key was ever pasted into chat, an issue, a screenshot, or terminal history, rotate it at the provider before storing the replacement in the vault.

## Rate limits and terms

Intermix uses timeouts, cooldowns, caching, bounded concurrency, and provider fallback. It does not attempt to bypass captchas or rate limits through DNS changes, IP/proxy rotation, automated challenge solving, account cycling, or other evasive behavior.

Each user is responsible for provider terms, quotas, billing, privacy, and permitted content. A configured provider is not automatically an authoritative source.

## Adding an adapter

A provider pull request must include:

1. an official API/terms link;
2. controller-side credentials with no model exposure;
3. bounded timeouts and result count;
4. a stable provider name and provenance fields;
5. relevance, malformed-response, cooldown, and no-secret-output tests;
6. a statement of source authority; and
7. no captcha or rate-limit evasion.

Use the provider-adapter issue template before a large implementation.
