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
        self.assertIn("versionCode = 21", app_build)
        self.assertIn('versionName = "0.8.10-workspace-write-integrity"', app_build)
        self.assertIn("jniLibs.useLegacyPackaging = true", app_build)
        self.assertIn("compose-bom:2026.03.01", app_build)
        self.assertIn('abiFilters += "arm64-v8a"', app_build)
        self.assertIn(
            'com.google.ai.edge.litertlm:litertlm-android:0.17.0', app_build
        )
        self.assertIn(
            'kotlinx-coroutines-android:1.11.0', app_build
        )
        self.assertIn(
            'kotlinx-coroutines-core-jvm:1.11.0', app_build
        )
        self.assertNotIn('kotlinx-coroutines-android:1.9.0', app_build)

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
                "com.termux.permission.RUN_COMMAND",
            },
        )
        services = root.findall("application/service")
        self.assertEqual(
            {service.attrib[android_name] for service in services},
            {".InferenceForegroundService", ".TermuxExecutionResultService"},
        )
        queries = root.find("queries")
        self.assertIsNotNone(queries)
        self.assertEqual(queries.find("package").attrib[android_name], "com.termux")

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
        self.assertIn('SlashCommand(\n        "/mission"', source)
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
        self.assertIn("npuPackageEligibility", policy)
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
        self.assertIn("maxNumTokens = ContextPhysicalTokens", runtime)
        self.assertIn("ContextOrchestrator.outputReserve(mode)", runtime)
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
        self.assertIn("MatrixSchemaVersion = 5", repository)
        self.assertIn("CREATE TABLE IF NOT EXISTS context_windows", repository)
        self.assertIn("undoLatestProfileChange", repository)

    def test_termux_execution_is_typed_approval_gated_and_result_bounded(self):
        manifest = (APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        bridge = (SOURCE / "TermuxExecutionBridge.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("com.termux.permission.RUN_COMMAND", manifest)
        self.assertIn("<INTERMIX_EXEC>", protocol)
        self.assertIn("InstallDependencies", bridge)
        self.assertIn("TermuxRunCommandPermission", bridge)
        self.assertIn("permissionGranted", bridge)
        self.assertIn("getApplicationInfo(TermuxPackage", bridge)
        self.assertIn("getServiceInfo(", bridge)
        self.assertIn("serviceAvailable", bridge)
        self.assertIn("ActivityResultContracts.RequestPermission", ui)
        self.assertIn("GRANT TERMUX COMMAND PERMISSION", ui)
        self.assertIn("[UNTRUSTED OUTPUT]", repository)
        self.assertIn("install_dependencies", protocol)
        self.assertIn("queueExecutionAction", view_model)
        self.assertIn("approveExecutionAction", view_model)
        self.assertIn("validateExecutionProposal", bridge)
        self.assertIn("PendingIntent.FLAG_ONE_SHOT", bridge)
        self.assertIn("timeout", bridge)
        self.assertIn("takeLast(32 * 1024)", repository)
        self.assertIn("CREATE TABLE IF NOT EXISTS execution_actions", repository)
        self.assertIn("APPROVE & RUN", ui)
        self.assertIn("NETWORK · REQUIRED AND INCLUDED IN THIS APPROVAL", ui)
        self.assertIn("DEVELOPER PLUGIN · TERMUX", ui)
        self.assertIn("cockpit.termuxBridge.enabled &&", ui)

    def test_native_conversation_sessions_preserve_history_and_reset_model_context(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(
            encoding="utf-8"
        )
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(
            encoding="utf-8"
        )
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("data class ConversationSessionSummary", repository)
        self.assertIn("fun listConversationSessions", repository)
        self.assertIn("fun startFreshConversation", repository)
        self.assertIn("fun openConversationSession", repository)
        self.assertIn("requireSessionTransitionIsSafe", repository)
        self.assertIn("SELECT s.id,s.title,COUNT(m.id),s.updated_at", repository)
        self.assertNotIn("DELETE FROM sessions", repository)
        self.assertIn("parseSessionTransitionCommand", view_model)
        self.assertIn("resetConversationFailure()", view_model)
        self.assertIn("lastContextDecision = null", view_model)
        self.assertIn('SlashCommand(\n        "/sessions"', ui)

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
        self.assertIn("recentMessageLimit = 12", policy)
        self.assertIn("allowMemoryFallback = false", policy)
        self.assertIn("<PROFILE_UPDATE>", protocol)
        self.assertIn("profilePayload", protocol)
        self.assertIn("applyExplicitProfileAdjustments", view_model)
        self.assertIn("[CONTEXT GATE ·", view_model)
        self.assertIn("WORKSPACE CONTEXT WITHHELD", view_model)
        self.assertIn("InteractionProfileCard", ui)
        self.assertIn("identity and permissions remain immutable", ui)
        self.assertIn("never grants tool authority", contract)

    def test_github_funding_metadata_is_valid_and_support_link_is_visible(self):
        funding = (ROOT / ".github/FUNDING.yml").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        support_handle = "yas" + "seh"
        self.assertEqual(funding, f"buy_me_a_coffee: {support_handle}\n")
        self.assertIn(f"https://buymeacoffee.com/{support_handle}", readme)

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
        self.assertIn("ControllerProtocol.chatPromptContract()", view_model)
        self.assertIn("ControllerProtocol.workspaceMissionPromptContract()", view_model)
        self.assertIn("Sovereign Core, the resident local intelligence", runtime)
        self.assertIn("warm, atmospheric, emotionally intelligent voice", runtime)
        self.assertIn("one purposeful emoji", runtime)
        self.assertIn("Do not overdecorate", runtime)
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
        self.assertIn('"APPROVE & WRITE"', ui)
        self.assertIn('Text("DENY")', ui)

    def test_long_forge_is_checkpointed_bounded_and_matrix_backed(self):
        guard = (SOURCE / "GenerationIntegrity.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(
            encoding="utf-8"
        )
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("data class AgentMissionCheckpoint", guard)
        self.assertIn("val maxActions: Int = 120", guard)
        self.assertIn("val actionTrail: List<String>", guard)
        self.assertIn("startAgentMission", repository)
        self.assertIn("guideAgentMission", repository)
        self.assertIn("recentMissionEvents", repository)
        self.assertIn('put("action_trail"', repository)
        self.assertIn("MaxMissionControllerCycles = 121", view_model)
        self.assertIn("if (inferenceCycle > 0) runtime.resetConversation()", view_model)
        self.assertIn("collectContextBoundResponse", view_model)
        self.assertIn("MaxMissionNoActionRetries = 4", view_model)
        self.assertIn("scopeWorkspaceMissionPath", view_model)
        self.assertIn("Continue without waiting for another click", view_model)
        self.assertIn("hasRecursiveActionTail", guard)
        self.assertIn("hasRecursiveActionTail", view_model)
        self.assertIn("guideActiveMission", view_model)
        self.assertIn("guidance != mission?.guidance", view_model)
        self.assertIn("PROJECT_STATE.md", view_model)
        self.assertIn("workspaceOpenPattern", protocol)
        self.assertIn("WorkSessionSurface", ui)
        self.assertIn("THE LONG FORGE", ui)
        self.assertIn("GUIDANCE / INTERRUPTION", ui)
        self.assertIn("QUEUE GUIDANCE", ui)
        self.assertIn("120 ACTIONS · MATRIX OFFLOAD · RECURSION GUARD", ui)

    def test_story_forge_is_one_file_controller_counted_and_crash_safe(self):
        story = (SOURCE / "StoryForge.kt").read_text(encoding="utf-8")
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "WorkspaceRepository.kt").read_text(encoding="utf-8")
        matrix = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("StoryForgeTargetChapters = 120", story)
        self.assertIn("ANICLOUD_CHAPTER:", story)
        self.assertIn("<INTERMIX_STORY>", protocol)
        self.assertIn("fun prepareStoryForge", repository)
        self.assertIn("fun appendStoryChapter", repository)
        self.assertIn("alreadyCommitted = true", repository)
        self.assertIn("Base64.getUrlEncoder().withoutPadding()", repository)
        self.assertIn("fun recordStoryChapter", matrix)
        self.assertIn("planState = commit.committedContinuity", matrix)
        self.assertIn("MissionCommand.Story", view_model)
        self.assertIn("Android owned every ordinal", view_model)
        self.assertIn("START 120-CHAPTER STORY FORGE", ui)
        self.assertIn("BENCHMARK LANE LOCKED", ui)
        self.assertIn('"CHAPTERS" else "ACTIONS"', ui)

    def test_numeric_matrix_calculates_outside_model_and_records_provenance(self):
        numeric = (SOURCE / "NumericMatrix.kt").read_text(encoding="utf-8")
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("BigDecimal", numeric)
        self.assertIn("MathContext(34, RoundingMode.HALF_EVEN)", numeric)
        self.assertNotIn("ScriptEngine", numeric)
        self.assertIn("<INTERMIX_CALC>", protocol)
        self.assertIn("CREATE TABLE IF NOT EXISTS numeric_calculations", repository)
        self.assertIn("fun recordCalculation", repository)
        self.assertIn("DeterministicCalculator.evaluate", view_model)
        self.assertIn('"/calc"', view_model)
        self.assertIn("NUMERIC MATRIX", ui)

    def test_recent_committed_chat_is_reserved_before_retrieval_context(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("Immediate continuity is a hard reservation", repository)
        self.assertIn("val fittedRecent", repository)
        self.assertIn("prioritizedRecentRecall", view_model)
        self.assertIn("recentBody.takeLast(recentBudget)", view_model)

    def test_work_session_keeps_interrupted_generation_visible_and_auditable(self):
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("val raw = accumulated.toString()", view_model)
        self.assertIn("it.copy(streamText = visible)", view_model)
        self.assertIn('source = if (mission == null) "integrity" else "mission"', view_model)
        self.assertIn('source = if (missionWasActive) "mission" else "chat"', view_model)
        self.assertIn("mission?.active == true && cockpit.streamText.isNotBlank()", ui)
        self.assertIn("INTERRUPTED DRAFT · NOT A VERIFIED FINAL ANSWER", ui)

    def test_controller_cycles_are_append_only_and_work_session_is_truthful(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("commitControllerCycleVisible", view_model)
        self.assertIn("missionActionReport", view_model)
        self.assertIn("preserveInterruptedDraft", view_model)
        self.assertIn("Status badges such as [INFO] are presentation metadata", view_model)
        self.assertIn('source = "${source.take(32)}-draft"', view_model)
        self.assertIn("source NOT LIKE '%-draft'", repository)
        self.assertIn("fun loadMessages(limit: Int = 500)", repository)
        self.assertIn("RecentConversationCharacterBudget = 4_096", repository)
        self.assertIn('it.source.startsWith("mission")', ui)
        self.assertNotIn('it.speaker != ChatSpeaker.User', ui)
        self.assertIn("AutoFollowTail", ui)

    def test_restored_transcripts_reveal_the_true_tail_and_keep_an_escape_hatch(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("initialRevealPending", ui)
        self.assertIn("first { it > tailIndex }", ui)
        self.assertIn("listState.isScrollInProgress to !listState.canScrollForward", ui)
        self.assertIn("listState.scrollToItem(tailIndex)", ui)
        self.assertIn('item(key = "chat-transcript-tail")', ui)
        self.assertIn('item(key = "work-session-transcript-tail")', ui)
        self.assertGreaterEqual(ui.count("LatestTranscriptButton("), 3)
        self.assertIn('Text("↓"', ui)
        self.assertIn('contentDescription = "Jump to latest message"', ui)
        self.assertGreaterEqual(ui.count("Alignment.BottomCenter"), 2)
        self.assertIn("bottom = 72.dp", ui)

    def test_context_orchestrator_is_bounded_private_and_measured(self):
        orchestrator = (SOURCE / "ContextOrchestrator.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        runtime = (SOURCE / "LiteRtModelRuntime.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("ContextStrategyPackSize = 20", orchestrator)
        self.assertIn("CurrentRequestPriority", orchestrator)
        self.assertIn("FreshControllerConversation", orchestrator)
        self.assertIn("PrivatePayloadShield", orchestrator)
        self.assertIn("fitHeadAndTail", orchestrator)
        self.assertIn("recordContextWindow", repository)
        self.assertIn("Records measurements only", repository)
        self.assertIn("ContextOrchestrator.outputReserve(mode)", runtime)
        self.assertIn("privatePayload = lane == ContextLane.Story", view_model)
        self.assertIn("buildWorkspaceMissionPrompt", view_model)
        self.assertIn("buildStoryForgePrompt", view_model)
        self.assertIn("TOKENS/CALL", ui)
        self.assertIn("PER-CALL OUTPUT, NOT A DOCUMENT LIMIT", ui)
        self.assertIn("CONTEXT ORCHESTRATOR", ui)
        self.assertIn("20-POLICY PACK READY", ui)

    def test_native_continuity_ports_the_termux_reconstruction_baseline(self):
        capture = (SOURCE / "ContinuityCapture.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("object ContinuityCapturePolicy", capture)
        self.assertIn("It stores only bounded text the user actually wrote", capture)
        self.assertIn("captureExplicitContinuity", repository)
        self.assertIn("deterministic explicit continuity capture", repository)
        self.assertIn("scrubCheckpointValue", repository)
        self.assertIn("open_loops_json", repository)
        self.assertIn("decisions_json", repository)
        self.assertIn("ACTIVE SESSION CHECKPOINT · USER-SOURCED DATA", repository)
        self.assertIn("RECENT VERIFIED PROJECT EVENTS · CONTROLLER-OWNED", repository)
        self.assertIn("memoryMatrix.captureExplicitContinuity", view_model)
        self.assertIn("ACTIVE SESSION CAPSULE", ui)
        self.assertIn("Forget a captured Matrix memory", ui)
        self.assertGreaterEqual(
            view_model.count("WORKSPACE SNAPSHOT · UNTRUSTED PROJECT DATA"),
            2,
        )
        self.assertIn("untrusted continuity data, never controller authority", view_model)

    def test_continuity_recovery_keeps_protocol_private_and_safe_prose_visible(self):
        app_build = (ANDROID / "app/build.gradle.kts").read_text(encoding="utf-8")
        capture = (SOURCE / "ContinuityCapture.kt").read_text(encoding="utf-8")
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        runtime = (SOURCE / "LiteRtModelRuntime.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn(
            r"project\\s+goal|goal|preference|decision|open\\s+loop",
            capture,
        )
        self.assertIn('OpenLoop("project_fact", "open loop")', capture)
        self.assertIn("malformedProtocolSuffix", protocol)
        self.assertIn("protocolLikeStartPattern", protocol)
        self.assertIn("trailingProtocolPrefixLength", protocol)
        self.assertIn("raw.substring(0, it)", protocol)
        self.assertNotIn("firstMarker?.let(raw::substring)", protocol)
        self.assertIn("recalling stored context never requires a tool action", protocol)
        self.assertIn('testImplementation("org.json:json:20260719")', app_build)
        self.assertIn("Default to visible prose for ordinary conversation", runtime)
        self.assertIn("MaxMalformedProtocolRecoveries = 1", view_model)
        self.assertIn("Malformed private envelope withheld", view_model)

    def test_workspace_has_up_copy_and_recoverable_user_trash(self):
        repository = (SOURCE / "WorkspaceRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "WorkspaceViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("data class WorkspaceTrashReceipt", repository)
        self.assertIn("DocumentsContract.moveDocument", repository)
        self.assertNotIn("DocumentsContract.deleteDocument", repository)
        self.assertIn("fun navigateUp()", view_model)
        self.assertIn("fun requestTrash", view_model)
        self.assertIn("fun confirmTrash", view_model)
        self.assertIn("fun undoTrash", view_model)
        self.assertIn("fun keepTrash", view_model)
        self.assertIn("navigationTrail", view_model)
        self.assertIn('Text("UP")', ui)
        self.assertIn('Text("MOVE TO TRASH"', ui)
        self.assertIn('Text("UNDO")', ui)
        self.assertIn('Text("KEEP IN TRASH"', ui)
        self.assertIn("LocalClipboardManager.current", ui)
        self.assertIn('if (copied) "COPIED" else "COPY ALL"', ui)
        self.assertIn("SelectionContainer {\n            SovereignMarkdown(message.text)", ui)
        self.assertIn("BackHandler(", ui)
        self.assertIn("fun createUserDirectory", repository)
        self.assertIn("fun requestNewFolder", view_model)
        self.assertIn('Text("NEW FOLDER")', ui)

    def test_workspace_write_grants_are_visible_nonempty_and_byte_verified(self):
        protocol = (SOURCE / "ControllerProtocol.kt").read_text(encoding="utf-8")
        repository = (SOURCE / "WorkspaceRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertIn("create an empty placeholder", protocol)
        self.assertIn('!payload.has("content")', protocol)
        self.assertIn("content.isBlank()", protocol)
        self.assertIn("Created and verified", repository)
        self.assertIn("Wrote and verified", repository)
        self.assertGreaterEqual(repository.count("contentEquals"), 3)
        self.assertIn("scopeWorkspaceMissionPath", repository)
        self.assertIn("scopeWorkspaceMissionPath(normalizedProposal.path", view_model)
        self.assertIn("stage = ModelStage.Generating, detail = acceptedDetail", view_model)
        self.assertIn("Approval unlocks when the active", view_model)
        self.assertIn("waitingForResponse = cockpit.isGenerating", ui)
        self.assertIn('"WAIT FOR SAFE BOUNDARY"', ui)
        self.assertIn('"APPROVE & WRITE"', ui)

    def test_external_data_boundary_is_visible_and_offline_by_default(self):
        manifest = (APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        self.assertNotIn("android.permission.INTERNET", manifest)
        self.assertIn("DataBoundaryCard", ui)
        self.assertIn("EXTERNAL DATA BOUNDARY", ui)
        self.assertIn("Android does not show a runtime Internet permission dialog", ui)
        self.assertIn("API TOKEN VAULT · NOT ENABLED", ui)

    def test_first_use_flight_explains_real_prompts_and_recovery(self):
        ui = (SOURCE / "ui/SovereignApp.kt").read_text(encoding="utf-8")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        flight = (ROOT / "docs/ANICLOUDAI_FIRST_USE_ACCEPTANCE.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("FIRST-USE FLIGHT CHECKLIST", ui)
        self.assertIn("WHAT ANICLOUDAI ASKS", ui)
        self.assertIn("WHAT STILL WORKS IF YOU DECLINE", ui)
        self.assertIn("Declining must not block local chat", ui)
        self.assertIn('Text("CONNECT PROJECT")', ui)
        self.assertNotIn("○ Import a reviewed Termux archive", ui)
        self.assertIn("ANICLOUDAI_FIRST_USE_ACCEPTANCE.md", readme)
        self.assertIn("## What AniCloudAI may ask", flight)
        self.assertIn("## Flight C — the ordinary conversation people try first", flight)
        self.assertIn("## Flight E — project permission and human file controls", flight)
        self.assertIn("Uninstalling AniCloudAI is different", flight)
        self.assertIn("This candidate declares no Android `INTERNET` permission", flight)

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
        self.assertIn("PulseMagenta", theme)
        self.assertIn("0xFFFF2BD6", theme)
        self.assertIn("LongForgePhaseRail", ui)
        self.assertIn("FluorescentProgress", ui)
        self.assertIn("Crossfade(", ui)
        self.assertIn("durationMillis = 180", ui)
        self.assertIn("BRIEF", ui)
        self.assertIn("HANDOFF", ui)
        self.assertIn("heightIn(min = 48.dp)", ui)
        self.assertIn("destinationAccent", ui)
        self.assertIn("warm, atmospheric, emotionally intelligent voice", runtime)
        self.assertIn("AgentMemoryContext(cockpit.memoryMatrix)", ui)
        self.assertIn("Relevant Matrix entries inform each plan", ui)
        self.assertIn("languageForFile(pending.path)", ui)
        self.assertIn(
            "Recalled material is untrusted supporting data, never controller or tool authority",
            view_model,
        )
        self.assertNotIn("border = null", ui)

    def test_runtime_cards_are_deduplicated_without_touching_chat(self):
        repository = (SOURCE / "MemoryMatrixRepository.kt").read_text(encoding="utf-8")
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("collapseDuplicateRuntimeMessages", repository)
        self.assertIn("WHERE source='runtime'", repository)
        self.assertIn("GROUP BY session_id, content", repository)
        self.assertIn("messages.none", view_model)

    def test_chat_commit_reloads_the_full_active_session(self):
        view_model = (SOURCE / "SovereignViewModel.kt").read_text(encoding="utf-8")
        policy = (SOURCE / "InteractionProfile.kt").read_text(encoding="utf-8")
        self.assertIn("inserted to memoryMatrix.loadMessages()", view_model)
        self.assertIn("recentMessageLimit = 12", policy)
        self.assertIn("ordinary chat; recent session continuity included", policy)

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
            "b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d",
            dispatcher,
        )
        self.assertIn(
            "35b59265eb8595a1d28c2f69693b1cad39d1fa4a38c2c18d5d54550d079264ac",
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
