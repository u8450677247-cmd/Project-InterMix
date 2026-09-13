# Threshold calendar product contract

Status: foundation proposal, version 0.1. Threshold is a standalone, local-first
Android calendar and lifecycle companion designed to interoperate with AniCloudAI
without sharing a live database or silently granting its model authority.

## Purpose

Threshold represents time through several simultaneous, inspectable layers:

1. a sacred 13-chamber year of 28 counted days per chamber;
2. astronomical Moon and Sun state calculated from exact instants and locations;
3. an origin season anchored to the user's privately configured birthplace;
4. a present season calculated for the user's current location, when granted;
5. user-recorded physical, mental, creative, social, and training rhythms; and
6. a civil-calendar bridge for Android events, reminders, import, and export.

The 28-day chambers are symbolic organizational periods. They are not labelled
as astronomical lunar months. The synodic Moon and the chamber count are shown
as independent signals so neither is falsified to make the other fit.

Threshold is not a diagnostic, predictive, or medical system. The app may help
the user observe patterns, but correlation, personal interpretation, and verified
astronomical facts remain visibly distinct.

## Foundational calendar

- A counted year contains exactly `13 * 28 = 364` days and 52 counted weeks.
- Counted weekdays advance only on counted days.
- The user's birth-anniversary date is `THRESHOLD_DAY`, outside chambers and the
  counted weekday cycle.
- The sacred New Year begins on the civil date containing the Northern spring
  equinox in the privately configured origin zone.
- The exact equinox instant remains visible as an astronomical event; civil-day
  indexing begins at local midnight so Android reminders remain understandable.
- Any remaining civil date needed to reconcile one equinox-year interval with
  364 counted days and Threshold Day is a `DRIFT_DAY`. Drift Days are outside
  chambers and weekdays and occur immediately before the next sacred New Year.
- The conversion engine derives the number of Drift Days from the actual interval.
  It does not assume that every Gregorian leap year has the same sacred correction.
- Conversion is bidirectional and deterministic: a civil date maps to either a
  counted `(sacredYear, chamber, day)` tuple or a named outside-time day.

An anniversary has three optional presentations:

- `ORIGIN_GATE`: the configured birth time in the configured origin zone;
- `PRESENT_EQUIVALENT`: the same UTC instant in the current zone; and
- `LOCAL_RITUAL`: the configured ritual time in the current zone, explicitly
  labelled as a personal
  observance rather than the original instant.

## Dual seasons

The sacred calendar remains anchored to the Northern spring and the origin zone.
The astronomical context carries both seasonal frames:

- **Origin season:** solar position and daylight for the configured birthplace.
- **Present season:** solar position and daylight for the current location.

Present location is optional and requested only when a feature needs it. A user
can instead save a coarse place manually. The app must explain which location
produced every rise, set, twilight, daylight, and seasonal value.

Weather is not required to determine astronomical season. Weather integration is
future opt-in data and must not become a hidden network dependency.

## Natal foundation

The initial private profile may contain a local birth instant, canonical UTC
instant, IANA birth zone, birthplace coordinates, and an astronomical natal Moon
state computed by the bundled ephemeris engine. Onboarding collects or privately
imports these values after installation; the repository supplies no personal
defaults.

These values are user data, not source constants. A former name, current name,
birth data, journal content, precise location, and inferred patterns must never
appear in public repository fixtures, diagnostics, screenshots, or logs.

Symbolic labels such as Blue Solar Kin, tarot correspondences, witchy seasons,
ritual names, and personal interpretations are versioned overlays. Each overlay
records its source tradition and whether a value was user-declared, calculated,
or generated. No overlay may overwrite astronomical or civil time.

## Daily experience

The Today surface must answer, without scrolling:

- Where am I in the 13-by-28 year?
- Is today counted time, Threshold Day, or a Drift Day?
- What is the real Moon phase and illumination trend?
- What are the origin and present seasonal positions?
- What did I choose to focus on today?
- Is an AI interpretation present, and what evidence did it use?

The first usable release includes:

- Today, 13-chamber year, day detail, and civil-date translation;
- real lunar phase plus new, quarter, and full Moon events;
- origin and present solar-season context;
- local events, reminders, recurrence, search, and widgets;
- a private journal with user-controlled lifecycle tags;
- export/import with a preview and encrypted archive option;
- reduced motion, TalkBack, keyboard navigation, large text, and opaque fallback;
- read-only AniCloudAI context access; and
- inspect, correct, forget, export, and purge controls.

Tarot spreads, custom ritual builders, pattern comparisons, animated sky maps,
wearable signals, weather, and agent-authored automations follow after calendar
conversion, reminders, persistence, accessibility, and recovery are proven.

## Agent authority

Threshold owns its database. AniCloudAI never opens or copies that database.

The first bridge is read-only and exposes a versioned context snapshot through an
explicit Android component protected by a signature permission. Both private APKs
must be signed by the same dogfood identity for this permission to resolve.

Later mutation requests use a separate typed queue:

1. AniCloudAI submits a bounded proposal such as create-event or append-journal;
2. Threshold validates types, limits, timestamps, recurrence, and target scope;
3. Threshold displays the exact proposed change;
4. the user approves, edits, or rejects it inside Threshold; and
5. Threshold records the result and returns a receipt.

The model cannot grant location, notification, calendar, health, file, or bridge
permissions. Reading context never implies permission to create an event. Journal,
identity, health, and inferred-pattern fields default to unavailable to the bridge
and require separate granular consent.

Every context field carries provenance:

- `ASTRONOMICAL_CALCULATION` for deterministic ephemeris output;
- `CALENDAR_CALCULATION` for 13-by-28 conversion;
- `USER_DECLARED` for explicit identity, meaning, or observation;
- `DEVICE_OBSERVED` for permissioned local signals; or
- `MODEL_INFERENCE` for a revisable AI interpretation.

The AI must not convert a user observation such as recurring winter training into
astronomical causation. It may compare observations across years and describe the
evidence, sample size, uncertainty, and alternative explanations.

## Storage and privacy

- Structured data uses app-private SQLite/WAL storage with migrations and tests.
- Highly sensitive profile and journal bodies use Android Keystore-backed
  encryption and device authentication.
- No backup occurs unless the user explicitly configures an export destination.
- Location history is off by default; current calculations can discard raw fixes.
- Diagnostics are local, redacted, previewable, and manually exported.
- Deletion is explicit and recoverable where practical; full purge is deliberate.
- The public APK contains no private profile, model weight, provider key, or raw log.

## Adaptive presentation

Layout responds to the current window and input mode, not the marketed device name.

### Compact

- one primary surface at a time;
- bottom navigation for Today, Year, Journal, and Agent;
- an animated lunar focus with counted-day and civil bridge immediately readable;
- sheets for supporting context; and
- a composer and actions that remain reachable with the keyboard visible.

### Medium

- navigation rail;
- primary cycle surface plus an optional detail pane; and
- touch-sized controls even when a pointer is connected.

### Expanded and desktop windowing

- navigation rail or compact sidebar;
- a primary year/timeline workspace;
- a supporting context or AniCloudAI pane;
- adjustable density, pointer hover, keyboard shortcuts, and visible focus;
- state preserved through continuous resize; and
- no control positioned beneath caption-bar, taskbar, or system insets.

The default visual system is near-black smoked glass, neutral white/grey reading
surfaces, cyan for active time, radiant purple for boundaries, and magenta for
threshold states. Animation communicates phase changes and transitions. Reduced
motion replaces particles and continuous movement with fades and static gradients.
Thermal pressure may reduce visual effects without weakening calendar accuracy.

## Engineering boundary

Threshold begins as a standalone Kotlin/Jetpack Compose application with a pure
Kotlin calendar domain module. Its domain tests must run without Android, network,
location, UI, or an LLM.

Suggested modules:

- `calendar-domain`: 13-by-28 conversion, outside-time rules, recurrence bridge;
- `astronomy-domain`: Moon/Sun calculations and location-labelled results;
- `threshold-data`: encrypted profile, Room/SQLite, migrations, export/import;
- `threshold-ui`: adaptive Compose application and widgets; and
- `intermix-bridge`: versioned snapshots, permission checks, proposal receipts.

The public contract is `threshold.context.v1`. Unknown fields are ignored, required
fields fail closed, timestamps use ISO 8601 with explicit offsets or `Z`, IANA zone
identifiers are retained, and decimal values are never localized inside protocol
payloads.

## Acceptance gates for the first APK

1. Property tests prove every civil date in a wide year range maps exactly once.
2. Reverse conversion round-trips every counted day and outside-time date.
3. The calendar always exposes 13 chambers of exactly 28 counted days.
4. Weekdays never advance on Threshold or Drift Days.
5. Time-zone tests cover the origin zone, DST locations, travel, and midnight edges.
6. Astronomical fixtures are compared with an independent trusted ephemeris source.
7. Rotation and resize preserve selected date, scroll position, draft, and pane state.
8. Compact, short-wide, medium, expanded, desktop, large-text, and reduced-motion
   screenshots pass semantic contrast and reachability review.
9. AniCloudAI can read only consented fields and cannot mutate calendar state.
10. No private identity or birth value is present in the release source or artifact.
