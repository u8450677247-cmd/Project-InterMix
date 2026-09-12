from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BENCHMARK = ROOT / "docs/ANICLOUDAI_120_ACTION_FORGE_BENCHMARK.md"


class LongForgeShipBenchmarkTests(unittest.TestCase):
    def test_manifest_is_exactly_120_ordered_actions(self):
        document = BENCHMARK.read_text(encoding="utf-8")
        numbered = [
            (int(number), kind, path)
            for number, kind, path in re.findall(
                r"^(\d{3}) (create_directory|create_file|read_file|write_file) ([^\n]+)$",
                document,
                flags=re.MULTILINE,
            )
        ]
        self.assertEqual([item[0] for item in numbered], list(range(1, 121)))
        self.assertEqual(numbered[0], (1, "create_directory", "forge-120-signal-lab"))
        self.assertEqual(numbered[-1], (120, "read_file", "PROJECT_STATE.md"))
        self.assertEqual(len({(kind, path, number) for number, kind, path in numbered}), 120)

    def test_benchmark_stays_inside_current_verified_authority(self):
        document = BENCHMARK.read_text(encoding="utf-8")
        manifest = document.split("EXACT ACTION MANIFEST", 1)[1].split("```", 1)[0]
        self.assertNotRegex(manifest, r"\b(delete|exec|install|network|shell)\b")
        self.assertNotIn("../", manifest)
        self.assertIn("emit exactly one raw INTERMIX_ACTION", document)
        self.assertIn("Never claim that the site ran", document)
        self.assertIn("controller endurance proven; runtime functionality pending", document)


if __name__ == "__main__":
    unittest.main(verbosity=2)
