package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionProfilePolicyTest {
    private val profile = InteractionProfile()

    @Test
    fun selfContainedDefinitionDoesNotInheritTheOpenWorkspace() {
        val decision = InteractionProfilePolicy.selectContext("What is nested quoting?", profile)

        assertEquals(ContextScope.General, decision.scope)
        assertFalse(decision.includeWorkspace)
        assertEquals(12, decision.recentMessageLimit)
        assertTrue(decision.characterBudget >= 8_000)
        assertFalse(decision.allowMemoryFallback)
    }

    @Test
    fun explicitFileWorkIncludesTheBoundedWorkspace() {
        val decision = InteractionProfilePolicy.selectContext(
            "Please fix and test probe.py in our workspace",
            profile,
        )

        assertEquals(ContextScope.Project, decision.scope)
        assertTrue(decision.includeWorkspace)
        assertTrue(decision.relevanceScore >= decision.threshold)
    }

    @Test
    fun explicitWebsiteBuildIncludesProjectContext() {
        val decision = InteractionProfilePolicy.selectContext(
            "Create a functional website from the approved project brief",
            profile,
        )

        assertEquals(ContextScope.Project, decision.scope)
        assertTrue(decision.includeWorkspace)
    }

    @Test
    fun referentialFollowUpGetsConversationButNotIncidentalFiles() {
        val decision = InteractionProfilePolicy.selectContext("Why did you say that earlier?", profile)

        assertEquals(ContextScope.Continuity, decision.scope)
        assertFalse(decision.includeWorkspace)
        assertTrue(decision.recentMessageLimit > 0)
    }

    @Test
    fun explicitStyleCueMovesOnlyTheNamedTraitWithinBounds() {
        val adjustments = InteractionProfilePolicy.detectExplicitAdjustments(
            "Please keep it more concise, but preserve the substance.",
        )
        val updated = InteractionProfilePolicy.apply(profile, adjustments)

        assertEquals(1, adjustments.size)
        assertEquals(InteractionTrait.Detail, adjustments.single().trait)
        assertTrue(updated.detail < profile.detail)
        assertEquals(profile.warmth, updated.warmth, 0.0)
    }

    @Test
    fun profileValuesRemainBounded() {
        val updated = InteractionProfilePolicy.apply(
            profile.copy(emoji = 0.02),
            listOf(ProfileAdjustment(InteractionTrait.Emoji, -0.12, "no emojis")),
        )

        assertEquals(0.0, updated.emoji, 0.0)
    }

    @Test
    fun nonFiniteProfileValuesReturnToReviewedDefaults() {
        val updated = profile.copy(detail = Double.NaN).normalized()

        assertEquals(InteractionTrait.Detail.defaultValue, updated.detail, 0.0)
    }
}
