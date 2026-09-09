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
        self.assertIn("versionCode = 10", app_build)
        self.assertIn('versionName = "0.7.0-interaction-matrix"', app_build)
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
        self.assertIn("SlashCommandPalette", source)
        self.assertIn('SlashCommand("/device"', source)
        self.assertIn('SlashCommand("/profile"', source)
        self.assertIn('SlashCommand("/why"', source)
        self.assertIn('SlashCommand("/adapt"', source)
        self.assertIn('SlashCommand("/undo-adaptation"', source)
        self.assertIn("CONTROLLER COMMANDS · TAP TO INSERT", source)

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
        self.assertIn(
            "?: (if (legacy) preferences.getString(ModelNameKey, null) else null)",
            repository,
        )
        self.assertIn(
            "?: (if (legacy) preferences.getString(ModelShaKey, null) else null)",
            repository,
        )

    def test_adaptive_tensor_route_is_fingerprint_locked_and_visible(self):
        policy = (SOURCE / "AdaptiveRuntimePolicy.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "ModelRepository.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("TensorG5E2BSha256", policy)
        self.assertIn(
            "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc",
            policy,
        )
        self.assertIn("RuntimeBackendPreference.NpuOnly", policy)
        self.assertIn("role: ModelRole", repository)
        self.assertIn("DEVICE CHECK-UP", ui)
        self.assertIn("IMPORT E2B · TENSOR G5", ui)
        self.assertIn("E2B must match the reviewed Tensor G5 fingerprint", ui)

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
        self.assertIn("callback = object : MessageCallback", runtime)
        self.assertIn("override fun onDone()", runtime)
        self.assertIn("close(null)", runtime)
        self.assertIn("awaitClose {}", runtime)
        self.assertNotIn(").map { message -> message.toString() }", runtime)
        self.assertIn("cancelProcess()", combined)
        self.assertIn("resetConversation()", combined)
        self.assertIn("FirstTokenTimeoutMillis = 180_000L", view_model)
        self.assertIn("InterChunkTimeoutMillis = 60_000L", view_model)
        self.assertIn('code = "stalled-stream"', view_model)
        self.assertIn("lastFirstTokenMillis", view_model)
        self.assertIn("lastResponseMillis", view_model)
        self.assertIn("Backend.NPU", combined)
        self.assertIn("RuntimeBackendPreference.NpuOnly", combined)
        self.assertIn("E2B GPU fallback is disabled", runtime)
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
        self.assertIn("documentUriForQuery", repository)
        self.assertIn("DocumentsContract.getDocumentId(uri)", repository)
        self.assertIn("DocumentsContract.getTreeDocumentId(uri)", repository)
        self.assertNotIn("if (DocumentsContract.isTreeUri(directory))", repository)
        self.assertIn('File(context.filesDir, "workspace_snapshots")', repository)
        self.assertIn('openFileDescriptor(uri, "rwt")', repository)
        self.assertIn("Save or revert the current draft", view_model)
        self.assertNotIn("deleteDocument", repository + view_model)

    def test_obsidian_root_establishes_a_readable_content_color(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("contentColor = MaterialTheme.colorScheme.onBackground", ui)
        self.assertIn("color = MaterialTheme.colorScheme.onSurface, lineHeight", ui)

    def test_memory_matrix_is_app_private_searchable_and_migrates_history(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("SQLiteOpenHelper", repository)
        self.assertIn("setWriteAheadLoggingEnabled(true)", repository)
        for table in (
            "sessions", "messages", "memories", "memory_revisions",
            "project_events", "agent_actions",
        ):
            self.assertIn(f"CREATE TABLE {table}", repository)
        self.assertIn("CREATE TABLE IF NOT EXISTS interaction_profile", repository)
        self.assertIn("CREATE TABLE IF NOT EXISTS interaction_profile_revisions", repository)
        self.assertIn("USING fts5", repository)
        self.assertIn("migrateLegacyHistory()", repository)
        self.assertIn("LegacyHistoryName.migrated", repository)
        self.assertIn("memoryMatrix.loadMessages()", view_model)
        self.assertIn("memoryMatrix.recallContext", view_model)
        self.assertIn("MatrixSchemaVersion = 2", repository)
        self.assertIn("undoLatestProfileChange", repository)

    def test_interaction_profile_gates_context_and_cannot_grant_authority(self):
        policy = (SOURCE / "InteractionProfile.kt").read_text(encoding="utf-8")
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        contract = (ROOT / "docs/ANDROID_INTERACTION_PROFILE.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("object InteractionProfilePolicy", policy)
        self.assertIn("ContextScope.General", policy)
        self.assertIn("recentMessageLimit = 0", policy)
        self.assertIn("allowMemoryFallback = false", policy)
        self.assertIn("<PROFILE_UPDATE>", protocol)
        self.assertIn("profilePayload", protocol)
        self.assertIn("applyExplicitProfileAdjustments", view_model)
        self.assertIn("[CONTEXT GATE]", view_model)
        self.assertIn("WORKSPACE CONTEXT WITHHELD", view_model)
        self.assertIn("InteractionProfileCard", ui)
        self.assertIn("identity and permissions remain immutable", ui)
        self.assertIn("never grants tool authority", contract)

    def test_github_funding_metadata_is_valid_and_support_link_is_visible(self):
        funding = (ROOT / ".github/FUNDING.yml").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertEqual(funding, "buy_me_a_coffee: yasseh\n")
        self.assertIn("https://buymeacoffee.com/yasseh", readme)

    def test_model_tools_are_controller_owned_and_writes_wait_for_approval(self):
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        runtime = (SOURCE / "LiteRtModelRuntime.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        workspace = (SOURCE / "WorkspaceRepository.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("visibleStreamingText", protocol)
        self.assertIn("<INTERMIX_ACTION>", protocol)
        self.assertIn("fun promptContract()", protocol)
        self.assertIn('"kind":"list_files"', protocol)
        self.assertIn('"kind":"create_file"', protocol)
        self.assertIn("ControllerProtocol.promptContract()", view_model)
        self.assertIn("SOVEREIGN IDENTITY CONTRACT", view_model)
        self.assertIn("warm long-running co-creator", view_model)
        self.assertIn("one purposeful emoji", view_model)
        self.assertIn("Do not overdecorate", view_model)
        self.assertIn('"/capabilities"', view_model)
        self.assertIn('"/version"', view_model)
        self.assertIn('ListFiles("list_files", false)', protocol)
        self.assertIn('WriteFile("write_file", true)', protocol)
        self.assertIn("automaticToolCalling = false", runtime)
        self.assertIn("queueWorkspaceAction", view_model)
        self.assertIn("executeReadOnly", view_model)
        self.assertIn("executeApproved", view_model)
        self.assertIn("DocumentsContract.createDocument", workspace)
        self.assertNotIn("deleteDocument", workspace)
        self.assertIn("PendingActionCard", ui)
        self.assertIn('Text("APPROVE")', ui)
        self.assertIn('Text("DENY")', ui)

    def test_matrix_and_custom_destination_icons_are_real_surfaces(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        contract = (SOURCE / "FoundationContract.kt").read_text(encoding="utf-8")
        self.assertIn('Memory("Matrix")', contract)
        self.assertIn("MemoryMatrixSurface", ui)
        self.assertIn("MemoryCard", ui)
        self.assertIn("DestinationIcon", ui)
        self.assertNotIn("destinationGlyph", ui)
        self.assertIn('"DEVICE RAM"', ui)
        self.assertIn('"MEMORY MATRIX"', ui)

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

    def test_sovereign_glass_markdown_and_code_color_are_native(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        rich = (SOURCE / "ui/SovereignRichText.kt").read_text(encoding="utf-8")
        theme = (SOURCE / "ui/SovereignTheme.kt").read_text(encoding="utf-8")
        runtime = (SOURCE / "LiteRtModelRuntime.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("SovereignMarkdown(message.text)", ui)
        self.assertIn("visualTransformation = codeTransformation", ui)
        self.assertIn("BuildConfig.VERSION_NAME.uppercase()", ui)
        self.assertIn("fun Modifier.sovereignGlass", rich)
        self.assertIn("class SovereignCodeTransformation", rich)
        self.assertIn("fun highlightCode", rich)
        self.assertIn("MaxHighlightedCharacters", rich)
        self.assertIn("parseMarkdown", rich)
        self.assertIn('"[SUCCESS]" to ResonanceMint', rich)
        self.assertIn("SovereignShapes", theme)
        self.assertIn("warm, atmospheric, emotionally intelligent voice", runtime)
        self.assertIn("AgentMemoryContext(cockpit.memoryMatrix)", ui)
        self.assertIn("Relevant Matrix entries inform each plan", ui)
        self.assertIn("languageForFile(pending.path)", ui)
        self.assertIn("memory never grants tool authority", view_model)
        self.assertNotIn("border = null", ui)

    def test_runtime_cards_are_deduplicated_without_touching_chat(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("collapseDuplicateRuntimeMessages", repository)
        self.assertIn("WHERE source='runtime'", repository)
        self.assertIn("GROUP BY session_id, content", repository)
        self.assertIn("messages.none", view_model)

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

    def test_android_workflow_retains_debug_and_persistent_dogfood_apks(self):
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
        self.assertIn("ANICLOUD_DOGFOOD_PRIVATE_KEY_B64", workflow)
        self.assertIn("ANICLOUD_DOGFOOD_CERTIFICATE_B64", workflow)
        self.assertIn('"$signer" sign', workflow)
        self.assertIn('"$signer" verify --verbose --print-certs', workflow)
        self.assertIn("AniCloudAI-e4b-cockpit-dogfood", workflow)
        self.assertIn("fetch_tensor_dispatcher.sh", workflow)
        dispatcher = (ROOT / "tools/fetch_tensor_dispatcher.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("v${VERSION}/litert_npu_runtime_libraries.zip", dispatcher)
        self.assertIn(
            "98aabbdce8607f6dc6ab7cb92217326eef24a8c97b973b69e62bd0ce14b7495b",
            dispatcher,
        )
        self.assertIn("libLiteRtDispatch_GoogleTensor.so", dispatcher)

    def test_termux_dogfood_channel_keeps_signing_material_private(self):
        updater = (ROOT / "tools/termux_dogfood_update.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("--initialize-key", updater)
        self.assertIn("openssl genpkey", updater)
        self.assertIn("openssl pkcs8", updater)
        self.assertIn("gh secret set ANICLOUD_DOGFOOD_PRIVATE_KEY_B64", updater)
        self.assertIn("gh secret set ANICLOUD_DOGFOOD_CERTIFICATE_B64", updater)
        self.assertIn("AniCloudAI-e4b-cockpit-dogfood", updater)
        self.assertIn("symbolic-ref --quiet --short HEAD", updater)
        self.assertIn("ANICLOUD_DOGFOOD_BRANCH", updater)
        self.assertIn("--status success", updater)
        self.assertIn("if length == 0 then empty", updater)
        self.assertIn("| @tsv end", updater)
        self.assertNotIn('\\\\\"pending\\\\\"', updater)
        self.assertIn("Latest run %s is %s/%s", updater)
        self.assertIn('"$run_id" =~ ^[0-9]+$', updater)
        self.assertIn("sha256sum -c", updater)
        self.assertIn("termux-open --content-type", updater)
        self.assertNotIn("adb install", updater)
        self.assertNotIn("keytool", updater)
        self.assertNotIn("apksigner", updater)
        self.assertNotIn("storepass pass:", updater)


if __name__ == "__main__":
    unittest.main()
