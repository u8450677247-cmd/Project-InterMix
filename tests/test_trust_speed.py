from __future__ import annotations

import asyncio
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
sys.path.insert(0, str(ENGINE))

import llm_controller
import provider_vault
import resident_engine
import web_search
from claim_contracts import classify_claim, item_entails_claim
from memory_store import MemoryStore
from prompt_builder import build_prompt
from resident_engine import ResidentEngineManager
from response_policy import select_response_policy


PYTHON_EVIDENCE = """Query: What is the latest Python version?
[1] Python 3.14.7
Source: https://www.python.org/downloads/
Evidence: Python.org lists Python 3.14.7 as the latest stable release.
Provider: Python.org Official
Claim-Type: latest_stable_version
Verified-Value: 3.14.7
Channel: stable
Authority: official
Retrieved: 2026-08-25T12:00:00+00:00
"""


class ClaimContractTests(unittest.TestCase):
    def test_stable_and_prerelease_are_distinct(self):
        stable = classify_claim("What is the latest Python version?")
        preview = classify_claim("What is the latest Python prerelease version?")
        self.assertEqual(stable.kind, "latest_stable_version")
        self.assertEqual(stable.channel, "stable")
        self.assertEqual(preview.kind, "latest_prerelease_version")
        self.assertEqual(preview.channel, "prerelease")
        self.assertNotEqual(stable.cache_key, preview.cache_key)

    def test_compatibility_mention_is_not_proof_of_latest(self):
        contract = classify_claim("What is the latest Python version?")
        item = {
            "title": "Project adds Python 3.15 support",
            "snippet": "This package now supports Python 3.15.",
            "provider": "GitHub REST",
            "url": "https://github.example/issue/1",
        }
        self.assertFalse(item_entails_claim(contract, item))

    def test_official_python_resolver_selects_stable_channel(self):
        page = """
        <h1>Download Python 3.14.7</h1>
        <p>Looking for Python 3.15.0rc2?</p>
        <p>Older Python 3.13.12</p>
        """
        with patch.object(web_search, "_fetch", return_value=page):
            result = web_search._python_org("latest Python version", 4)
        self.assertEqual(result[0]["verified_value"], "3.14.7")
        self.assertEqual(result[0]["channel"], "stable")

    def test_search_short_circuits_after_official_exact_result(self):
        official = {
            "title": "Python 3.14.7",
            "url": "https://www.python.org/downloads/",
            "snippet": "Python.org lists Python 3.14.7 as the latest stable release.",
            "provider": "Python.org Official",
            "published": "",
            "claim_type": "latest_stable_version",
            "verified_value": "3.14.7",
            "channel": "stable",
            "authority": "official",
            "retrieved_at": "2026-08-25T12:00:00+00:00",
        }
        with (
            patch.object(web_search, "_python_org", return_value=[official]),
            patch.object(web_search, "_github") as github,
            patch.object(web_search, "_duckduckgo_html") as duck,
        ):
            result = web_search.search_web("What is the latest Python version?")
        self.assertIn("Verified-Value: 3.14.7", result)
        github.assert_not_called()
        duck.assert_not_called()

    def test_verified_fact_round_trip(self):
        fact = web_search.extract_verified_fact(
            "What is the latest Python version?", PYTHON_EVIDENCE
        )
        self.assertIsNotNone(fact)
        self.assertEqual(fact["value"], "3.14.7")
        self.assertEqual(fact["source_id"], 1)

    def test_optional_search_apis_parse_without_exposing_keys(self):
        serp_payload = json.dumps(
            {
                "search_metadata": {"status": "Success"},
                "organic_results": [
                    {
                        "title": "Official release notes",
                        "link": "https://example.test/release",
                        "snippet": "Current software release notes and version information.",
                    }
                ],
            }
        )
        tiny_payload = json.dumps(
            {
                "results": [
                    {
                        "title": "Current platform update",
                        "url": "https://example.test/update",
                        "snippet": "Current platform update and security release.",
                        "date": "2026-08-25",
                    }
                ]
            }
        )
        with (
            patch.dict(
                os.environ,
                {"SERPAPI_API_KEY": "dummy-serp", "TINYFISH_API_KEY": "dummy-tiny"},
                clear=False,
            ),
            patch.object(web_search, "_fetch", side_effect=[serp_payload, tiny_payload]),
        ):
            serp = web_search._serpapi("current software release", 2)
            tiny = web_search._tinyfish("current platform update", 2)
        rendered = json.dumps([serp, tiny])
        self.assertNotIn("dummy-serp", rendered)
        self.assertNotIn("dummy-tiny", rendered)
        self.assertEqual(serp[0]["provider"], "SerpAPI Google")
        self.assertEqual(tiny[0]["provider"], "TinyFish Search API")


class LookupBudgetTests(unittest.TestCase):
    def test_lookup_mode_uses_compact_input_lane(self):
        policy = select_response_policy(
            "What is the latest Python version?", fast_lookup=True
        )
        self.assertEqual(policy.mode, "lookup")
        self.assertEqual(policy.input_limit + policy.output_reserve, 8000)
        with tempfile.TemporaryDirectory() as temporary:
            store = MemoryStore(Path(temporary) / "brain.db")
            session = store.create_session("Lookup")
            for index in range(20):
                store.append_message(session["id"], "user", f"Old unrelated message {index} " * 20)
            current = store.append_message(
                session["id"], "user", "What is the latest Python version?"
            )
            prompt = build_prompt(
                store,
                session_id=session["id"],
                current_user_message_id=current,
                user_text="What is the latest Python version?",
                web_data=PYTHON_EVIDENCE,
                grounding_required=True,
                response_instruction=policy.instruction,
                response_mode=policy.mode,
                input_limit_tokens=policy.input_limit,
                output_reserve_tokens=policy.output_reserve,
            )
        self.assertLessEqual(prompt.estimated_tokens, 2200)
        self.assertNotIn("second_brain", prompt.report["blocks"])
        self.assertNotIn("protocol", prompt.report["blocks"])
        self.assertNotIn("session", prompt.report["blocks"])


class ControllerFactTests(unittest.TestCase):
    def test_bad_exact_draft_is_hidden_and_repaired(self):
        async def run():
            temporary = tempfile.TemporaryDirectory()
            self.addCleanup(temporary.cleanup)
            store = MemoryStore(Path(temporary.name) / "brain.db")
            store.set_setting("fact_style", "hybrid")
            calls = {"count": 0}

            async def fake_model(prompt: str, model_role: str = "reasoning"):
                calls["count"] += 1
                if calls["count"] == 1:
                    answer = "[1] Python 3.14.6 is currently the latest stable version."
                else:
                    answer = "[1] Python 3.14.7 is the latest stable version. 🛰️"
                yield "token", answer
                yield "_model_meta", json.dumps(
                    {"backend": "fake", "return_code": 0, "hidden": "", "visible": answer}
                )

            def fake_search(query: str, *, evaluation_query: str | None = None):
                return PYTHON_EVIDENCE

            with (
                patch.object(llm_controller, "STORE", store),
                patch.object(llm_controller, "search_web", new=fake_search),
                patch.object(llm_controller, "_stream_model", new=fake_model),
                patch.object(llm_controller, "_inference_mode", return_value="pty"),
            ):
                events = []
                async for event in llm_controller.stream_inference(
                    "What is the latest Python version?"
                ):
                    events.append(event)
            return calls, events

        calls, events = asyncio.run(run())
        visible = "".join(value for kind, value in events if kind == "token")
        cards = [json.loads(value) for kind, value in events if kind == "fact_card"]
        self.assertEqual(calls["count"], 2)
        self.assertNotIn("3.14.6", visible)
        self.assertIn("3.14.7", visible)
        self.assertEqual(cards[0]["value"], "3.14.7")


class ResidentProfileTests(unittest.TestCase):
    def test_measured_memory_selects_adaptive_profiles(self):
        manager = ResidentEngineManager(
            model_path="/nonexistent",
            keep_hot_memory_mb=2560,
            low_memory_mb=2048,
            critical_memory_mb=1280,
        )
        with patch.object(resident_engine, "available_memory_mb", return_value=3100):
            self.assertEqual(manager.status()["residency_profile"], "performance")
        with patch.object(resident_engine, "available_memory_mb", return_value=1800):
            self.assertEqual(manager.status()["residency_profile"], "pressure")
        with patch.object(resident_engine, "available_memory_mb", return_value=900):
            self.assertEqual(manager.status()["effective_idle_unload_seconds"], 5)

    def test_warm_loads_engine_once_without_generation(self):
        class FakeBackend:
            @staticmethod
            def GPU():
                return "gpu"

        class FakeModule:
            Backend = FakeBackend
            count = 0

            class Engine:
                def __init__(self, *args, **kwargs):
                    FakeModule.count += 1

                def close(self):
                    pass

        async def run(path: Path):
            manager = ResidentEngineManager(
                model_path=str(path),
                cache_dir=str(path.parent),
                prewarm_memory_mb=0,
                module_loader=lambda: FakeModule,
            )
            self.assertTrue(await manager.warm())
            self.assertTrue(await manager.warm())
            manager.shutdown(wait=True)

        with tempfile.TemporaryDirectory() as temporary:
            model = Path(temporary) / "model.litertlm"
            model.write_bytes(b"fake")
            asyncio.run(run(model))
        self.assertEqual(FakeModule.count, 1)


class ProviderVaultTests(unittest.TestCase):
    def test_vault_is_private_and_values_are_never_reported(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "providers.env"
            provider_vault._write_values(
                {"SERPAPI_API_KEY": "secret-test-value", "TINYFISH_API_KEY": "tiny-secret"},
                path,
            )
            mode = stat.S_IMODE(path.stat().st_mode)
            self.assertEqual(mode, 0o600)
            values = provider_vault._read_values(path)
            self.assertEqual(values["SERPAPI_API_KEY"], "secret-test-value")
            self.assertNotIn("secret-test-value", provider_vault.__doc__ or "")
            path.chmod(0o644)
            self.assertEqual(provider_vault._read_values(path), {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
