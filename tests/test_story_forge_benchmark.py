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

    def test_native_preset_loads_the_reviewed_prompt_without_starting_it(self):
        story = (
            ROOT
            / "android/app/src/main/java/dev/anicloud/sovereign/StoryForge.kt"
        ).read_text(encoding="utf-8")
        ui = (
            ROOT
            / "android/app/src/main/java/dev/anicloud/sovereign/ui/SovereignApp.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('StoryForgeBenchmarkFolder = "story-forge-orbit"', story)
        self.assertIn("val StoryForgeBenchmarkPremise", story)
        self.assertIn("the sky keeps receipts", story)
        self.assertIn("LOAD 120-CHAPTER BENCHMARK", ui)
        self.assertIn("rootPath = StoryForgeBenchmarkFolder", ui)
        self.assertIn("objective = StoryForgeBenchmarkPremise", ui)
        self.assertIn("modeName = AnswerMode.Quality.name", ui)
        self.assertIn("Nothing runs until", ui)


if __name__ == "__main__":
    unittest.main(verbosity=2)
