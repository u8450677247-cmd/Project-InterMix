package dev.anicloud.anytime.domain;

import java.time.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executable with plain javac/java as well as through JUnit on CI. */
public final class DomainChecks {
    private DomainChecks() {}
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void run() {
        ZoneId origin = ZoneId.of("America/Santo_Domingo");
        AnytimeCalendar c = new AnytimeCalendar(AnytimeCalendar.Rules.spring(origin, MonthDay.of(11, 7)));
        for (int y = 1901; y <= 2099; y++) {
            List<AnytimeCalendar.Day> list = c.year(y);
            check(list.size() == 365 || list.size() == 366, "civil span " + y);
            check(list.stream().filter(AnytimeCalendar.Day::counted).count() == 364, "counted " + y);
            check(list.stream().filter(d -> d.kind() == AnytimeCalendar.Kind.THRESHOLD_DAY).count() == 1,
                "anniversary " + y);
            Set<LocalDate> seen = new HashSet<>();
            int index = 0;
            for (AnytimeCalendar.Day date : list) {
                check(seen.add(date.civilDate()), "duplicate civil date");
                if (date.counted()) {
                    check(date.chamber() == index / 28 + 1 && date.day() == index % 28 + 1,
                        "13×28 position " + y);
                    check(date.weekday() == index % 7 + 1, "counted weekday " + y);
                    index++;
                } else check(date.weekday() == 0 && date.chamber() == 0, "outside weekday " + y);
            }
            check(c.toCivil(y, 1, 1).equals(list.get(0).civilDate()), "start " + y);
            check(c.toCivil(y, 13, 28).equals(list.stream().filter(AnytimeCalendar.Day::counted)
                .reduce((a, b) -> b).orElseThrow().civilDate()), "end " + y);
            if (y >= 2000 && y <= 2030) {
                for (AnytimeCalendar.Day date : list) {
                    check(c.fromCivil(date.civilDate()).equals(date), "civil round trip " + date.civilDate());
                    if (date.counted()) check(c.toCivil(y, date.chamber(), date.day()).equals(date.civilDate()),
                        "custom round trip " + date.civilDate());
                }
            }
        }
        check(c.start(2026).equals(LocalDate.of(2026, 3, 20)), "USNO spring 2026");
        check(c.start(2027).equals(LocalDate.of(2027, 3, 20)), "USNO spring 2027");
        check(c.fromInstant(Instant.parse("2026-03-20T02:00:00Z")).year() == 2025,
            "origin zone conversion");
        check(c.fromInstant(Instant.parse("2026-03-20T06:00:00Z")).year() == 2026,
            "origin midnight conversion");
        AnytimeCalendar fixed = new AnytimeCalendar(new AnytimeCalendar.Rules(
            ZoneId.of("Europe/Vienna"), AnytimeCalendar.Boundary.FIXED_DATE,
            MonthDay.of(4, 1), MonthDay.of(2, 29), AnytimeCalendar.LeapBirthday.MARCH_1));
        check(fixed.fromCivil(LocalDate.of(2025, 3, 1)).kind() == AnytimeCalendar.Kind.THRESHOLD_DAY,
            "non-leap birthday");
        check(fixed.fromCivil(LocalDate.of(2024, 2, 29)).kind() == AnytimeCalendar.Kind.THRESHOLD_DAY,
            "leap birthday");
        check(fixed.fromInstant(Instant.parse("2026-03-29T00:30:00Z")).civilDate()
            .equals(LocalDate.of(2026, 3, 29)), "DST forward");
        check(fixed.fromInstant(Instant.parse("2026-10-25T01:30:00Z")).civilDate()
            .equals(LocalDate.of(2026, 10, 25)), "DST backward");
        check(new PersonalCycle("practice", "Practice", LocalDate.of(2026, 1, 1), 7)
            .position(LocalDate.of(2025, 12, 31)) == 7, "negative cycle offset");
        LocalDate annual = c.toCivil(2025, 13, 27);
        check(Recurrence.nextAfter(annual, annual, Recurrence.Rule.ANYTIME_YEARLY, c)
            .equals(c.toCivil(2026, 13, 27)), "custom annual recurrence");
        check(Recurrence.nextAfter(c.toCivil(2026, 1, 28), c.toCivil(2026, 1, 28),
            Recurrence.Rule.ANYTIME_MONTHLY, c).equals(c.toCivil(2026, 2, 28)),
            "custom monthly recurrence");
        LocalDate threshold = c.year(2026).stream()
            .filter(d -> d.kind() == AnytimeCalendar.Kind.THRESHOLD_DAY).findFirst().orElseThrow().civilDate();
        AnytimeCalendar.Day lastBefore = c.year(2026).stream()
            .filter(d -> d.counted() && d.civilDate().isBefore(threshold))
            .reduce((a, b) -> b).orElseThrow();
        int nextChamber = lastBefore.chamber() + 1;
        if (nextChamber <= 13) check(Recurrence.nextAfter(c.toCivil(2026, 1, 1), threshold,
            Recurrence.Rule.ANYTIME_MONTHLY, c).equals(c.toCivil(2026, nextChamber, 1)),
            "monthly recurrence across outside day");
        ZoneId presentZone = ZoneId.of("Europe/Vienna");
        Instant crossZoneAnchor = LocalDate.of(2026, 8, 9).atTime(1, 30).atZone(presentZone).toInstant();
        Instant crossZoneNext = OccurrenceResolver.nextAfter(crossZoneAnchor, presentZone,
            Recurrence.Rule.ANYTIME_MONTHLY, c, crossZoneAnchor);
        AnytimeCalendar.Day anchorDay = c.fromInstant(crossZoneAnchor);
        AnytimeCalendar.Day nextDay = c.fromInstant(crossZoneNext);
        check(nextDay.day() == anchorDay.day() && nextDay.chamber() == anchorDay.chamber() + 1,
            "custom recurrence honors origin date across zones");
        check(SolarCalculator.daylightHours(0, Instant.parse("2026-03-20T14:46:00Z")) > 12,
            "equinox daylight estimate");
        check(Numerology.civilDateDigitReduction(LocalDate.of(2026, 9, 27)).value() == 1,
            "documented digit sum");
        moonReference(Instant.parse("2026-09-11T03:27:00Z"), MoonCalculator.Phase.NEW);
        moonReference(Instant.parse("2026-09-18T20:44:00Z"), MoonCalculator.Phase.FIRST_QUARTER);
        moonReference(Instant.parse("2026-09-26T16:49:00Z"), MoonCalculator.Phase.FULL);
        moonReference(Instant.parse("2026-10-03T13:25:00Z"), MoonCalculator.Phase.THIRD_QUARTER);
    }
    private static void moonReference(Instant official, MoonCalculator.Phase phase) {
        MoonCalculator.Event calculated = MoonCalculator.adjacent(official.minusSeconds(24 * 3600), true, phase);
        long error = Math.abs(Duration.between(calculated.instant(), official).toMinutes());
        check(error <= 4 * 60, "USNO " + phase + " off by " + error + " minutes: " + calculated.instant());
    }
    public static void main(String[] args) {
        run();
        System.out.println("Anytime domain checks passed");
    }
}
