package dev.anicloud.anytime

import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anicloud.anytime.domain.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

/** Runs on an Android device/emulator; CI assembles these tests but has no emulator. */
@RunWith(AndroidJUnit4::class)
class AnytimeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun primarySelectionNavigatesToYear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("anytime-settings", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", false).putString("primary", "Anytime").commit()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("How would you like to experience it?").assertExists()
        compose.onNodeWithText("Gregorian").performClick()
        compose.onNodeWithText("Begin →").performClick()
        compose.onNodeWithText("WHAT IS TODAY FOR ME?").assertExists()
        compose.onNodeWithText("Civil bridge", substring = true).assertExists()
        compose.onNodeWithText("Year").performClick()
        compose.onNodeWithText("13 CHAMBERS", substring = true).assertExists()
    }

    @Test fun journalSurvivesActivityRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("anytime-settings", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", true).commit()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Journal").performScrollTo().performClick()
        compose.onNodeWithText("+  Write a reflection").performClick()
        val note = "Device test reflection ${System.nanoTime()}"
        compose.onNodeWithText("Reflection").performTextInput(note)
        compose.onNodeWithText("Keep this moment").performClick()
        compose.onNodeWithText(note).assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Journal").performScrollTo().performClick()
        compose.onNodeWithText(note).assertExists()
    }

    @Test fun exportedRecordsImportOnceAndPersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AnytimeStore(context)
        try {
            store.set("originZone", "UTC")
            store.setBirthMonthDay(null)
            val date = LocalDate.of(2026, 10, 8)
            val marker = "Device archive test ${System.nanoTime()}"
            store.addEvent(marker, "private detail", date.atTime(18, 0).atZone(ZoneOffset.UTC),
                false, Recurrence.Rule.NONE)
            store.addEntry(marker)
            store.addCycle(marker, date, 28)
            val archive = store.exportJson()
            val count = store.previewImport(archive)
            assertTrue(count.events > 0 && count.reflections > 0 && count.cycles > 0)
            val repeated = store.importJson(archive)
            assertEquals(0, repeated.events)
            assertEquals(0, repeated.reflections)
            assertEquals(0, repeated.cycles)
            val event = store.events().first { it.title == marker }
            val entry = store.entries().first { it.text == marker }
            val cycle = store.cycles().first { it.title == marker }
            store.removeEvent(event.id); store.removeEntry(entry.id); store.removeCycle(cycle.id)
            val restored = store.importJson(archive)
            assertTrue(restored.events > 0 && restored.reflections > 0 && restored.cycles > 0)
            store.close()
            AnytimeStore(context).use { reopened ->
                assertTrue(reopened.events().any { it.title == marker })
                assertTrue(reopened.entries().any { it.text == marker })
                assertTrue(reopened.cycles().any { it.title == marker })
            }
        } finally { store.close() }
    }
}
