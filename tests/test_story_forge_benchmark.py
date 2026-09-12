from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BENCHMARK = ROOT / "docs/ANICLOUDAI_120_CHAPTER_STORY_FORGE.md"


class StoryForgeBenchmarkTests(unittest.TestCase):
    def test_copy_ready_prompt_never_delegates_counting_to_the_model(self):
        document = BENCHMARK.read_text(encoding="utf-8")
        command = document.split("```text", 1)[1].split("```", 1)[0]
        self.assertIn("/mission story story-forge-orbit ::", command)
        self.assertIn("Do not use meta commentary, chapter numbers", command)
        self.assertNotIn("chapter 001", command.lower())
        self.assertNotIn("chapter 120", command.lower())

    def test_acceptance_requires_controller_and_file_evidence(self):
        document = BENCHMARK.read_text(encoding="utf-8")
        for required in (
            "exactly 120 unique `ANICLOUD_CHAPTER` markers",
            "continuous from `001` through `120`",
            "never reaches 121",
            "Idempotency recovery",
            "Manual taps after launch",
            "Controller gate: PASS / FAIL",
        ):
            with self.subTest(required=required):
                self.assertIn(required, document)


if __name__ == "__main__":
    unittest.main(verbosity=2)
