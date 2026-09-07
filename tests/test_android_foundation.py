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
        self.assertIn("versionCode = 4", app_build)
        self.assertIn('versionName = "0.3.1-runtime-compat"', app_build)
        self.assertIn("compose-bom:2026.03.01", app_build)
        self.assertIn('abiFilters += "arm64-v8a"', app_build)
        self.assertIn(
            'com.google.ai.edge.litertlm:litertlm-android:0.16.1', app_build
        )
        self.assertIn(
            'kotlinx-coroutines-android:1.9.0', app_build
        )
        self.assertIn(
            'kotlinx-coroutines-core-jvm:1.9.0', app_build
        )
        self.assertNotIn('kotlinx-coroutines-android:1.10.2', app_build)

    def test_manifest_declares_biometric_and_visible_inference_service(self):
        manifest = APP / "src/main/AndroidManifest.xml"
        root = ET.parse(manifest).getroot()
        self.assertEqual(root.tag, "manifest")
        android_name = "{http://schemas.android.com/apk/res/android}name"
        permissions = {
            node.attrib[android_name] for node in root.findall("uses-permission")
        }
        self.assertEqual(
            permissions,
            {
                "android.permission.USE_BIOMETRIC",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            },
        )
        services = root.findall("application/service")
        self.assertEqual(len(services), 1)
        self.assertEqual(
            services[0].attrib[android_name],
            ".InferenceForegroundService",
        )

    def test_composer_contract_is_native_multiline_and_visible_send(self):
        source = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        contract = (SOURCE / "FoundationContract.kt").read_text(encoding="utf-8")
        self.assertIn("DesktopThresholdDp = 1200", contract)
        self.assertIn("singleLine = false", source)
        self.assertIn("maxLines = ComposerMaxVisibleLines", source)
        self.assertIn("ImeAction.Default", source)
        self.assertIn('Text("SEND"', source)
        self.assertIn('Text("STOP"', source)

    def test_cockpit_uses_measured_android_health_signals(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        combined = ui + view_model
        self.assertIn("ActivityManager.MemoryInfo", view_model)
        self.assertIn("info.availMem", view_model)
        self.assertIn("PowerManager.OnThermalStatusChangedListener", view_model)
        self.assertIn("thermalStatusLabel", ui)
        self.assertIn("Android thermal pressure · categorical", ui)
        self.assertNotIn("MemFree", combined)

    def test_model_import_is_one_file_verified_and_app_private(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "ModelRepository.kt").read_text(encoding="utf-8")
        self.assertIn("ActivityResultContracts.OpenDocument", ui)
        self.assertIn("context.noBackupFilesDir", repository)
        self.assertIn('MessageDigest.getInstance("SHA-256")', repository)
        self.assertIn('endsWith(".litertlm")', repository)
        self.assertIn("target.fd.sync()", repository)

    def test_native_runtime_is_cancellable_and_has_measured_fallback(self):
        runtime = (SOURCE / "LiteRtModelRuntime.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        combined = runtime + view_model
        self.assertIn("Dispatchers.IO", runtime)
        self.assertIn("Backend.GPU()", runtime)
        self.assertIn("Backend.CPU()", runtime)
        self.assertIn("private const val PhysicalContextTokens = 8_000", runtime)
        self.assertIn("maxNumTokens = PhysicalContextTokens", runtime)
        self.assertIn("sendMessageAsync", runtime)
        self.assertIn("cancelProcess()", combined)
        self.assertIn("resetConversation()", combined)
        self.assertIn("FirstTokenTimeoutMillis = 180_000L", view_model)
        self.assertIn("InterChunkTimeoutMillis = 60_000L", view_model)
        self.assertIn('code = "stalled-stream"', view_model)
        self.assertIn("lastFirstTokenMillis", view_model)
        self.assertIn("lastResponseMillis", view_model)
        self.assertNotIn("Backend.NPU", combined)
        self.assertNotIn("GOOGLE_TENSOR", combined)

    def test_generation_survives_window_switches_without_token_rate_recomposition(self):
        service = (SOURCE / "InferenceForegroundService.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("FOREGROUND_SERVICE_TYPE_SPECIAL_USE", service)
        self.assertIn("GenerationStopBridge.requestStop()", service)
        self.assertIn("generation continues across windows", service)
        self.assertIn("StreamUiPublishMillis = 90L", view_model)
        self.assertIn("launch(Dispatchers.Default)", view_model)
        self.assertIn("StringBuilder()", view_model)

    def test_workspace_uses_persisted_tree_access_and_prewrite_snapshots(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "WorkspaceRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "WorkspaceViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("ActivityResultContracts.OpenDocumentTree", ui)
        self.assertIn("takePersistableUriPermission", repository)
        self.assertIn('File(context.filesDir, "workspace_snapshots")', repository)
        self.assertIn('openFileDescriptor(uri, "rwt")', repository)
        self.assertIn("Save or revert the current draft", view_model)
        self.assertNotIn("deleteDocument", repository + view_model)

    def test_obsidian_root_establishes_a_readable_content_color(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("contentColor = MaterialTheme.colorScheme.onBackground", ui)
        self.assertIn("color = MaterialTheme.colorScheme.onSurface, lineHeight", ui)

    def test_committed_conversation_history_is_atomic_and_app_private(self):
        repository = (SOURCE / "ConversationRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn('File(context.filesDir, HistoryFileName)', repository)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", repository)
        self.assertIn("MaxHistoryMessages = 200", repository)
        self.assertIn("quarantineCorruptHistory()", repository)
        self.assertIn("conversationRepository.load()", view_model)
        self.assertIn("distinctUntilChanged()", view_model)

    def test_generation_guard_and_visual_truth_are_wired(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        guard = (SOURCE / "GenerationIntegrity.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        theme = (SOURCE / "ui/SovereignTheme.kt").read_text(encoding="utf-8")
        self.assertIn("GenerationIntegrityGuard.inspectStreamingText", view_model)
        self.assertIn("missingExactNumericAnchors", view_model)
        self.assertIn("repetition-loop", guard)
        self.assertIn("LivingVoid", ui)
        self.assertIn("allowsAmbientMotion", ui)
        self.assertIn("ThermalSparkline", ui)
        self.assertIn("Termux-assisted dogfood", ui)
        self.assertIn("0xFFEAF6FF", theme)
        self.assertNotIn("syntheticResponse", ui)

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
        self.assertIn('"feature/anicloud-*"', workflow)
        self.assertIn('sdkmanager "platforms;android-36"', workflow)
        self.assertNotIn("--channel=3", workflow)
        self.assertIn("actions/checkout@v7", workflow)
        self.assertIn("actions/setup-java@v6", workflow)
        self.assertIn("gradle/actions/setup-gradle@v6", workflow)
        self.assertIn("actions/upload-artifact@v7", workflow)
        self.assertIn("AniCloudAI-e4b-cockpit-debug", workflow)
        self.assertIn("app-debug.apk", workflow)

    def test_termux_dogfood_channel_keeps_signing_material_private(self):
        updater = (ROOT / "tools/termux_dogfood_update.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("--initialize-key", updater)
        self.assertIn("apksigner sign", updater)
        self.assertIn("apksigner verify --verbose --print-certs", updater)
        self.assertIn("--status success", updater)
        self.assertIn("termux-open --content-type", updater)
        self.assertNotIn("adb install", updater)
        self.assertNotIn("storepass pass:", updater)


if __name__ == "__main__":
    unittest.main()
