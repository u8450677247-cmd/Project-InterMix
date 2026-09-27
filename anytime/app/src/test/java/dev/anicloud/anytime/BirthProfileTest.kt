package dev.anicloud.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class BirthProfileTest {
    @Test fun completeUnambiguousProfileResolvesInstant() {
        val profile = BirthProfile(LocalDate.of(2004, 6, 13), LocalTime.of(16, 25),
            ZoneId.of("UTC"), "Example place")
        assertEquals(Instant.parse("2004-06-13T16:25:00Z"), profile.instant())
    }

    @Test fun dstGapAndFoldDoNotCreateFalseBirthInstant() {
        val zone = ZoneId.of("America/New_York")
        assertNull(BirthProfile(LocalDate.of(2024, 3, 10), LocalTime.of(2, 30), zone, "").instant())
        assertNull(BirthProfile(LocalDate.of(2024, 11, 3), LocalTime.of(1, 30), zone, "").instant())
        assertNull(BirthProfile(null, LocalTime.of(12, 0), zone, "").instant())
    }
}
