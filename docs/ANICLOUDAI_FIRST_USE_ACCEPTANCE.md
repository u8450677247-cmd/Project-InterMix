# AniCloudAI First-Use Acceptance Flight

This is the release-gate journey for a newly installed or in-place updated AniCloudAI dogfood APK.
It tests the questions a real user sees, explains why each question exists, and verifies that
declining an optional grant does not damage the local experience.

Passing means the signed build behaves correctly on the tested device. Opening the Home screen is
not a pass, and a model saying that an action succeeded is never evidence that Android performed it.

## Before takeoff

1. Keep the currently installed dogfood app. An in-place update preserves app-private conversations,
   Matrix state, imported models, and settings when the signing certificate is unchanged.
2. Removing old downloaded `.apk` installer files is safe. **Uninstalling AniCloudAI is different:**
   Android removes its app-private data and imported model copies.
3. Use a disposable project folder for Workspace and Forge testing. Do not connect the live source
   tree until navigation, approval, trash, and recovery have passed.
4. Keep the Pixel on power and cooled. Record available memory and Android thermal state before
   importing or switching a model.
5. Stop at the first critical continuity, scope, deletion, or recovery defect. Do not hide a broken
   ordinary chat path underneath a successful endurance benchmark.

## What AniCloudAI may ask

| Prompt or picker | Why it appears | Safe result if declined |
|---|---|---|
| Android fingerprint, PIN, or password | Unlocks the local cockpit through Android authentication | The Core stays locked; authenticate again without losing state |
| Notification permission | Shows foreground generation progress and a system STOP action | Local foreground chat must still work; background visibility is reduced |
| E4B document picker | Copies one user-selected `.litertlm` reasoning model into app-private no-backup storage | No model changes; reopen the picker later |
| E2B document picker | Imports the exact reviewed Tensor G5 conversation/NPU candidate | E4B remains the usable route; no GPU substitution is allowed for E2B |
| Workspace tree picker | Grants persistent access to one selected project tree through Android's Storage Access Framework | Existing grant remains unchanged; choosing nothing changes no files |
| Termux command permission | Enables the separately switched-on developer execution plugin | Native Chat, Matrix, Workspace, Numeric Matrix, and Story Forge remain available |

This candidate declares no Android `INTERNET` permission and has no API Token Vault. It must not ask
for an API key, mobile-data access, a provider login, Accessibility, all-files access, device
administrator access, or root. Treat any such request as a release-blocking defect for this build.

## Flight A — identity, update, and truthful boundaries

1. Install the new APK over the previous dogfood build. If Android refuses the signature, stop and
   record both certificate hashes; do not uninstall merely to force the update.
2. Unlock once with a fingerprint and once with the Android device credential. Cancel one unlock
   attempt and confirm that the cockpit remains locked and offers a clean retry.
3. Review the notification prompt. Grant it for the primary flight. Later, deny it from Android
   settings and confirm an ordinary foreground turn still completes and exposes in-app STOP.
4. Open **System** and record `/version`, model, backend, build, device RAM, thermal state, and route.
5. Confirm **External Data Boundary** says `OFFLINE` and **API TOKEN VAULT** says `NOT ENABLED`.
6. Confirm **Developer plugin · Termux** starts disabled.

## Flight B — model import and recovery

1. If an in-place update already shows the prior E4B fingerprint, do not re-import it. This proves
   update persistence.
2. Otherwise choose **Import E4B**, cancel the picker once, and confirm the previous route and model
   inventory remain unchanged.
3. Import the reviewed E4B file. Keep AniCloudAI visible during the first multi-gigabyte copy. Record
   filename, full SHA-256, copied bytes, load time, backend, memory before/after, and thermal state.
4. A failed import must keep the last usable model and expose a retry; it must not leave Send falsely
   enabled against a disconnected engine.
5. Do not import E2B yet. Establish the E4B control result before testing the NPU candidate.

## Flight C — the ordinary conversation people try first

Use **Adaptive** unless a step names another mode.

1. Send: `What's up? In one short paragraph, tell me what you can do entirely on this device.`
2. Send: `For this acceptance flight, remember that my codename is Brass Moth.`
3. Send: `What was my codename, and what did I ask immediately before I gave it to you?`
4. Verify all three user messages and all three Core responses remain visible in chronological order.
5. Wait for the next runtime or `[INFO]` card. It must append after the response rather than replace
   any prose.
6. Long-press and drag across one sentence, copy it, and paste it into the composer. Then use
   **COPY ALL** and verify the whole card is copied.
7. Move through Home, Matrix, Files, Agents, and System, then return to Chat. The transcript and draft
   must remain intact.
8. Resize through portrait, landscape, split-screen, free-form, and desktop mode. No message or Send,
   STOP, mode, or navigation control may become unreachable.
9. Swipe AniCloudAI out of Recents, reopen it, authenticate, and ask: `What codename did I give you?`
   Both the visible transcript and bounded recent-turn recall must survive.

## Flight D — mode, numbers, cancellation, and sessions

1. Run `/capabilities`, `/models`, `/device`, `/why`, and `/version`. Controller responses must report
   only implemented or explicitly unavailable capability.
2. Run `/calc (19.75 * 4) + 6.50`. The verified Numeric Matrix result must equal `85.5`, retain the
   original expression, and appear in the durable ledger without trusting model arithmetic.
3. Ask the model to explain the calculation. Its prose may vary; the controller-owned value may not.
4. Start a long Quality response, press **STOP** during generation, and confirm safe partial prose is
   visibly marked as an interrupted, unverified draft.
5. Send a clean follow-up. The stopped draft must not be recalled as a completed answer.
6. Run `/sessions new`, send one recognizable sentence, run `/sessions list`, reopen the earlier
   reference, and verify neither session was deleted or merged.

## Flight E — project permission and human file controls

1. Open **Files → Connect project**, cancel once, and confirm no new grant or empty project appears.
2. Select the disposable project tree. Confirm AniCloudAI shows that exact root and never asks for
   all-files access.
3. Press **New folder**, enter `flight-check`, cancel, and verify nothing was created.
4. Repeat and confirm. Enter `flight-check`, then prove Android back, **UP**, and **ROOT** each return
   to the expected level without losing the grant.
5. Ask Chat to propose creating `flight-check/hello.md`. In **Agents**, deny the first proposal and
   verify no bytes changed. Submit it again, review the exact path and content, approve it, and verify
   the file appears.
6. Edit the file, navigate away with an unsaved draft, and confirm the app blocks or clearly resolves
   the transition. Save deliberately and reopen the file to compare exact bytes.
7. Select **Trash**, cancel the first confirmation, then confirm the second. Verify the file moves to
   project-local recoverable trash, use **Undo**, and confirm its contents return unchanged.
8. Trigger another runtime receipt and verify it appends to Work Session without replacing the last
   generated or controller-committed message.

## Flight F — bounded autonomy

1. Start Story Forge in a fresh benchmark folder. Let at least three chapters commit without another
   tap, then press STOP.
2. Confirm the last complete chapter remains in both Work Session and `story.md`; any partial chapter
   is not counted. Leave Workspace, reopen it, and verify the checkpoint is still Paused.
3. Press **Resume** once. The next marker must be new, consecutive, and not duplicated.
4. Only after STOP/resume passes, run the complete
   [120-chapter Story Forge benchmark](ANICLOUDAI_120_CHAPTER_STORY_FORGE.md).
5. Run the [120-action Long Forge benchmark](ANICLOUDAI_120_ACTION_FORGE_BENCHMARK.md) separately.
   A literary pass does not prove multi-file planning, and a coding pass does not prove story quality.

## Flight G — optional E2B/NPU candidate

1. Record the healthy E4B baseline, then import the reviewed E2B artifact.
2. Verify the complete E2B SHA-256, Pixel/Tensor device tuple, packaged dispatcher, available-memory
   gate, and thermal gate before initialization.
3. Record NPU initialization, first-token time, warm-turn time, sustained rate, route-switch time,
   memory, and temperature category.
4. Performance chat must visibly select E2B/NPU only after those gates pass. E2B may never silently
   fall back to GPU.
5. Trigger STOP during E2B generation and run the three-turn recall flight again.
6. Force one safe E2B refusal or initialization failure. The error must remain truthful and E4B must
   recover as the usable route without losing the transcript.

E2B remains **unproven** until this exact device flight passes. Successful packaging or fingerprint
recognition alone is not an inference result.

## Flight H — optional Termux developer plugin

Run this only after Flights A–F pass.

1. Enable **Developer plugin · Termux** deliberately. Grant its custom command permission only now.
2. In Termux, enable external apps and configure the exact project root shown by `/exec status`.
3. Prepare `inspect_environment`; review command, working directory, timeout, dependency list, and
   network declaration in Agents. Deny it once and verify nothing runs.
4. Prepare it again, approve it, and verify bounded stdout/stderr plus exit metadata return to the
   durable execution record.
5. Repeat with one test command. A dependency proposal must name packages and disclose network use;
   deny it unless the manifest/lockfile evidence and exact install command are correct.
6. Start a harmless long-running test, press STOP, and wait for a Termux exit record.
7. Disable the plugin. Native features must remain usable and no command may execute afterward.

## Pass record

```text
Build / version code:
Git commit:
Workflow run:
APK SHA-256:
Signing certificate SHA-256:
Device / Android build:
E4B filename / SHA-256 / backend:
E2B filename / SHA-256 / backend, or NOT TESTED:
Memory before / after model load:
Authentication and notification result:
Conversation continuity: PASS / FAIL
Sentence copy / COPY ALL: PASS / FAIL
STOP and clean recovery: PASS / FAIL
Session restart: PASS / FAIL
Workspace picker / New folder / Up / Root: PASS / FAIL
Approval deny / approve: PASS / FAIL
Trash cancel / move / undo: PASS / FAIL
Numeric Matrix: PASS / FAIL
Story Forge STOP / resume: PASS / FAIL
120-chapter controller gate: PASS / FAIL / NOT RUN
120-action engineering gate: PASS / FAIL / NOT RUN
E2B NPU flight: PASS / FAIL / NOT RUN
Termux plugin flight: PASS / FAIL / NOT RUN
Unexpected permission or data request:
First failing step and exact visible message:
Screenshots / logs / artifact hashes:
```

The public-preview decision requires Flights A–F plus the signed-build integrity check. E2B and
Termux may remain separately labelled experimental, but neither may be presented as working from
source integration alone.
