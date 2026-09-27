package dev.anicloud.anytime.domain;

import java.time.Duration;
import java.time.Instant;

/** Geocentric lunar phases using a truncated Meeus longitude series.
 * Phase-event estimates have a declared tolerance of four hours against USNO
 * modern-era reference tables; rise/set and local horizon are out of scope.
 */
public final class MoonCalculator {
    private MoonCalculator() {}

    public enum Phase { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS,
        FULL, WANING_GIBBOUS, THIRD_QUARTER, WANING_CRESCENT }
    public record Event(Phase phase, Instant instant) {}
    public record State(Phase phase, double illumination, boolean waxing,
                        double longitudeDifference, double ageDays,
                        Event previous, Event next) {}

    public static double phaseAngle(Instant instant) {
        double jd = 2440587.5 + (instant.getEpochSecond() + instant.getNano() / 1e9 + 70) / 86400;
        double t = (jd - 2451545.0) / 36525;
        double l = SolarCalculator.norm(218.3164477 + 481267.88123421 * t
            - 0.0015786 * t * t + t * t * t / 538841);
        double m = Math.toRadians(SolarCalculator.norm(357.5291092 + 35999.0502909 * t));
        double mp = Math.toRadians(SolarCalculator.norm(134.9633964 + 477198.8675055 * t));
        double d = Math.toRadians(SolarCalculator.norm(297.8501921 + 445267.1114034 * t));
        double f = Math.toRadians(SolarCalculator.norm(93.2720950 + 483202.0175233 * t));
        double moonLongitude = l + 6.289 * Math.sin(mp) + 1.274 * Math.sin(2 * d - mp)
            + 0.658 * Math.sin(2 * d) + 0.214 * Math.sin(2 * mp)
            - 0.186 * Math.sin(m) - 0.114 * Math.sin(2 * f)
            + 0.059 * Math.sin(2 * d - 2 * mp) + 0.057 * Math.sin(2 * d - m - mp)
            + 0.053 * Math.sin(2 * d + mp) + 0.046 * Math.sin(2 * d - m)
            + 0.041 * Math.sin(m - mp) - 0.035 * Math.sin(d)
            - 0.031 * Math.sin(m + mp) - 0.015 * Math.sin(2 * f - 2 * d);
        return SolarCalculator.norm(moonLongitude - SolarCalculator.longitude(instant));
    }

    public static State at(Instant instant) {
        double angle = phaseAngle(instant);
        int sector = ((int) Math.floor((angle + 22.5) / 45)) % 8;
        double illumination = (1 - Math.cos(Math.toRadians(angle))) / 2;
        Event previous = adjacent(instant, false, null);
        Event next = adjacent(instant, true, null);
        Instant lastNew = precedingTarget(instant, 0);
        return new State(Phase.values()[sector], illumination, angle < 180,
            angle, Duration.between(lastNew, instant).toSeconds() / 86400.0,
            previous, next);
    }

    public static Event adjacent(Instant instant, boolean forward, Phase target) {
        double angle = phaseAngle(instant);
        double chosen = target == null
            ? (forward ? (Math.floor(angle / 90) + 1) * 90 : Math.floor(angle / 90) * 90)
            : switch (target) {
                case NEW -> 0;
                case FIRST_QUARTER -> 90;
                case FULL -> 180;
                case THIRD_QUARTER -> 270;
                default -> throw new IllegalArgumentException("major phases only");
            };
        double destination = SolarCalculator.norm(chosen);
        double travel = forward ? SolarCalculator.norm(destination - angle)
            : SolarCalculator.norm(angle - destination);
        if (travel < 0.000001) travel = 360;
        Instant result = crossing(instant, forward, travel, angle);
        Phase phase = switch (((int) Math.round(destination / 90)) % 4) {
            case 0 -> Phase.NEW;
            case 1 -> Phase.FIRST_QUARTER;
            case 2 -> Phase.FULL;
            default -> Phase.THIRD_QUARTER;
        };
        return new Event(phase, result);
    }

    private static Instant precedingTarget(Instant instant, double target) {
        double angle = phaseAngle(instant);
        double travel = SolarCalculator.norm(angle - target);
        return travel < 0.000001 ? instant : crossing(instant, false, travel, angle);
    }

    private static Instant crossing(Instant start, boolean forward, double travel, double angle) {
        long direction = forward ? 1 : -1;
        long low = 0, high = 0;
        double accumulated = 0;
        double priorAngle = angle;
        // Accumulate each six-hour angular step so a full revolution cannot
        // wrap to zero and masquerade as the beginning of the search.
        for (int step = 1; step <= 140; step++) {
            high = step * 6L * 3600;
            double current = phaseAngle(start.plusSeconds(direction * high));
            double increment = forward ? SolarCalculator.norm(current - priorAngle)
                : SolarCalculator.norm(priorAngle - current);
            if (accumulated + increment >= travel - 0.000001) break;
            accumulated += increment;
            priorAngle = current;
            low = high;
            if (step == 140) throw new IllegalStateException("lunar phase not found");
        }
        // Work on magnitude in seconds to avoid direction-specific binary search.
        final double travelledBeforeBracket = accumulated;
        final double angleBeforeBracket = priorAngle;
        while (high - low > 30) {
            long middle = (low + high) / 2;
            double current = phaseAngle(start.plusSeconds(direction * middle));
            double reached = travelledBeforeBracket + (forward
                ? SolarCalculator.norm(current - angleBeforeBracket)
                : SolarCalculator.norm(angleBeforeBracket - current));
            if (reached >= travel - 0.000001) high = middle; else low = middle;
        }
        return start.plusSeconds(direction * high);
    }
}
