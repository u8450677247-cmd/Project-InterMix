package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerRecoveryTest {
    @Test
    fun structuredTransientFailureRetriesWithoutStringClassification() {
        val failure = ControllerFailure(
            code = ControllerErrorCode.PROVIDER_TRANSIENT,
            detail = "SAF provider returned a retryable busy signal.",
            evidence = "provider_status=busy",
            rejectedProposal = "read_file mission/input.md",
        )

        val decision = MissionRecoveryRouter.classify(failure, emptyList())

        assertEquals(ControllerErrorCode.PROVIDER_TRANSIENT, decision.errorCode)
        assertEquals(MissionRecoveryDisposition.RetryTransient, decision.disposition)
        assertTrue(decision.automatic)
    }

    @Test
    fun identicalFailedProposalIsNotRetriedUnchanged() {
        val failure = ControllerFailure(
            code = ControllerErrorCode.TOOL_SCHEMA_INVALID,
            detail = "Required content parameter is missing.",
            rejectedProposal = "artifact_create TASK-001 cover_letter",
        )
        val first = MissionRecoveryRouter.classify(failure, emptyList())
        val repeated = MissionRecoveryRouter.classify(failure, listOf(first.signature))

        assertEquals(MissionRecoveryDisposition.ModelRepair, first.disposition)
        assertTrue(first.automatic)
        assertEquals(MissionRecoveryDisposition.Exhausted, repeated.disposition)
        assertFalse(repeated.automatic)
        assertEquals(first.signature, repeated.signature)
    }

    @Test
    fun securityFailureNeverWidensAuthorityAutomatically() {
        val decision = MissionRecoveryRouter.classify(
            ControllerFailure(
                code = ControllerErrorCode.OUTSIDE_AUTHORITY,
                detail = "Requested path escapes the granted workspace.",
                rejectedProposal = "workspace_read ../secrets",
            ),
            emptyList(),
        )

        assertEquals(MissionRecoveryDisposition.SecurityStop, decision.disposition)
        assertFalse(decision.automatic)
    }

    @Test
    fun typedRecoveryStateSurvivesMissionCheckpointRoundTrip() {
        val objective = "1. Alpha\nProduce alpha.\n2. Beta\nProduce beta."
        val manifest = MissionManifestCompiler.compile("LF-RECOVERY", objective).let { compiled ->
            compiled.copy(tasks = compiled.tasks.mapIndexed { index, task ->
                task.copy(status = if (index == 0) MissionTaskStatus.InProgress else MissionTaskStatus.Pending)
            })
        }
        val original = AgentMissionCheckpoint(
            id = "LF-RECOVERY",
            rootPath = "mission",
            objective = objective,
            mode = AnswerMode.Quality,
            missionManifest = manifest,
            executionLedger = AgentExecutionController.initial("LF-RECOVERY", "TASK-001"),
        )
        val decision = MissionRecoveryRouter.classify(
            ControllerFailure(
                code = ControllerErrorCode.MISSING_PARENT,
                detail = "One bounded parent directory is missing.",
                rejectedProposal = "artifact_create TASK-001 notes",
            ),
            emptyList(),
        )
        val recovered = MissionProgressReducer.recordRecovery(original, decision, "recovered")

        val restored = AgentMissionCheckpointCodec.decode(
            AgentMissionCheckpointCodec.encode(recovered),
        )

        assertEquals(ControllerErrorCode.MISSING_PARENT, restored.lastControllerErrorCode)
        assertEquals(MissionRecoveryDisposition.DeterministicRepair, restored.lastRecoveryDisposition)
        assertEquals(1, restored.recoveries)
    }
}
