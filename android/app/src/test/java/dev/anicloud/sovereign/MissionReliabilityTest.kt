package dev.anicloud.sovereign.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionReliabilityTest {
    @Test
    fun a_longObjectiveRetainsAllTwelveTasksBeyondTheLegacyPrefix() {
        val objective = twelveTaskObjective()
        assertTrue(objective.indexOf("12. Ziel zwölf") > 3_600)

        val manifest = MissionManifestCompiler.compile("LF-LONG", objective)

        assertEquals(12, manifest.expectedTaskCount)
        assertEquals("TASK-012", manifest.tasks.last().id)
        assertTrue(manifest.tasks.last().instructions.contains("LATE-TASK-12-PROVENANCE"))
        val selectedLateTask = missionTaskSourceForPrompt(manifest.tasks.last(), 1_200)
        assertTrue(selectedLateTask.contains("TASK-012"))
        assertTrue(selectedLateTask.contains("LATE-TASK-12-PROVENANCE"))
        assertEquals(missionObjectiveSha256(objective), manifest.originalObjectiveSha256)
    }

    @Test
    fun b_manifestAndExactObjectiveSurviveDurableRoundTrip() {
        val objective = twelveTaskObjective()
        var mission = mission(objective)
        mission = successfulTool(mission, 1, "mission/001_ziel-eins/package.md")
        mission = MissionProgressReducer.recordCheckpoint(
            mission,
            MissionCheckpointProposal(
                MissionCheckpointKind.TaskComplete,
                "TASK-001",
                "First package verified",
            ),
            "t2",
        )

        val restored = AgentMissionCheckpointCodec.decode(AgentMissionCheckpointCodec.encode(mission))

        assertEquals(objective, restored.objective)
        assertEquals(12, restored.missionManifest?.expectedTaskCount)
        assertEquals(1, restored.missionManifest?.completedTaskCount)
        assertEquals("TASK-002", restored.missionManifest?.currentTaskId)
        assertEquals(MissionTaskStatus.Completed, restored.missionManifest?.tasks?.first()?.status)
        assertEquals(missionObjectiveSha256(objective), restored.missionManifest?.originalObjectiveSha256)
    }

    @Test
    fun c_toolOperationsDoNotConsumeLogicalAgentSteps() {
        var mission = mission(twoTaskObjective())
        repeat(4) { index ->
            mission = successfulTool(mission, index + 1, "mission/001_alpha/evidence-$index.md")
        }

        assertEquals(4, mission.toolOperations)
        assertEquals(0, mission.logicalStepsCompleted)
        mission = MissionProgressReducer.recordCheckpoint(
            mission,
            MissionCheckpointProposal(
                MissionCheckpointKind.LogicalStep,
                "TASK-001",
                "Four audited operations form one verified reasoning boundary",
            ),
            "checkpoint",
        )
        assertEquals(1, mission.logicalStepsCompleted)
        assertEquals(4, mission.toolOperations)
    }

    @Test
    fun d_failedToolIsAuditedWithoutInventingLogicalProgress() {
        val original = mission(twoTaskObjective())
        val failed = MissionProgressReducer.recordToolOperation(
            current = original,
            proposal = WorkspaceActionProposal(
                WorkspaceActionKind.WriteFile,
                "mission/001_alpha/missing.md",
                content = "attempted content",
            ),
            result = WorkspaceActionResult("FAILED: target was absent"),
            succeeded = false,
            updatedAt = "failed",
        )

        assertEquals(1, failed.toolOperations)
        assertEquals(1, failed.toolFailures)
        assertEquals(0, failed.logicalStepsCompleted)
        assertEquals(0L, failed.writtenBytes)
        assertFalse(failed.lastToolSucceeded)
    }

    @Test
    fun e_boundedTransactionParsesAuditsEachOperationAndCheckpointsOnce() {
        val parsed = ControllerProtocol.parse(
            """
            <INTERMIX_TRANSACTION>{
              "reason":"one coherent application package",
              "operations":[
                {"kind":"create_file","task_id":"TASK-001","artifact_type":"cover_letter","display_name":"McDonald's — Küchenhilfe","content":"letter"},
                {"kind":"create_file","task_id":"TASK-001","artifact_type":"email_draft","content":"email"},
                {"kind":"create_file","task_id":"TASK-001","artifact_type":"notes","content":"notes"}
              ],
              "checkpoint":{"kind":"task_complete","task_id":"TASK-001","summary":"package verified"}
            }</INTERMIX_TRANSACTION>
            """.trimIndent(),
        )
        val transaction = requireNotNull(parsed.workspaceTransaction)
        assertEquals(3, transaction.operations.size)
        assertEquals("McDonald's — Küchenhilfe", transaction.operations.first().displayName)

        var mission = mission(twoTaskObjective())
        transaction.operations.forEachIndexed { index, proposal ->
            mission = MissionProgressReducer.recordToolOperation(
                current = mission,
                proposal = proposal.copy(path = "mission/001_alpha/${proposal.artifactType}.md"),
                result = WorkspaceActionResult("verified", artifactId = artifact(index + 1)),
                succeeded = true,
                updatedAt = "op-$index",
            )
        }
        mission = MissionProgressReducer.recordCheckpoint(
            mission,
            requireNotNull(transaction.checkpoint),
            "bundle-checkpoint",
        )

        assertEquals(3, mission.toolOperations)
        assertEquals(1, mission.logicalStepsCompleted)
        assertEquals(1, mission.missionManifest?.completedTaskCount)
        assertEquals("TASK-002", mission.missionManifest?.currentTaskId)
    }

    @Test
    fun f_partialTransactionCannotRecordFalseCheckpoint() {
        var mission = mission(twoTaskObjective())
        mission = successfulTool(mission, 1, "mission/001_alpha/one.md")
        mission = MissionProgressReducer.recordToolOperation(
            current = mission,
            proposal = WorkspaceActionProposal(
                WorkspaceActionKind.CreateFile,
                "mission/001_alpha/two.md",
                content = "two",
            ),
            result = WorkspaceActionResult("FAILED: provider race"),
            succeeded = false,
            updatedAt = "partial-failure",
        )

        assertThrows(IllegalArgumentException::class.java) {
            MissionProgressReducer.recordCheckpoint(
                mission,
                MissionCheckpointProposal(
                    MissionCheckpointKind.TaskComplete,
                    "TASK-001",
                    "must not commit",
                ),
                "invalid",
            )
        }
        assertEquals(2, mission.toolOperations)
        assertEquals(1, mission.toolFailures)
        assertEquals(0, mission.logicalStepsCompleted)
        assertEquals(0, mission.missionManifest?.completedTaskCount)
    }

    @Test
    fun g_unicodeWorkspaceAndDisplayNamesRemainRepresentable() {
        val unicodePath = "Arbeit/Küchenhilfe/Gründinger/McDonald's.md"
        assertEquals(unicodePath, normalizeWorkspacePath(unicodePath))
        assertEquals("Küchenhilfe", normalizeWorkspaceLeafName("Küchenhilfe"))

        val objective = "1. McDonald's — Küchenhilfe bei Gründinger\nDeutsch erhalten.\n2. Zweites Ziel\nWeiter."
        val manifest = MissionManifestCompiler.compile("LF-UNICODE", objective)
        assertEquals("McDonald's — Küchenhilfe bei Gründinger", manifest.tasks.first().displayLabel)
        val generated = resolveManifestArtifactPath(
            manifest,
            "Bewerbungen",
            "TASK-001",
            "cover_letter",
        )
        assertTrue(generated.startsWith("Bewerbungen/001_mcdonald-s-kuchenhilfe-bei-grundinger/"))
        assertTrue(generated.endsWith("cover_letter.md"))
        assertFalse(
            resolveManifestArtifactPath(manifest, "Bewerbungen", "TASK-001", "cover-letter") ==
                resolveManifestArtifactPath(manifest, "Bewerbungen", "TASK-001", "cover_letter"),
        )
    }

    @Test
    fun h_unicodeSupportDoesNotWeakenTraversalOrContainment() {
        listOf(
            "../Küchenhilfe.md",
            "/Arbeit/Küchenhilfe.md",
            "C:/Arbeit/Küchenhilfe.md",
            "Arbeit/bad\u0007name.md",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeWorkspacePath(invalid)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            scopeWorkspaceMissionPath("Bewerbungen_alias/Küchenhilfe.md", "Bewerbungen-alias")
        }
        assertEquals(
            "Bewerbungen/Küchenhilfe/Brief.md",
            scopeWorkspaceMissionPath("Küchenhilfe/Brief.md", "Bewerbungen"),
        )
    }

    @Test
    fun i_recoverableFailureRetriesAutomaticallyWithoutPausing() {
        val original = mission(twoTaskObjective()).copy(lastVerifiedCheckpoint = "inputs verified")
        val decision = MissionRecoveryRouter.classify(
            "write_file to create_file because target was absent",
            original.recoveryTrail,
        )
        val recovered = MissionProgressReducer.recordRecovery(original, decision, "recovery")

        assertEquals(MissionRecoveryDisposition.DeterministicRepair, decision.disposition)
        assertTrue(decision.automatic)
        assertEquals(AgentMissionStatus.Running, recovered.status)
        assertEquals(1, recovered.recoveries)
        assertEquals("inputs verified", recovered.lastVerifiedCheckpoint)
    }

    @Test
    fun j_distinctRecoveryExhaustionPausesAtLastGoodCheckpoint() {
        var mission = mission(twoTaskObjective()).copy(lastVerifiedCheckpoint = "durable-good-state")
        repeat(MaximumMissionRecoveryAttempts + 1) { index ->
            val decision = MissionRecoveryRouter.classify(
                "ordinary provider disagreement ${index + 1}",
                mission.recoveryTrail,
            )
            mission = MissionProgressReducer.recordRecovery(mission, decision, "recovery-$index")
            if (index < MaximumMissionRecoveryAttempts) {
                assertTrue(decision.automatic)
                assertEquals(AgentMissionStatus.Running, mission.status)
            } else {
                assertEquals(MissionRecoveryDisposition.Exhausted, decision.disposition)
            }
        }

        assertEquals(AgentMissionStatus.Paused, mission.status)
        assertEquals("durable-good-state", mission.lastVerifiedCheckpoint)
        assertEquals(MaximumMissionRecoveryAttempts, mission.recoveries)
    }

    @Test
    fun k_twelveTaskMissionCannotCompleteAtEleven() {
        var mission = mission(twelveTaskObjective())
        repeat(11) { index ->
            mission = successfulTool(
                mission,
                index + 1,
                "mission/${(index + 1).toString().padStart(3, '0')}_task/output.md",
            )
            mission = MissionProgressReducer.recordCheckpoint(
                mission,
                MissionCheckpointProposal(
                    MissionCheckpointKind.TaskComplete,
                    "TASK-${(index + 1).toString().padStart(3, '0')}",
                    "Task ${index + 1} verified",
                ),
                "checkpoint-${index + 1}",
            )
        }
        assertEquals(11, mission.missionManifest?.completedTaskCount)
        assertFalse(requireNotNull(mission.missionManifest).complete)
        assertEquals(AgentMissionStatus.Running, mission.status)
        val forged = mission.copy(status = AgentMissionStatus.Completed)
        assertThrows(IllegalArgumentException::class.java) {
            AgentMissionCheckpointCodec.decode(AgentMissionCheckpointCodec.encode(forged))
        }

        mission = successfulTool(mission, 12, "mission/012_task/output.md")
        mission = MissionProgressReducer.recordCheckpoint(
            mission,
            MissionCheckpointProposal(
                MissionCheckpointKind.TaskComplete,
                "TASK-012",
                "Task 12 verified",
            ),
            "checkpoint-12",
        )
        assertEquals(12, mission.missionManifest?.completedTaskCount)
        assertTrue(requireNotNull(mission.missionManifest).complete)
        assertEquals(AgentMissionStatus.Completed, mission.status)
    }

    @Test
    fun l_restartContinuesAtLastVerifiedCursorWithoutRepeatingCompletedWork() {
        var beforeRestart = mission(twoTaskObjective())
        beforeRestart = successfulTool(beforeRestart, 1, "mission/001_alpha/output.md")
        beforeRestart = MissionProgressReducer.recordCheckpoint(
            beforeRestart,
            MissionCheckpointProposal(
                MissionCheckpointKind.TaskComplete,
                "TASK-001",
                "Alpha verified",
            ),
            "before-restart",
        )

        var restored = AgentMissionCheckpointCodec.decode(
            AgentMissionCheckpointCodec.encode(beforeRestart),
        )
        assertEquals(AgentMissionStatus.Running, restored.status)
        assertEquals("TASK-002", restored.missionManifest?.currentTaskId)
        assertEquals(1, restored.logicalStepsCompleted)
        assertEquals(MissionTaskStatus.Completed, restored.missionManifest?.task("TASK-001")?.status)

        restored = successfulTool(restored, 2, "mission/002_beta/output.md")
        restored = MissionProgressReducer.recordCheckpoint(
            restored,
            MissionCheckpointProposal(
                MissionCheckpointKind.TaskComplete,
                "TASK-002",
                "Beta verified after restart",
            ),
            "after-restart",
        )
        assertEquals(2, restored.logicalStepsCompleted)
        assertEquals(2, restored.missionManifest?.completedTaskCount)
        assertEquals(AgentMissionStatus.Completed, restored.status)
    }

    @Test
    fun transactionLimitRejectsUnboundedBundles() {
        val operations = (1..MaximumMissionTransactionOperations + 1).joinToString(",") { index ->
            "{\"kind\":\"read_file\",\"path\":\"file-$index.md\"}"
        }
        val parsed = ControllerProtocol.parse(
            "$WorkspaceTransactionOpenMarker{\"operations\":[$operations]}$WorkspaceTransactionCloseMarker",
        )
        assertEquals(null, parsed.workspaceTransaction)
        assertTrue(parsed.malformedProtocolSuffix)
    }

    @Test
    fun legacyWorkspaceActionsMigrateToToolOperationsNotLogicalSteps() {
        val encoded = JSONObject(AgentMissionCheckpointCodec.encode(mission(twoTaskObjective())))
        encoded.remove("logical_agent_steps")
        encoded.remove("tool_operations")
        encoded.remove("mission_manifest")
        encoded.put("completed_actions", 7)

        val migrated = AgentMissionCheckpointCodec.decode(encoded.toString())

        assertEquals(0, migrated.logicalStepsCompleted)
        assertEquals(7, migrated.toolOperations)
        assertEquals(2, migrated.missionManifest?.expectedTaskCount)
        assertEquals("TASK-001", migrated.missionManifest?.currentTaskId)
    }

    @Test
    fun directoryAndListingEvidenceCannotMasqueradeAsACompletedDeliverable() {
        var current = mission(twoTaskObjective())
        current = MissionProgressReducer.recordToolOperation(
            current,
            WorkspaceActionProposal(WorkspaceActionKind.CreateDirectory, "mission/001_alpha"),
            WorkspaceActionResult("directory verified", artifactId = artifact(1)),
            succeeded = true,
            updatedAt = "directory",
        )
        current = MissionProgressReducer.recordToolOperation(
            current,
            WorkspaceActionProposal(WorkspaceActionKind.ListFiles, "mission/001_alpha"),
            WorkspaceActionResult("listing verified", artifactId = artifact(1)),
            succeeded = true,
            updatedAt = "listing",
        )

        assertTrue(current.missionManifest?.currentTask?.artifactIds.orEmpty().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            MissionProgressReducer.recordCheckpoint(
                current,
                MissionCheckpointProposal(
                    MissionCheckpointKind.TaskComplete,
                    "TASK-001",
                    "An empty folder is not a reviewable deliverable",
                ),
                "invalid-directory-checkpoint",
            )
        }
    }

    @Test
    fun manifestLimitRejectsRatherThanSilentlyDroppingTaskOneHundredTwentyOne() {
        val oversized = (1..MaximumMissionTasks + 1).joinToString("\n") { ordinal ->
            "$ordinal. Required task $ordinal"
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            MissionManifestCompiler.compile("LF-OVERSIZED", oversized)
        }

        assertTrue(failure.message.orEmpty().contains("will not silently discard"))
    }

    @Test
    fun commonJobMarkerPunctuationCompilesIntoExactTasks() {
        val manifest = MissionManifestCompiler.compile(
            "LF-JOBS",
            "JOB-01: Küchenhilfe\nPreserve German.\nJOB-02: Gründinger\nPreserve the source.",
        )

        assertEquals(2, manifest.expectedTaskCount)
        assertEquals("Küchenhilfe", manifest.tasks.first().displayLabel)
        assertEquals("Gründinger", manifest.tasks.last().displayLabel)
    }

    private fun mission(objective: String): AgentMissionCheckpoint {
        val compiled = MissionManifestCompiler.compile("LF-TEST", objective)
        val manifest = compiled.copy(
            tasks = compiled.tasks.mapIndexed { index, task ->
                task.copy(
                    status = if (index == 0) MissionTaskStatus.InProgress else MissionTaskStatus.Pending,
                )
            },
        )
        return AgentMissionCheckpoint(
            id = "LF-TEST",
            rootPath = "mission",
            objective = objective,
            mode = AnswerMode.Quality,
            missionManifest = manifest,
            updatedAt = "start",
        )
    }

    private fun successfulTool(
        current: AgentMissionCheckpoint,
        index: Int,
        path: String,
    ): AgentMissionCheckpoint = MissionProgressReducer.recordToolOperation(
        current = current,
        proposal = WorkspaceActionProposal(
            WorkspaceActionKind.CreateFile,
            path,
            content = "verified-$index",
        ),
        result = WorkspaceActionResult("verified", artifactId = artifact(index)),
        succeeded = true,
        updatedAt = "tool-$index",
    )

    private fun artifact(index: Int): String =
        "WA-${index.toString(16).uppercase().padStart(32, '0')}"

    private fun twoTaskObjective(): String = """
        Global rule: preserve verified evidence.
        1. Alpha
        Produce the alpha artifact.
        2. Beta
        Produce the beta artifact.
    """.trimIndent()

    private fun twelveTaskObjective(): String = buildString {
        appendLine("Global constraints: preserve every supplied target and its exact source instructions.")
        appendLine("Durable preface follows; it deliberately exceeds the old prompt prefix.")
        appendLine("P".repeat(3_900))
        (1..12).forEach { ordinal ->
            val label = when (ordinal) {
                1 -> "Ziel eins"
                12 -> "Ziel zwölf"
                else -> "Ziel $ordinal"
            }
            appendLine("$ordinal. $label")
            appendLine("Unique instructions for target $ordinal; token LATE-TASK-$ordinal-PROVENANCE.")
        }
    }.trim()
}
