package dev.anicloud.anytime.domain;

import java.time.*;

/** Occurrence dates are derived afresh so custom recurrence survives a rules change. */
public final class Recurrence {
    private Recurrence() {}
    public enum Rule { NONE, GREGORIAN_YEARLY, ANYTIME_YEARLY, ANYTIME_MONTHLY }

    public static LocalDate nextAfter(LocalDate initial, LocalDate after, Rule rule,
                                      AnytimeCalendar calendar) {
        if (after.isBefore(initial)) return initial;
        return switch (rule) {
            case NONE -> null;
            case GREGORIAN_YEARLY -> {
                int year = after.getYear();
                LocalDate candidate = initial.withYear(year);
                if (!candidate.isAfter(after)) candidate = initial.withYear(year + 1);
                yield candidate;
            }
            case ANYTIME_YEARLY -> {
                AnytimeCalendar.Day anchor = calendar.fromCivil(initial);
                if (!anchor.counted()) throw new IllegalArgumentException("outside-time annual event requires a civil recurrence");
                LocalDate candidate = calendar.nextOccurrence(after, anchor.chamber(), anchor.day());
                yield candidate.isBefore(initial) ? initial : candidate;
            }
            case ANYTIME_MONTHLY -> {
                AnytimeCalendar.Day anchor = calendar.fromCivil(initial);
                if (!anchor.counted()) throw new IllegalArgumentException("outside-time monthly event requires a civil recurrence");
                AnytimeCalendar.Day current = calendar.fromCivil(after);
                AnytimeCalendar.Day lastCounted = current.counted() ? current : calendar.year(current.year())
                    .stream().filter(d -> d.counted() && !d.civilDate().isAfter(after))
                    .reduce((a, b) -> b).orElse(null);
                int monthIndex = lastCounted == null ? current.year() * 13 - 1
                    : lastCounted.year() * 13 + lastCounted.chamber() - 1;
                for (int i = 0; i < 15; i++) {
                    int index = monthIndex + i;
                    LocalDate candidate = calendar.toCivil(Math.floorDiv(index, 13),
                        Math.floorMod(index, 13) + 1, anchor.day());
                    if (candidate.isAfter(after) && !candidate.isBefore(initial)) yield candidate;
                }
                throw new IllegalStateException("next month not found");
            }
        };
    }
}
