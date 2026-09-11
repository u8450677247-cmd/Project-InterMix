package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRuntimePolicyTest {
    private val g5Facts = DeviceRuntimeFacts(
        socModel = "Tensor G5",
        hardware = "blazer",
        dispatcherAvailable = true,
        availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
    )
    private val e2b = ImportedModel(
        displayName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        absolutePath = "/private/e2b.litertlm",
        byteSize = 3_110_000_000L,
        sha256 = AdaptiveRuntimePolicy.TensorG5E2BSha256,
        role = ModelRole.Conversation,
    )
    private val e4b = ImportedModel(
        displayName = "gemma-4-E4B-it.litertlm",
        absolutePath = "/private/e4b.litertlm",
        byteSize = 4_000_000_000L,
        sha256 = "e4b",
        role = ModelRole.Reasoning,
    )

    @Test
    fun exactG5PackageIsNpuEligible() {
        val eligibility = AdaptiveRuntimePolicy.npuEligibility(e2b, g5Facts)
        assertTrue(eligibility.eligible)
        assertTrue(eligibility.detail.contains("verified"))
    }

    @Test
    fun residentE4bMemoryDoesNotBlockConversationRouteSelection() {
        val factsWhileE4bIsResident = g5Facts.copy(
            availableMemoryBytes = 1_400L * 1024L * 1024L,
        )

        assertFalse(AdaptiveRuntimePolicy.npuEligibility(e2b, factsWhileE4bIsResident).eligible)
        assertTrue(
            AdaptiveRuntimePolicy.npuPackageEligibility(e2b, factsWhileE4bIsResident).eligible,
        )
        val route = AdaptiveRuntimePolicy.select(
            AnswerMode.Performance,
            "hello",
            e2b,
            e4b,
            factsWhileE4bIsResident,
        )

        assertEquals(ModelRole.Conversation, route?.model?.role)
        assertEquals(RuntimeBackendPreference.NpuOnly, route?.backendPreference)
    }

    @Test
    fun wrongFingerprintCannotConsumeGpuAsE2bFallback() {
        val unknown = e2b.copy(sha256 = "unknown")
        assertFalse(AdaptiveRuntimePolicy.npuEligibility(unknown, g5Facts).eligible)
        val route = AdaptiveRuntimePolicy.select(
            AnswerMode.Performance,
            "hello",
            unknown,
            e4b,
            g5Facts,
        )
        assertEquals(ModelRole.Reasoning, route?.model?.role)
        assertEquals(RuntimeBackendPreference.GpuThenCpu, route?.backendPreference)
    }

    @Test
    fun ordinaryChatUsesE2bWhileCodingUsesE4b() {
        val chat = AdaptiveRuntimePolicy.select(
            AnswerMode.Adaptive,
            "How are you today?",
            e2b,
            e4b,
            g5Facts,
        )
        val code = AdaptiveRuntimePolicy.select(
            AnswerMode.Adaptive,
            "Debug and refactor this Kotlin code",
            e2b,
            e4b,
            g5Facts,
        )
        assertEquals(ModelRole.Conversation, chat?.model?.role)
        assertEquals(RuntimeBackendPreference.NpuOnly, chat?.backendPreference)
        assertEquals(ModelRole.Reasoning, code?.model?.role)
    }

    @Test
    fun noVerifiedModelMeansNoRoute() {
        assertNull(
            AdaptiveRuntimePolicy.select(
                AnswerMode.Adaptive,
                "hello",
                e2b.copy(sha256 = "unknown"),
                null,
                g5Facts,
            ),
        )
    }
}
