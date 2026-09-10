# AniCloudAI native Android cockpit

This module is the private Pixel 10 Pro reference cockpit for Project Intermix.
The original disconnected shell has passed its first device test. The current
`0.8.2-session-boundary` candidate combines the stable E4B LiteRT-LM boundary with
the native Memory Matrix, controller-mediated project workspace, and the first
checkpointed long-form work-session loop. Its cyan–magenta–violet spectral
glass pass concentrates fluorescence on focus, mode, code, and mission progress
while preserving neutral long-form reading surfaces.

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
- app-private SQLite/WAL conversation and durable-memory tables with FTS5
  retrieval, provenance, revision records, and one-time JSON-history migration;
- non-destructive `/sessions list|new|open <reference>` controls that reset native
  model context while preserving earlier sessions, durable memories, model files,
  and the SAF workspace grant;
- bounded context rehydration before each model turn;
- a versioned Interaction Profile Matrix with explicit-evidence adaptation,
  undo, and deterministic General/Continuity/Project context selection;
- a persisted Storage Access Framework project tree with automatic list/read
  tools, approval-gated create/write/mkdir actions, and pre-write snapshots;
- a dedicated Long Forge workspace conversation that accepts one complete
  objective, up to 120 scoped controller actions, queued mid-run guidance,
  periodic Matrix context rebuilding, recursive-loop detection, and durable
  pause/resume checkpoints;
- a real Agents approval queue plus pin/forget controls in the Matrix surface;
- custom destination icons that preserve the cyan-purple cockpit language;
- actual Android `MemAvailable` and categorical thermal-pressure signals; and
- thermal-aware visual motion and model initialization.

The app still has no online grounding, Android provider vault, Termux command
bridge, Kokoro voice, isolated code runner, or unattended scheduler. E2B has a
fingerprint-locked Tensor G5 import and NPU route, but remains device-evidence
gated rather than a general fallback.
Those surfaces remain visibly unavailable instead of simulating success.

## Model import and storage

The APK requests no broad storage permission. Android's Storage Access
Framework grants access to one selected file, which AniCloudAI copies before
initializing. The copied file is named by its SHA-256 digest and stored beneath
`noBackupFilesDir/models`; the external source is never executed in place.

Workspace access uses a separate persisted SAF tree chosen in Workspace Lens.
Choosing an Internal Storage folder grants reach only within that tree. In
ordinary Chat, reads can be controller-executed while file creation,
replacement, and directory creation must be approved in Agents. Starting a
Long Forge work session visibly grants those three mutation types inside one
named subdirectory for at most 120 actions and 1 MiB of attempted write content.
Deletion remains disabled in this candidate.

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
9. Use `/remember`, close and reopen the app, then verify the fact is visible in
   Matrix and influences a relevant later turn.
10. Connect a disposable project tree, ask chat to list and read a file, then
    request an edit and verify no bytes change before approval in Agents.
11. Open Workspace → Work Session, give a disposable ASCII subdirectory and a
    multi-file objective, then verify several actions complete without a manual
    resume between files. Queue guidance while it runs, press STOP, and verify
    explicit Resume continues from the Matrix checkpoint rather than recent
    chat guesses.

Do not import a live Termux SQLite database. A later bridge must use a reviewed
export so two runtimes never concurrently open the same database. The next
native gates are provider grounding and a Keystore-backed credential vault;
dual-model routing begins only after a compatible E2B package exists.
