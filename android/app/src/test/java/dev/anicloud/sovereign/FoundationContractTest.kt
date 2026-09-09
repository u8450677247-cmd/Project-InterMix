package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationContractTest {
    @Test
    fun automaticLayoutUsesTheDesktopThreshold() {
        assertEquals(1200, DesktopThresholdDp)
        assertEquals(
            FoundationLayout.Phone,
            resolveFoundationLayout(DesktopThresholdDp - 1, LayoutPreference.Automatic),
        )
        assertEquals(
            FoundationLayout.Desktop,
            resolveFoundationLayout(DesktopThresholdDp, LayoutPreference.Automatic),
        )
    }

    @Test
    fun workspaceRemainsAFirstClassDestination() {
        assertTrue(Destination.entries.contains(Destination.Workspace))
        assertEquals("Workspace", Destination.Workspace.label)
    }

    @Test
    fun explicitLayoutChoiceWinsAtAnyWidth() {
        assertEquals(
            FoundationLayout.Desktop,
            resolveFoundationLayout(360, LayoutPreference.Desktop),
        )
        assertEquals(
            FoundationLayout.Phone,
            resolveFoundationLayout(1600, LayoutPreference.Phone),
        )
    }

    @Test
    fun oneResponseAnswerOverrideIsConsumed() {
        val selected = AnswerModeSelection(
            defaultMode = AnswerMode.Adaptive,
            oneResponseOverride = AnswerMode.Quality,
        )
        assertEquals(AnswerMode.Quality, selected.modeForNextResponse())
        assertEquals(AnswerMode.Adaptive, selected.afterResponse().modeForNextResponse())
    }

    @Test
    fun authenticationGraceExpiresOnlyAfterThirtySeconds() {
        val grace = AuthenticationGrace()
        assertTrue(grace.needsAuthentication(0))
        grace.markAuthenticated()
        assertFalse(grace.needsAuthentication(10))
        grace.markBackgrounded(1_000)
        assertFalse(grace.needsAuthentication(31_000))
        assertTrue(grace.needsAuthentication(31_001))
    }

    @Test
    fun repeatedTailIsQuarantinedButOrdinaryTextPasses() {
        val repeated = "the the the the"
        assertEquals(
            "repetition-loop",
            GenerationIntegrityGuard.inspectStreamingText(repeated)?.code,
        )
        assertEquals(
            null,
            GenerationIntegrityGuard.inspectStreamingText(
                "The local model answered once, preserved its values, and stopped normally.",
            ),
        )
    }

    @Test
    fun recursiveMissionActionPatternsAreDetectedWithoutRejectingProgress() {
        assertTrue(hasRecursiveActionTail(listOf("read:a", "read:a", "read:a")))
        assertTrue(
            hasRecursiveActionTail(
                listOf("read:a", "write:b", "read:a", "write:b", "read:a", "write:b"),
            ),
        )
        assertFalse(
            hasRecursiveActionTail(
                listOf("read:a", "write:b", "read:c", "write:d", "read:e", "write:f"),
            ),
        )
    }

    @Test
    fun exactNumericAnchorsMustSurviveWhenExplicitlyRequested() {
        val prompt = "Preserve every value exactly: 6800 tokens, 6.99 GiB, 0.52x, 814.8 MiB."
        val valid = "Context 6800 tokens; memory 6.99 GiB; voice 0.52x; peak 814.8 MiB."
        val drifted = "Context 6800 tokens; memory about 7 GiB; voice 0.52x."

        assertTrue(GenerationIntegrityGuard.missingExactNumericAnchors(prompt, valid).isEmpty())
        assertEquals(
            listOf("6.99 gib", "814.8 mib"),
            GenerationIntegrityGuard.missingExactNumericAnchors(prompt, drifted),
        )
    }

    @Test
    fun ambientMotionYieldsToInferenceAndThermalPressure() {
        assertTrue(allowsAmbientMotion(status = 0, stage = ModelStage.Ready))
        assertFalse(allowsAmbientMotion(status = 2, stage = ModelStage.Ready))
        assertFalse(allowsAmbientMotion(status = 0, stage = ModelStage.Generating))
        assertTrue(isSevereThermalStatus(3))
        assertFalse(isSevereThermalStatus(2))
    }
}
