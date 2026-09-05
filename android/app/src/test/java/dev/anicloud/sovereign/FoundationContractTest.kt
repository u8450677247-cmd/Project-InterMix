package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationContractTest {
    @Test
    fun automaticLayoutUsesTheDesktopThreshold() {
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
}
