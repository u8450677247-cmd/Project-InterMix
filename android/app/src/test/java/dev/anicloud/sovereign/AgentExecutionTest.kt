package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionTest {
    @Test
    fun staleOperationFromSupersededRunIsAuditedWithoutAdmission() {
        var ledger = AgentExecutionController.initial("LF-IDENTITY", "TASK-001")
        ledger = AgentExecutionController.beginRun(ledger, "TASK-001", logicalStepsCompleted = 0)
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val oldLease = AgentExecutionController.beginTransaction(
            ledger,
            operationCount = 1,
            explicitBundle = false,
            updatedAt = "old-pending",
        )

        ledger = AgentExecutionController.beginRun(
            oldLease.ledger,
            "TASK-001",
            logicalStepsCompleted = 0,
        )
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val stale = AgentExecutionController.admitOperationResult(
            ledger = ledger,
            identity = oldLease.operations.single(),
            succeeded = true,
            evidence = "late physical result",
            updatedAt = "late",
        )

        assertFalse(stale.accepted)
        assertTrue(stale.stale)
        assertEquals(1, stale.ledger.staleResultCount)
        assertEquals(AgentOperationState.Stale, stale.ledger.receipts.last().state)
    }

    @Test
    fun duplicateToolCallHasExactlyOneLogicalEffect() {
        var ledger = AgentExecutionController.initial("LF-DUPLICATE", "TASK-001")
        ledger = AgentExecutionController.beginRun(ledger, "TASK-001", logicalStepsCompleted = 0)
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val lease = AgentExecutionController.beginTransaction(
            ledger,
            operationCount = 1,
            explicitBundle = false,
            updatedAt = "pending",
        )
        val first = AgentExecutionController.admitOperationResult(
            lease.ledger,
            lease.operations.single(),
            succeeded = true,
            evidence = "verified once",
            updatedAt = "first",
        )
        val duplicate = AgentExecutionController.admitOperationResult(
            first.ledger,
            lease.operations.single(),
            succeeded = true,
            evidence = "verified twice",
            updatedAt = "duplicate",
        )

        assertTrue(first.accepted)
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.duplicate)
        assertEquals(1, duplicate.ledger.duplicateResultCount)
        assertEquals(1, duplicate.ledger.processedToolCallIds.size)
    }

    @Test
    fun staleReducerResultCannotAdvanceToolOrTaskProgress() {
        val manifest = MissionManifestCompiler.compile(
            "LF-REDUCER",
            "1. Alpha\nProduce alpha.\n2. Beta\nProduce beta.",
        ).let { compiled ->
            compiled.copy(tasks = compiled.tasks.mapIndexed { index, task ->
                task.copy(status = if (index == 0) MissionTaskStatus.InProgress else MissionTaskStatus.Pending)
            })
        }
        var ledger = AgentExecutionController.initial("LF-REDUCER", "TASK-001")
        ledger = AgentExecutionController.beginRun(ledger, "TASK-001", logicalStepsCompleted = 0)
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val oldLease = AgentExecutionController.beginTransaction(
            ledger,
            operationCount = 1,
            explicitBundle = false,
            updatedAt = "old",
        )
        ledger = AgentExecutionController.beginRun(
            oldLease.ledger,
            "TASK-001",
            logicalStepsCompleted = 0,
        )
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val mission = AgentMissionCheckpoint(
            id = "LF-REDUCER",
            rootPath = "mission",
            objective = "1. Alpha\nProduce alpha.\n2. Beta\nProduce beta.",
            mode = AnswerMode.Quality,
            missionManifest = manifest,
            executionLedger = ledger,
        )

        val rejected = MissionProgressReducer.recordToolOperation(
            current = mission,
            proposal = WorkspaceActionProposal(
                WorkspaceActionKind.CreateFile,
                "mission/001_alpha/output.md",
                content = "late content",
            ),
            result = WorkspaceActionResult("late verified result", artifactId = artifact(1)),
            succeeded = true,
            updatedAt = "late",
            identity = oldLease.operations.single(),
        )

        assertEquals(0, rejected.toolOperations)
        assertEquals(0, rejected.logicalStepsCompleted)
        assertTrue(rejected.missionManifest?.currentTask?.artifactIds.orEmpty().isEmpty())
        assertEquals(1, rejected.executionLedger?.staleResultCount)
    }

    @Test
    fun executionLedgerSurvivesCheckpointCodecRoundTrip() {
        var ledger = AgentExecutionController.initial("LF-CODEC", "TASK-001")
        ledger = AgentExecutionController.beginRun(ledger, "TASK-001", logicalStepsCompleted = 0)
        val run = requireNotNull(ledger.currentRunIdentity)
        ledger = AgentExecutionController.admitRunResult(ledger, run).ledger
        val lease = AgentExecutionController.beginTransaction(
            ledger,
            operationCount = 1,
            explicitBundle = false,
            updatedAt = "pending",
        )
        ledger = AgentExecutionController.admitOperationResult(
            lease.ledger,
            lease.operations.single(),
            succeeded = false,
            evidence = "provider transient",
            updatedAt = "failed",
        ).ledger
        val objective = "1. Alpha\nProduce alpha.\n2. Beta\nProduce beta."
        val manifest = MissionManifestCompiler.compile("LF-CODEC", objective).let { compiled ->
            compiled.copy(tasks = compiled.tasks.mapIndexed { index, task ->
                task.copy(status = if (index == 0) MissionTaskStatus.InProgress else MissionTaskStatus.Pending)
            })
        }
        val mission = AgentMissionCheckpoint(
            id = "LF-CODEC",
            rootPath = "mission",
            objective = objective,
            mode = AnswerMode.Quality,
            toolOperations = 1,
            toolFailures = 1,
            missionManifest = manifest,
            executionLedger = ledger,
        )

        val restored = AgentMissionCheckpointCodec.decode(AgentMissionCheckpointCodec.encode(mission))

        assertEquals(ledger, restored.executionLedger)
        assertEquals(AgentOperationState.Failed, restored.executionLedger?.receipts?.last()?.state)
    }

    @Test
    fun transactionCompensationAndUnresolvedSideEffectsAreDurableEvidence() {
        var ledger = AgentExecutionController.initial("LF-COMPENSATE", "TASK-001")
        ledger = AgentExecutionController.beginRun(ledger, "TASK-001", logicalStepsCompleted = 0)
        ledger = AgentExecutionController.admitRunResult(
            ledger,
            requireNotNull(ledger.currentRunIdentity),
        ).ledger
        val lease = AgentExecutionController.beginTransaction(
            ledger,
            operationCount = 2,
            explicitBundle = true,
            updatedAt = "pending",
        )
        ledger = AgentExecutionController.admitOperationResult(
            lease.ledger,
            lease.operations[0],
            succeeded = true,
            evidence = "write verified",
            updatedAt = "write",
        ).ledger
        ledger = AgentExecutionController.admitOperationResult(
            ledger,
            lease.operations[1],
            succeeded = true,
            evidence = "create verified",
            updatedAt = "create",
        ).ledger
        ledger = AgentExecutionController.recordCompensation(
            ledger,
            lease.operations[0],
            compensated = true,
            detail = "restored verified snapshot",
            updatedAt = "rollback",
        )
        ledger = AgentExecutionController.recordCompensation(
            ledger,
            lease.operations[1],
            compensated = false,
            detail = "created file retained because deletion is unavailable",
            updatedAt = "unresolved",
        )

        assertEquals(AgentOperationState.Compensated, ledger.receipts.takeLast(2).first().state)
        assertEquals(AgentOperationState.Uncompensated, ledger.receipts.last().state)
    }

    @Test
    fun workspaceCompensationPolicyRestoresOnlyVerifiedReplacements() {
        val write = WorkspaceActionProposal(
            WorkspaceActionKind.WriteFile,
            "mission/existing.md",
            content = "replacement",
        )
        val verifiedSnapshot = WorkspaceActionResult(
            detail = "written",
            snapshotName = "0123456789abcdef-1.snapshot",
            beforeSha256 = "0".repeat(64),
            afterSha256 = "1".repeat(64),
        )

        assertEquals(
            WorkspaceCompensationPolicy.RestoreSnapshot,
            workspaceCompensationPolicy(write, verifiedSnapshot),
        )
        assertEquals(
            WorkspaceCompensationPolicy.RecordUncompensated,
            workspaceCompensationPolicy(
                WorkspaceActionProposal(
                    WorkspaceActionKind.CreateFile,
                    "mission/new.md",
                    content = "new",
                ),
                WorkspaceActionResult("created"),
            ),
        )
    }

    private fun artifact(index: Int): String =
        "WA-${index.toString(16).uppercase().padStart(32, '0')}"
}
