# AniCloudAI native Android cockpit

This module is the private Pixel 10 Pro reference cockpit for Project Intermix.
The original disconnected shell has passed its first device test. The current
candidate adds the first real E4B LiteRT-LM boundary without pretending that
memory, grounding, voice, agents, or E2B routing are already native.

## What runs in this candidate

- Android biometric or device-credential gate with a 30-second return grace;
- Sovereign Obsidian, System, and pale-blue Light appearances;
- one-pane layouts below `1200dp` and a fixed three-pane cockpit at Android's
  large-width breakpoint, with user overrides saved per display;
- one-file Android picker for a local `.litertlm` package;
- streaming SHA-256 verification while the model is copied into app-private,
  no-backup storage;
- one resident E4B engine with GPU initialization first and CPU fallback;
- an 8,000-token physical context and bounded per-mode output limits;
- real native token streaming with an always-visible STOP control;
- STOP recovery that cancels JNI inference, discards partial output, and
  rebuilds conversation state before the next turn;
- deterministic repetition, Unicode, output-length, and exact-number guards;
- actual Android `MemAvailable` and categorical thermal-pressure signals; and
- thermal-aware visual motion and model initialization.

The app still has no native durable-memory database, online grounding,
Termux bridge, Kokoro voice, foreground agent service, E2B model, or release
update channel. Those surfaces say so explicitly.

## Model import and storage

The APK requests no broad storage permission. Android's Storage Access
Framework grants access to one selected file, which AniCloudAI copies before
initializing. The copied file is named by its SHA-256 digest and stored beneath
`noBackupFilesDir/models`; the external source is never executed in place.

For the current Termux E4B package, make a temporary picker-visible copy:

```bash
cp "$HOME/project-intermix/models/gemma-4-E4B-it.litertlm" \
   "$HOME/storage/downloads/"
```

Keep enough free space for both the staging copy and the app-private copy
during import. Once the app shows the expected SHA-256 and E4B reaches READY,
the staging copy may be removed deliberately. The app-private copy remains.

## Native runtime baseline

| Component | Pinned version or policy |
|---|---:|
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.4.1 |
| JDK | 17 |
| compile / target SDK | 36 |
| minimum SDK | 31 |
| built-in Kotlin | 2.3.10 |
| Compose BOM | 2026.03.01 |
| LiteRT-LM Android | 0.16.1 |
| packaged ABI | arm64-v8a |
| physical context | 8,000 tokens |

API 36 remains the reproducible CI compile baseline while the Pixel 10 Pro
runs Android 17. Raise compile and target SDK together only after API 37 is in
the hosted command-line SDK index. No NPU/TPU claim is made: GPU is attempted,
CPU is the measured fallback, and the active backend is visible in System Lens.

The source includes `gradle-wrapper.properties`, but not the generated binary
`gradle-wrapper.jar`. On a trusted development machine with the Android SDK:

```bash
cd android
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Pushing a branch named `feature/anicloud-android-*` runs the Android workflow
and retains `AniCloudAI-e4b-cockpit-debug` for seven days. The result is a debug
artifact for private device testing, not a signed public release.

## Termux-assisted dogfood updates

Clean GitHub runners generate unrelated debug certificates. Installing those
artifacts directly would eventually force an uninstall and erase app-private
state. `tools/termux_dogfood_update.sh` instead downloads one successful
artifact, replaces the disposable CI signature with a persistent private
dogfood signature held only by Termux, verifies the signed APK, prints both
hashes and the signing-certificate fingerprint, and optionally opens Android's
visible installer. It never performs a silent installation.

The first dogfood-signed build is a one-time trust boundary. Remove the earlier
foundation APK before installing it, then keep the generated keystore and its
password file private and backed up. Later builds signed by that same key update
in place and preserve the app-private model. The future public release key is a
separate identity.

After installing Termux's JDK, Android signing tools, and GitHub CLI, the first
explicit run is:

```bash
bash tools/termux_dogfood_update.sh --initialize-key --open
```

Subsequent updates omit `--initialize-key`:

```bash
bash tools/termux_dogfood_update.sh --open
```

## Pixel E4B smoke test

1. Install the new debug APK. If Android reports an incompatible signature,
   remove the earlier debug build first; it contains no imported model data.
2. Authenticate with fingerprint, then verify device-PIN fallback.
3. Choose the picker-visible E4B `.litertlm` file and keep AniCloudAI in the
   foreground through the first multi-gigabyte copy and initialization.
4. Record the displayed full SHA-256, selected backend, initialization time,
   available-memory headroom, and thermal category.
5. Send the exact-number benchmark in Quality mode. Verify all anchors survive.
6. Start a longer response, press STOP, and verify the partial answer is not
   committed and the following clean response succeeds.
7. Rotate, resize, and enter desktop mode before, during, and after generation.
8. Repeat one response in Performance, Adaptive, and Quality. Until E2B exists,
   Performance and Adaptive must honestly show an E4B fallback route.

Do not import the Termux memory database yet. The next gate after this smoke
test is durable local conversation storage, followed by the versioned Termux
bridge. Dual-model routing begins only after a compatible E2B package exists.
