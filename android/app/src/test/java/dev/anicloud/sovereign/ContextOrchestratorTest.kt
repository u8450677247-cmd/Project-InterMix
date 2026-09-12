package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOrchestratorTest {
    @Test
    fun shipsExactlyTwentyInspectableContextPolicies() {
        assertEquals(20, ContextStrategy.entries.size)
        assertEquals(ContextStrategy.entries.size, ContextStrategyPackSize)
        assertTrue(ContextStrategy.entries.contains(ContextStrategy.FreshControllerConversation))
        assertTrue(ContextStrategy.entries.contains(ContextStrategy.ContextLedgerTelemetry))
    }

    @Test
    fun everyModeReservesOutputAndPhysicalHeadroom() {
        AnswerMode.entries.forEach { mode ->
            val pack = ContextOrchestrator.pack(
                rawPrompt = "[CURRENT USER REQUEST]\nExplain the verified state.",
                mode = mode,
                lane = ContextLane.Chat,
            )
            assertEquals(ContextOrchestrator.outputReserve(mode), pack.outputReserveTokens)
            assertTrue(
                pack.estimatedPrefillTokens + pack.outputReserveTokens <= ContextPhysicalTokens,
            )
            assertFalse(pack.compacted)
        }
    }

    @Test
    fun oversizedPacksKeepTheCurrentRequestAndNewestCheckpoint() {
        val prompt = buildString {
            append("[CONTROLLER CONTRACT]\n")
            append("contract ".repeat(10_000))
            append("\n[CURRENT USER REQUEST]\n")
            append("write the next durable scene\n")
            append("newest-state ".repeat(8_000))
            append("END_SENTINEL")
        }
        val pack = ContextOrchestrator.pack(prompt, AnswerMode.Quality, ContextLane.Story)

        assertTrue(pack.compacted)
        assertTrue(pack.prompt.length <= pack.maxPromptCharacters)
        assertTrue(pack.prompt.contains("[CURRENT USER REQUEST]"))
        assertTrue(pack.prompt.contains("write the next durable scene"))
        assertTrue(pack.prompt.endsWith("END_SENTINEL"))
        assertTrue(pack.estimatedHeadroomTokens >= 0)
    }

    @Test
    fun onlyCapacityShapedFailuresEnterTheSingleRecoveryLane() {
        assertTrue(
            ContextOrchestrator.isCapacityFailure(
                IllegalStateException("Prefill input length exceeds remaining capacity: 137"),
            ),
        )
        assertFalse(ContextOrchestrator.isCapacityFailure(IllegalStateException("model file missing")))
    }
}
