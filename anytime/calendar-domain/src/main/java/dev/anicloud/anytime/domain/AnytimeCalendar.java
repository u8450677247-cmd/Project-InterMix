package dev.anicloud.anytime.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pure calendar conversion. The civil date is interpreted in the origin zone. */
public final class AnytimeCalendar {
    public enum Boundary { NORTHERN_SPRING_EQUINOX, FIXED_DATE }
    public enum LeapBirthday { FEBRUARY_28, MARCH_1 }
    public enum Kind { COUNTED_DAY, THRESHOLD_DAY, DRIFT_DAY }

    public record Rules(ZoneId originZone, Boundary boundary, MonthDay fixedBoundary,
                        MonthDay outsideDay, LeapBirthday leapBirthday) {
        public Rules {
            if (originZone == null || boundary == null || leapBirthday == null)
                throw new IllegalArgumentException("origin zone, boundary and leap policy required");
            if (boundary == Boundary.FIXED_DATE && fixedBoundary == null)
                throw new IllegalArgumentException("fixed boundary date required");
            if (fixedBoundary != null && fixedBoundary.equals(MonthDay.of(2, 29)))
                throw new IllegalArgumentException("February 29 cannot be a fixed boundary");
        }
        public static Rules spring(ZoneId zone, MonthDay birthday) {
            return new Rules(zone, Boundary.NORTHERN_SPRING_EQUINOX, null,
                birthday, LeapBirthday.FEBRUARY_28);
        }
    }

    public record Day(int year, Kind kind, int chamber, int day, int weekday,
                      int outsideIndex, LocalDate civilDate) {
        public boolean counted() { return kind == Kind.COUNTED_DAY; }
    }

    private final Rules rules;
    private final ConcurrentHashMap<Integer, List<Day>> yearCache = new ConcurrentHashMap<>();
    public AnytimeCalendar(Rules rules) { this.rules = rules; }
    public Rules rules() { return rules; }

    public LocalDate start(int year) {
        return rules.boundary == Boundary.FIXED_DATE
            ? rules.fixedBoundary.atYear(year)
            : SolarCalculator.northernSpringDate(year, rules.originZone);
    }

    public int sacredYear(LocalDate civil) {
        int year = civil.getYear();
        return civil.isBefore(start(year)) ? year - 1 : year;
    }

    public Day fromInstant(Instant instant) {
        return fromCivil(instant.atZone(rules.originZone).toLocalDate());
    }

    public Day fromCivil(LocalDate date) {
        int year = sacredYear(date);
        List<Day> days = year(year);
        long offset = ChronoUnit.DAYS.between(start(year), date);
        return days.get(Math.toIntExact(offset));
    }

    public LocalDate toCivil(int sacredYear, int chamber, int day) {
        if (chamber < 1 || chamber > 13 || day < 1 || day > 28)
            throw new IllegalArgumentException("chamber must be 1–13 and day 1–28");
        int target = (chamber - 1) * 28 + day;
        for (Day candidate : year(sacredYear)) {
            if (candidate.counted() && (candidate.chamber - 1) * 28 + candidate.day == target)
                return candidate.civilDate;
        }
        throw new IllegalStateException("incomplete year");
    }

    public List<Day> year(int year) {
        return yearCache.computeIfAbsent(year, this::buildYear);
    }

    private List<Day> buildYear(int year) {
        LocalDate beginning = start(year);
        LocalDate end = start(year + 1);
        int span = Math.toIntExact(ChronoUnit.DAYS.between(beginning, end));
        if (span < 365 || span > 366)
            throw new IllegalArgumentException("year must span 365 or 366 civil days; got " + span);

        LocalDate threshold = null;
        if (rules.outsideDay != null) {
            for (int y = beginning.getYear(); y <= end.getYear(); y++) {
                LocalDate candidate = anniversary(y);
                if (!candidate.isBefore(beginning) && candidate.isBefore(end)) {
                    if (threshold != null) throw new IllegalArgumentException("two anniversaries in one year");
                    threshold = candidate;
                }
            }
            if (threshold == null) throw new IllegalArgumentException("anniversary outside year");
        }

        Set<LocalDate> drift = new HashSet<>();
        // Even without a configured birthday, the year has one outside day.
        // Assign it to the final available date until the user chooses a date.
        if (threshold == null) threshold = end.minusDays(1);
        for (LocalDate d = end.minusDays(1); drift.size() < span - 365; d = d.minusDays(1)) {
            if (!d.equals(threshold)) drift.add(d);
        }

        List<Day> result = new ArrayList<>(span);
        int counted = 0;
        int outside = 0;
        for (LocalDate date = beginning; date.isBefore(end); date = date.plusDays(1)) {
            if (date.equals(threshold) || drift.contains(date)) {
                result.add(new Day(year, date.equals(threshold) ? Kind.THRESHOLD_DAY : Kind.DRIFT_DAY,
                    0, 0, 0, ++outside, date));
            } else {
                result.add(new Day(year, Kind.COUNTED_DAY, counted / 28 + 1,
                    counted % 28 + 1, counted % 7 + 1, 0, date));
                counted++;
            }
        }
        if (counted != 364) throw new IllegalStateException("year must contain 364 counted days");
        return List.copyOf(result);
    }

    private LocalDate anniversary(int civilYear) {
        MonthDay date = rules.outsideDay;
        if (date.equals(MonthDay.of(2, 29)) && !Year.isLeap(civilYear)) {
            return rules.leapBirthday == LeapBirthday.FEBRUARY_28
                ? LocalDate.of(civilYear, 2, 28) : LocalDate.of(civilYear, 3, 1);
        }
        return date.atYear(civilYear);
    }

    public LocalDate nextOccurrence(LocalDate after, int chamber, int day) {
        int year = sacredYear(after);
        LocalDate candidate = toCivil(year, chamber, day);
        return candidate.isAfter(after) ? candidate : toCivil(year + 1, chamber, day);
    }
}
