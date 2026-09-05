# AniCloudAI Android foundation

This module is the private native-shell foundation for Project Intermix. It is
deliberately disconnected from private memory, Termux, providers, voice, and
LiteRT-LM. Its first job is to prove the Android interaction contract without
simulating capabilities that have not been integrated.

## What runs now

- Android biometric or device-credential gate with a 30-second return grace;
- Sovereign Obsidian, System, and Light appearances;
- compact phone and fixed three-pane desktop layouts;
- a separate saved layout preference for each Android display;
- dashboard, conversation, agents, and System Lens destinations;
- Performance, Adaptive, and Quality selection;
- one-to-seven-line native composer with Return-as-newline and visible Send;
- synthetic streaming with immediate STOP and quarantined partial output; and
- truthful disconnected model, memory, grounding, and thermal states.

Synthetic streaming is not model inference. This build writes no conversation
database, contacts no provider, requests no runtime permission, and does not
read the live Termux installation.

## Pinned build baseline

| Tool | Version |
|---|---:|
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| JDK | 17 |
| compile / target SDK | 37 |
| minimum SDK | 31 |
| built-in Kotlin | 2.3.10 |
| Compose BOM | 2026.08.00 |

The source includes `gradle-wrapper.properties`, but not the generated binary
`gradle-wrapper.jar`. On the first trusted development machine, install the
pinned Gradle version and create the wrapper once:

```bash
cd android
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
./gradlew --version
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Pushing a branch named `feature/anicloud-android-*` runs the same build in
GitHub Actions and retains `AniCloudAI-foundation-debug` for seven days. This is
an unsigned debug artifact for private device testing, not a public release.

Do not sign or distribute the foundation APK. The application ID
`dev.anicloud.sovereign.prototype` is provisional and release signing remains
an owner-controlled later gate.

## First Pixel test

1. Install the debug APK on the reference Pixel 10 Pro.
2. Confirm fingerprint and device-PIN fallback, then the 30-second grace.
3. Paste a seven-line prompt, rotate, enter and leave desktop mode, and confirm
   the draft is preserved where the current foundation supports recreation.
4. Start the synthetic response and press STOP repeatedly while resizing.
5. Switch Performance, Adaptive, and Quality for one response each.
6. Verify every model, memory, grounding, update, and thermal value remains
   explicitly disconnected.
7. Capture layout or lifecycle failures without importing private Termux data.

The next adapter is a lifecycle-safe LiteRT-LM boundary with synthetic numeric
integrity fixtures. Termux migration follows only after that adapter can be
cancelled and restarted safely.
