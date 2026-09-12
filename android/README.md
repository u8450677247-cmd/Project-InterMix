# AniCloudAI native Android cockpit

This module is the private Pixel 10 Pro reference cockpit for Project Intermix.
The original disconnected shell has passed its first device test. The current
`0.8.6-surgical-fluency` source candidate combines the stable E4B boundary with
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
- STOP recovery that cancels JNI inference, keeps the safe visible draft clearly
  marked as non-canonical, and rebuilds conversation state before the next turn;
- deterministic repetition, Unicode, output-length, and exact-number guards;
- app-private SQLite/WAL conversation and durable-memory tables with FTS5
  retrieval, provenance, revision records, and one-time JSON-history migration;
- non-destructive `/sessions list|new|open <reference>` controls that reset native
  model context while preserving earlier sessions, durable memories, model files,
  and the SAF workspace grant;
- roughly 1K-token recent-session context rehydration before every model turn, including
  ordinary chat that does not use explicit continuity wording;
- a versioned Interaction Profile Matrix with explicit-evidence adaptation,
  undo, and deterministic General/Continuity/Project context selection;
- a persisted Storage Access Framework project tree with automatic list/read
  tools, approval-gated create/write/mkdir actions, pre-write snapshots, Up/back
  navigation, whole-message copy, and explicitly confirmed recoverable trash;
- a dedicated Long Forge workspace conversation that accepts one complete
  objective, up to 120 scoped controller actions, queued mid-run guidance,
  periodic Matrix context rebuilding, recursive-loop detection, and durable
  pause/resume checkpoints;
- automatic correction of up to four narration-only mission responses before a
  pause, plus mission-relative controller paths that remain inside the exact grant;
- append-only controller-cycle messages and safe interrupted Work Session drafts
  that remain visible while draft sources are excluded from model recall and
  Memory Matrix learning;
- a Story Forge lane that appends one unnumbered model installment at a time
  into a crash-idempotent, controller-numbered 120-chapter `story.md`;
- a decimal128 Numeric Matrix that computes arithmetic outside the language model
  and stores expression/result/engine provenance;
- an optional developer-plugin Termux RUN_COMMAND bridge for typed inspect/run/test/build and
  dependency-install plans, with exact command previews, declared packages and
  network use, one-at-a-time dispatch, timeouts, bounded results, and STOP;
- a real Agents approval queue plus pin/forget controls in the Matrix surface;
- custom destination icons that preserve the cyan-purple cockpit language;
- actual Android `MemAvailable` and categorical thermal-pressure signals; and
- thermal-aware visual motion and model initialization.

The app still has no online grounding, Android provider vault, Kokoro voice,
in-process code sandbox, live terminal streaming, or unattended scheduler. Its
execution lane is an explicit external Termux boundary, not unrestricted shell
access. E2B has a
fingerprint-locked Tensor G5 import and NPU route, but remains device-evidence
gated rather than a general fallback. The dispatcher is extracted into Android's
native-library directory, and E4B is released before the final NPU memory gate.
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
Model-generated deletion remains disabled. A human can review a specific file or
folder, confirm a reversible move into `.anicloud-trash` within the same granted
tree, and undo the latest move. Provider refusal fails closed without widening
the SAF grant.

## Termux execution setup

The bridge requires a current Termux build with RUN_COMMAND PendingIntent results
(Termux `0.109` or newer). In Termux, enable external command requests once:

```bash
mkdir -p "$HOME/.termux"
if grep -q '^allow-external-apps=' "$HOME/.termux/termux.properties" 2>/dev/null; then
  sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' "$HOME/.termux/termux.properties"
else
  printf '\nallow-external-apps=true\n' >> "$HOME/.termux/termux.properties"
fi
termux-reload-settings
```

Then open AniCloudAI → Agents and tap **GRANT TERMUX COMMAND PERMISSION**. In
Chat, configure the Termux path resolving to the same project selected in
Workspace (often beneath `$HOME/storage/shared`) and enable the bridge:

```text
/exec workdir $HOME/storage/shared/YourProject
/exec on
/exec status
```

Sovereign Core can then inspect project manifests and propose a run, test,
build, or dependency action. Every proposal remains inert until **APPROVE & RUN**
is tapped in Agents. Dependency proposals must list packages and declare network
use. Results return to the originating conversation/Work Session only after the
process exits; stdout and stderr are sanitized, marked untrusted, and retained
with 32 KiB controller ceilings. This bridge never opens the Termux Matrix
database and never inherits a Long Forge filesystem grant. The configured
working directory is not an OS sandbox: an approved project script runs as the
Termux app and inherits Termux's existing storage and network reach. Approve
only commands and scripts you trust.

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
| LiteRT-LM Android | 0.17.0 candidate |
| LiteRT Tensor dispatcher | 2.2.0, checksum-pinned |
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

Use the complete
[`first-use acceptance flight`](../docs/ANICLOUDAI_FIRST_USE_ACCEPTANCE.md) for
the install/update prompts, safe denial and retry paths, ordinary conversation,
sentence copy, session recovery, Workspace controls, Numeric Matrix, Story
Forge, E2B/NPU, and optional Termux-plugin gates. The compact smoke sequence
below remains the minimum E4B engine check.

1. Install the new debug APK. If Android reports an incompatible signature,
   remove the earlier debug build first; it contains no imported model data.
2. Authenticate with fingerprint, then verify device-PIN fallback.
3. Choose the picker-visible E4B `.litertlm` file and keep AniCloudAI in the
   foreground through the first multi-gigabyte copy and initialization.
4. Record the displayed full SHA-256, selected backend, initialization time,
   available-memory headroom, and thermal category.
5. Send the exact-number benchmark in Quality mode. Verify all anchors survive.
6. Start a longer response, press STOP, and verify safe partial prose remains
   marked as an unverified draft, is excluded from recall, and the following
   clean response succeeds.
7. Rotate, resize, and enter desktop mode before, during, and after generation.
8. Repeat one response in Performance, Adaptive, and Quality. Until E2B exists,
   Performance and Adaptive must honestly show an E4B fallback route.
9. Use `/remember`, close and reopen the app, then verify the fact is visible in
   Matrix and influences a relevant later turn.
10. Connect a disposable project tree, ask chat to list and read a file, then
    request an edit and verify no bytes change before approval in Agents.
11. Run the exact
    [`120-chapter Story Forge benchmark`](../docs/ANICLOUDAI_120_CHAPTER_STORY_FORGE.md)
    as the single-file one-click endurance gate. Then run the separate
    [`120-action Long Forge benchmark`](../docs/ANICLOUDAI_120_ACTION_FORGE_BENCHMARK.md)
    for multi-file engineering, scope checks, and artifact verification.

Do not import a live Termux SQLite database. The execution bridge exchanges only
reviewed commands and bounded result records; a future memory bridge must use a
reviewed export so two runtimes never concurrently open the same database. The next
native gates are provider grounding and a Keystore-backed credential vault;
dual-model routing begins only after a compatible E2B package exists.
