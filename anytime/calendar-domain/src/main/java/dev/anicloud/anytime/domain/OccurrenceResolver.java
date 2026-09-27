package dev.anicloud.anytime.domain;

import java.time.*;

/** Finds event instants without assuming that event and sacred origin zones share a civil date. */
public final class OccurrenceResolver {
    private OccurrenceResolver() {}

    public static Instant nextAfter(Instant initial, ZoneId eventZone, Recurrence.Rule rule,
                                    AnytimeCalendar calendar, Instant after) {
        if (initial.isAfter(after)) return initial;
        if (rule == Recurrence.Rule.NONE) return null;
        LocalTime clock = initial.atZone(eventZone).toLocalTime();
        ZoneId originZone = calendar.rules().originZone();
        boolean custom = rule == Recurrence.Rule.ANYTIME_MONTHLY || rule == Recurrence.Rule.ANYTIME_YEARLY;
        LocalDate anchor = initial.atZone(custom ? originZone : eventZone).toLocalDate();
        LocalDate cursor = after.atZone(custom ? originZone : eventZone).toLocalDate().minusDays(1);
        for (int i = 0; i < 4; i++) {
            LocalDate date = Recurrence.nextAfter(anchor, cursor, rule, calendar);
            if (date == null) return null;
            Instant occurrence = custom ? originDateAtLocalClock(date, clock, eventZone, originZone)
                : date.atTime(clock).atZone(eventZone).toInstant();
            if (occurrence.isAfter(after) && !occurrence.isBefore(initial)) return occurrence;
            cursor = date;
        }
        throw new IllegalStateException("recurrence did not advance");
    }

    private static Instant originDateAtLocalClock(LocalDate originDate, LocalTime clock,
                                                 ZoneId eventZone, ZoneId originZone) {
        for (int delta = -2; delta <= 2; delta++) {
            Instant candidate = originDate.plusDays(delta).atTime(clock).atZone(eventZone).toInstant();
            if (candidate.atZone(originZone).toLocalDate().equals(originDate)) return candidate;
        }
        throw new IllegalStateException("no event instant corresponds to origin date");
    }
}
