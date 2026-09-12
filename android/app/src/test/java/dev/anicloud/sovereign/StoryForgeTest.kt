package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
