package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityCaptureTest {
    @Test
    fun extractsExactProjectGoalsDecisionsAndOpenLoops() {
        val source = "We need to make the native context feel as continuous as Termux. " +
            "We decided to keep about one thousand recent tokens. " +
            "The E2B route is still missing."
        val signals = ContinuityCapturePolicy.extract(source)

        assertEquals(3, signals.size)
        assertEquals("We need to make the native context feel as continuous as Termux.", signals[0].text)
        assertTrue(signals[0].currentTask)
        assertTrue(signals[1].decision)
        assertTrue(signals[2].openLoop)
    }

    @Test
    fun ignoresOrdinarySmallTalkAndControllerCommands() {
        assertTrue(ContinuityCapturePolicy.extract("Hello, how are you today?").isEmpty())
        assertTrue(ContinuityCapturePolicy.extract("/sessions list").isEmpty())
    }

    @Test
    fun captureIsBoundedAndNeverParaphrasesUserText() {
        val lines = (1..20).joinToString("\n") { "Project issue $it is still missing." }
        val signals = ContinuityCapturePolicy.extract(lines, limit = 4)

        assertEquals(4, signals.size)
        assertFalse(signals.any { it.text.contains("summary", ignoreCase = true) })
        assertEquals("Project issue 1 is still missing.", signals.first().text)
    }
}
