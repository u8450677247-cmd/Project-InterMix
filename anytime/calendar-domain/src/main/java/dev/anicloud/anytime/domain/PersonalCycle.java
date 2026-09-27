package dev.anicloud.anytime.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** An explicitly user-defined cycle, independent of astronomy and the calendar year. */
public record PersonalCycle(String id, String label, LocalDate origin, int periodDays) {
    public PersonalCycle {
        if (id == null || id.isBlank() || label == null || label.isBlank() || origin == null
            || periodDays < 2 || periodDays > 36525) throw new IllegalArgumentException("invalid cycle");
    }
    public int position(LocalDate date) {
        return (int) Math.floorMod(ChronoUnit.DAYS.between(origin, date), periodDays) + 1;
    }
}
