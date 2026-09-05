from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
APP = ANDROID / "app"
SOURCE = APP / "src/main/java/dev/anicloud/sovereign"


class AndroidFoundationTests(unittest.TestCase):
    def test_build_baseline_is_pinned_and_uses_built_in_kotlin(self):
        root_build = (ANDROID / "build.gradle.kts").read_text(encoding="utf-8")
        app_build = (APP / "build.gradle.kts").read_text(encoding="utf-8")
        wrapper = (ANDROID / "gradle/wrapper/gradle-wrapper.properties").read_text(
            encoding="utf-8"
        )
        self.assertIn('version "9.2.1"', root_build)
        self.assertIn('version "2.3.10"', root_build)
        self.assertNotIn("org.jetbrains.kotlin.android", root_build + app_build)
        self.assertIn("gradle-9.4.1-bin.zip", wrapper)
        self.assertIn("compileSdk = 36", app_build)
        self.assertIn("targetSdk = 36", app_build)
        self.assertIn("minSdk = 31", app_build)
        self.assertIn("compose-bom:2026.03.01", app_build)

    def test_manifest_is_well_formed_and_requests_no_runtime_permission(self):
        manifest = APP / "src/main/AndroidManifest.xml"
        root = ET.parse(manifest).getroot()
        self.assertEqual(root.tag, "manifest")
        self.assertEqual(root.findall("uses-permission"), [])

    def test_composer_contract_is_native_multiline_and_visible_send(self):
        source = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("singleLine = false", source)
        self.assertIn("maxLines = ComposerMaxVisibleLines", source)
        self.assertIn("ImeAction.Default", source)
        self.assertIn('Text("SEND"', source)
        self.assertIn('Text("STOP"', source)

    def test_foundation_uses_truthful_disconnected_states(self):
        source = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn('"Disconnected"', source)
        self.assertIn('"No user data"', source)
        self.assertIn('"Adapter pending"', source)
        self.assertIn('"No invented reading"', source)
        self.assertNotIn("MemFree", source)

    def test_contract_freezes_security_and_sync_boundaries(self):
        contract = (ROOT / "docs/ANDROID_PRODUCT_CONTRACT.md").read_text(
            encoding="utf-8"
        )
        required = (
            "Android biometric or device credential",
            "30-second background grace period",
            "The APK is authoritative for conflicts",
            "share or concurrently open a live SQLite file",
            "Sanctuary notifications always say only",
            "Deletion always requires confirmation",
            "Pixel 10 Pro",
        )
        for phrase in required:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, contract)

    def test_android_workflow_retains_a_debug_apk(self):
        workflow = (ROOT / ".github/workflows/android-foundation.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('"feature/anicloud-android-*"', workflow)
        self.assertIn('sdkmanager "platforms;android-36"', workflow)
        self.assertNotIn("--channel=3", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertIn("AniCloudAI-foundation-debug", workflow)
        self.assertIn("app-debug.apk", workflow)


if __name__ == "__main__":
    unittest.main()
