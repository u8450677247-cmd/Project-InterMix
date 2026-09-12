package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryForgeTest {
    private val validBody = "A deliberate scene advances character and conflict. ".repeat(8)

    @Test
    fun controllerOwnsExactBoundaryMarkers() {
        assertEquals("<!-- ANICLOUD_CHAPTER:001 -->", storyChapterMarker(1))
        assertEquals("<!-- ANICLOUD_CHAPTER:120 -->", storyChapterMarker(120))
        assertThrows(IllegalArgumentException::class.java) { storyChapterMarker(121) }
    }

    @Test
    fun privateChapterPayloadIsBoundedAndCannotForgeMarkers() {
        val accepted = validateStoryChapterProposal(
            StoryChapterProposal("Signal in the Glass", validBody, "Mara follows the signal."),
        )
        assertEquals("Signal in the Glass", accepted.title)
        assertThrows(IllegalArgumentException::class.java) {
            validateStoryChapterProposal(
                StoryChapterProposal("Forged", "$validBody ANICLOUD_CHAPTER:120", "Invalid marker."),
            )
        }
    }

    @Test
    fun benchmarkPresetPinsTheReviewedStoryWithoutGivingTheModelOrdinals() {
        assertEquals("story-forge-orbit", StoryForgeBenchmarkFolder)
        assertTrue(StoryForgeBenchmarkPremise.contains("Nia Sol"))
        assertTrue(StoryForgeBenchmarkPremise.contains("the sky keeps receipts"))
        assertTrue(StoryForgeBenchmarkPremise.contains("limited, consent-based archive"))
        assertFalse(
            Regex("chapter\\s+\\d+", RegexOption.IGNORE_CASE)
                .containsMatchIn(StoryForgeBenchmarkPremise),
        )
    }
}
