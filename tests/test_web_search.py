from __future__ import annotations

import re
import sys
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

import web_search


QUERY = "current Pixel 10 Pro Android update"
BAD_WIKIPEDIA_EVIDENCE = """Query: current Pixel 10 Pro Android update
[1] Pixel 10 Pro
Source: https://en.wikipedia.org/wiki/Pixel_10_Pro
Evidence: The Pixel 10 Pro is a flagship Android-based smartphone from Google.
Provider: Wikipedia
[2] Android 10
Source: https://en.wikipedia.org/wiki/Android_10
Evidence: Earlier Pixel devices received version updates to Android 10.
Provider: Wikipedia
"""
GOOD_UPDATE_EVIDENCE = f"""Query: current Pixel 10 Pro Android update
[1] Pixel 10 Pro receives August 2026 security update
Source: https://support.example.test/pixel-10-pro-august-2026
Evidence: The Pixel 10 Pro security update carries build BP4A.260824.001 and began rolling out in August 2026.
Provider: Google News RSS
Published: {format_datetime(datetime.now(timezone.utc))}
"""


class WebEvidenceTests(unittest.TestCase):
    def setUp(self):
        with web_search._PROVIDER_LOCK:
            web_search._PROVIDER_STATE.clear()

    def tearDown(self):
        with web_search._PROVIDER_LOCK:
            web_search._PROVIDER_STATE.clear()

    def test_volatile_wikipedia_fallback_is_rejected(self):
        self.assertFalse(web_search.validate_web_evidence(QUERY, BAD_WIKIPEDIA_EVIDENCE))

    def test_relevant_update_evidence_is_accepted(self):
        self.assertTrue(web_search.validate_web_evidence(QUERY, GOOD_UPDATE_EVIDENCE))
        self.assertEqual(web_search.evidence_source_ids(GOOD_UPDATE_EVIDENCE), {1})

    def test_old_news_cannot_prove_a_current_update(self):
        stale = re.sub(
            r"(?m)^Published: .+$",
            "Published: " + format_datetime(datetime.now(timezone.utc) - timedelta(days=1000)),
            GOOD_UPDATE_EVIDENCE,
        )
        self.assertFalse(web_search.validate_web_evidence(QUERY, stale))

    def test_stable_wikipedia_background_is_still_accepted(self):
        evidence = """Query: Pixel 10 Pro
[1] Pixel 10 Pro
Source: https://en.wikipedia.org/wiki/Pixel_10_Pro
Evidence: The Pixel 10 Pro is a flagship Android smartphone designed by Google.
Provider: Wikipedia
"""
        self.assertTrue(web_search.validate_web_evidence("Pixel 10 Pro", evidence))

    def test_search_rejects_related_but_nonresponsive_candidates(self):
        candidate = {
            "title": "Pixel 10 Pro",
            "url": "https://en.wikipedia.org/wiki/Pixel_10_Pro",
            "snippet": "The Pixel 10 Pro is an Android smartphone.",
            "provider": "Wikipedia",
            "published": "",
        }
        empty = lambda query, limit: []
        with (
            patch.object(web_search, "_configured_wave", new=lambda: []),
            patch.object(web_search, "_specialized_wave", new=lambda query: []),
            patch.object(web_search, "_duckduckgo_html", new=lambda query, limit: [candidate]),
            patch.object(web_search, "_duckduckgo_lite", new=empty),
            patch.object(web_search, "_google_news", new=empty),
            patch.object(web_search, "_bing_news", new=empty),
        ):
            result = web_search.search_web(QUERY)
        self.assertTrue(result.startswith("No current web results were available."), result)
        self.assertIn("rejected 1", result)

    def test_duckduckgo_rate_limit_cools_both_routes(self):
        calls = {"lite": 0}

        def blocked(query, limit):
            raise urllib.error.HTTPError(
                "https://duckduckgo.test", 429, "Too Many Requests", {}, None
            )

        def lite(query, limit):
            calls["lite"] += 1
            return []

        blocked.__name__ = "_duckduckgo_html"
        lite.__name__ = "_duckduckgo_lite"
        empty = lambda query, limit: []
        with (
            patch.object(web_search, "_configured_wave", new=lambda: []),
            patch.object(web_search, "_specialized_wave", new=lambda query: []),
            patch.object(web_search, "_google_news", new=empty),
            patch.object(web_search, "_bing_news", new=empty),
            patch.object(web_search, "_duckduckgo_html", new=blocked),
            patch.object(web_search, "_duckduckgo_lite", new=lite),
        ):
            result = web_search.search_web(QUERY)
        self.assertTrue(result.startswith("No current web results were available."), result)
        self.assertEqual(calls["lite"], 0)
        duck = next(item for item in web_search.provider_status() if item["provider"] == "duckduckgo")
        self.assertGreater(duck["cooldown_seconds"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
