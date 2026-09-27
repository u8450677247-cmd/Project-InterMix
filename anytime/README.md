# Anytime

Independent native Android personal-time application. Source of truth is an
instant and an explicitly configured calendar policy. Gregorian dates provide
interoperability; 13×28 dates, astronomy, and symbolic interpretations remain
separate representations. The app has no Internet permission or AniCloud runtime.

## Build

Use JDK 17, Gradle 9.4.1, and Android API 36:

```sh
gradle -p anytime :calendar-domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

The GitHub Actions `anytime-android` workflow retains the debug APK under
`Anytime-standalone-debug`. It is signed by CI's temporary debug certificate.
If the repository's private dogfood key is configured, a separate
`Anytime-standalone-dogfood` artifact uses a persistent signature for updates.
Install one signing line and keep using it; a debug build cannot replace a
dogfood-signed build.

## Calendar policy

The starting boundary is the civil date of the Northern spring equinox in a
configurable origin zone. A user can select a fixed month/day instead. Each
year has 364 counted days, one Day Outside Time, and zero or one Drift Day to
cover the observed equinox-to-equinox span. The Day Outside Time can be an
optional user-entered birthday; otherwise it is the final date in the year.
February 29 anniversary behavior has an explicit policy. Counted weekdays
advance only on counted days. The ephemeris covers modern years 1800–2200.

The Moon's synodic phase is independent of the 28-day chamber. The current
truncated Meeus series targets within four hours of U.S. Naval Observatory
major-phase timings in the tested modern fixture. Illumination and daylight are
estimates, not local horizon observations.

## Data and limitations

Events, reflections, cycles and settings are local. Journal text, event bodies,
and a configured birthday are encrypted using an Android Keystore AES-GCM key.
No automatic cloud backup, no analytics, and no AniCloud database access are
included. User-selected JSON export is readable plaintext; guard its destination.
The `anytime.export.v1` archive contains calendar and display settings, events,
reflections and cycles. Import previews and atomically merges new records;
identical records are skipped. It intentionally keeps the receiving device's
profile and calendar settings, so imported Anytime recurrences use that origin.
Align the origin before importing recurring events. Import is limited to a
5 MB archive with at most 5,000 records per category in this first version.

The first APK contains local event creation, inexact opt-in reminders with
Android notification permission, and custom monthly/annual recurrence,
journal, personal cycles, a Moon page, radial and accessible grid year views,
dual season frames, settings and onboarding. Editable entries,
location permissions, astronomical rise/set, `.ics` exchange, full encrypted
backup, widgets, and a live AniCloud bridge are not implemented yet. The JSON
merge import is not an encrypted full-device backup: restoring appearance and
personal profile values remains a manual step.
No agent is required to use the app.
