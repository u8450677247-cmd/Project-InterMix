package dev.anicloud.anytime.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Approximate apparent geocentric solar longitude (NOAA/Meeus low-order series). */
public final class SolarCalculator {
    private SolarCalculator() {}

    public static double longitude(Instant time) {
        // Dynamical time correction changes by era; 70 seconds is a documented
        // modern-era approximation, sufficient for a calendar-day boundary.
        double jd = 2440587.5 + (time.getEpochSecond() + time.getNano() / 1e9 + 70) / 86400.0;
        double t = (jd - 2451545.0) / 36525.0;
        double mean = norm(280.46646 + t * (36000.76983 + 0.0003032 * t));
        double anomaly = norm(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double m = Math.toRadians(anomaly);
        double equation = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t))
            + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
            + Math.sin(3 * m) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        return norm(mean + equation - 0.00569 - 0.00478 * Math.sin(omega));
    }

    public static LocalDate northernSpringDate(int civilYear, ZoneId zone) {
        if (civilYear < 1800 || civilYear > 2200) {
            throw new IllegalArgumentException("equinox boundary supports years 1800–2200");
        }
        Instant lo = LocalDate.of(civilYear, 3, 18).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant hi = lo.plus(6, ChronoUnit.DAYS);
        // On this bracket, longitudes before the crossing are near 360 degrees.
        for (int i = 0; i < 22; i++) {
            Instant mid = lo.plusMillis((hi.toEpochMilli() - lo.toEpochMilli()) / 2);
            double signed = longitude(mid);
            if (signed > 180) signed -= 360;
            if (signed < 0) lo = mid; else hi = mid;
        }
        return hi.atZone(zone).toLocalDate();
    }

    /** Geometric daylight estimate in hours, including standard horizon refraction. */
    public static double daylightHours(double latitude, Instant instant) {
        if (latitude < -90 || latitude > 90 || !Double.isFinite(latitude))
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        double declination = Math.asin(Math.sin(Math.toRadians(23.439))
            * Math.sin(Math.toRadians(longitude(instant))));
        double lat = Math.toRadians(latitude);
        double cosine = (Math.sin(Math.toRadians(-0.833)) - Math.sin(lat) * Math.sin(declination))
            / (Math.cos(lat) * Math.cos(declination));
        if (cosine >= 1) return 0;
        if (cosine <= -1) return 24;
        return 24 * Math.acos(cosine) / Math.PI;
    }

    static double norm(double angle) {
        double value = angle % 360.0;
        return value < 0 ? value + 360.0 : value;
    }
}
