package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionGrantTest {
    private val now = 1_800_000_000_000L
    private val grant = ExecutionGrant(
        id = "EG-0123456789ABCDEF0123456789ABCDEF",
        missionId = "LF-CODE",
        workspaceRoot = "project",
        allowedKinds = setOf(ExecutionKind.Build, ExecutionKind.Test, ExecutionKind.Lint),
        allowedCommandPrefixes = setOf("./gradlew", "python -m pytest"),
        maxExecutions = 8,
        maxWallTimeSeconds = 3_600,
        maxOutputBytes = 1_000_000,
        issuedAtEpochMillis = now,
        expiresAtEpochMillis = now + 28_800_000,
    )

    @Test
    fun boundedBuildConsumesGrantWithoutHumanAuthority() {
        val admission = ExecutionGrantPolicy.admit(
            grant,
            ExecutionProposal(ExecutionKind.Build, "./gradlew assembleDebug", timeoutSeconds = 600),
            missionId = "LF-CODE",
            nowEpochMillis = now + 1,
        )

        assertTrue(admission is ExecutionGrantAdmission.Granted)
        admission as ExecutionGrantAdmission.Granted
        assertEquals(1, admission.grant.consumedExecutions)
        assertEquals(600, admission.grant.reservedWallTimeSeconds)
    }

    @Test
    fun chainingNetworkAndUnknownCommandsRemainOutsideGrant() {
        val proposals = listOf(
            ExecutionProposal(ExecutionKind.Build, "./gradlew test && curl https://example.com"),
            ExecutionProposal(ExecutionKind.Test, "python -m pytest | tee results.txt"),
            ExecutionProposal(ExecutionKind.Test, "python -m pytest", networkRequired = true),
            ExecutionProposal(ExecutionKind.Test, "node hidden-script.js"),
        )

        proposals.forEach { proposal ->
            val admission = ExecutionGrantPolicy.admit(grant, proposal, "LF-CODE", now + 1)
            assertTrue(admission is ExecutionGrantAdmission.Rejected)
        }
    }

    @Test
    fun grantRoundTripPreservesBudgetsAndAuthority() {
        val restored = ExecutionGrantCodec.decode(ExecutionGrantCodec.encode(grant))

        assertEquals(grant, restored)
    }

    @Test
    fun expiryAndOutputBudgetsExhaustAuthority() {
        val outputExhausted = ExecutionGrantPolicy.recordOutput(grant, grant.maxOutputBytes, now + 1)

        assertEquals(ExecutionGrantStatus.Exhausted, outputExhausted.status)
        val expired = ExecutionGrantPolicy.admit(
            grant,
            ExecutionProposal(ExecutionKind.Test, "python -m pytest"),
            missionId = grant.missionId,
            nowEpochMillis = grant.expiresAtEpochMillis,
        )
        assertTrue(expired is ExecutionGrantAdmission.Rejected)
    }

    @Test
    fun defaultGrantIsOfflineBoundedAndLimitedToSafeKinds() {
        val generated = defaultMissionExecutionGrant("LF-CODE", "project", now)

        assertEquals(false, generated.networkAllowed)
        assertEquals(false, generated.dependencyInstallAllowed)
        assertEquals(24, generated.maxExecutions)
        assertEquals(ExecutionGrantStatus.Active, generated.effectiveStatus(now + 1))
        assertTrue(generated.allowedKinds.none { it == ExecutionKind.Run || it == ExecutionKind.InstallDependencies })
        assertTrue(generated.allowedCommandPrefixes.any { "--offline" in it })
    }
}
