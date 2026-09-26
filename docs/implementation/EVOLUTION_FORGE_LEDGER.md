# Evolution Forge Ledger

This ledger records verified patch evidence. Model narration is never treated as execution evidence.

## Recovery REC-00 — Preserve the stalled Resonance work

Exact blocker:

- The Android Gradle dependency-hydration attempt produced no compiler result and the execution environment recycled while using the repository's `-Xmx4g` daemon setting.
- The recycle restored a clean provider-flight checkout. The Resonance/Evolution work was uncommitted, absent from reflog and unreachable-object scans, and therefore not recoverable from Git.
- The canonical v1.1 specification and starter-profile artifacts remained available and were used to restore only the smallest previously verified boundary.

Lowest-risk alternative:

- Isolate the feature branch at the original `572561d4e28ce0fbc259dec22f08eafabfccf79e` tree.
- Compile only the pure Kotlin Resonance core and its focused JVM tests with Kotlin 2.3.10 and `-Werror`.
- Avoid the Android SDK, Gradle daemon, dependency graph, UI, database, and controller integration until this slice is committed.

Result: **RECOVERED AND CHECKPOINTED**

## Patch R-CORE-01 — Resonance deterministic core

Intent:

- Restore the canonical 25-trait model, five v1.1 starter profiles, deterministic resolution, temporary session modes, reversible feedback mapping, bounded Delivery Contract compiler, cue separation, and inert Experience Pack validation.

Acceptance evidence:

- Kotlin 2.3.10 compilation with `-Werror`: PASS.
- Focused JUnit suite: 8/8 PASS in 0.1 seconds.
- Existing Android foundation source-contract suite: 42/42 PASS with the standard-library runner.
- Canonical starter-profile JSON parse: PASS.
- `git diff --check`: PASS.
- Delivery Contracts: 293–311 characters and 74–78 estimated tokens across all five profiles; hard limits are 720 characters and 180 estimated tokens.
- Resolver benchmark: 100,000 Sovereign resolutions in 814.296 ms; 8.143 microseconds average in this host environment.

Pros:

- Pure deterministic code is testable without Android or network state.
- Factual memory, credentials, and controller authority remain structurally outside Resonance.
- Sparse canonical profiles resolve to a complete 25-trait map without duplicating giant prompts.
- Explicit preferences outrank inferred preferences; otherwise the narrowest scope wins.
- Session modes are call-scoped and cannot persist by accident in this layer.

Cons / costs:

- Persistence, Android UI, prompt injection, and process-death restoration are deliberately not part of this recovery patch.
- Undeclared starter traits use the reviewed neutral value `0.50`; persistence must retain that deterministic fallback.
- The local compiler bundle is a test tool only and is not committed.

Regression check:

- No existing source file or schema was changed.
- The patch adds one pure Kotlin source, one test source, and one canonical asset.

Security / recovery:

- Pack fields containing credentials, tools, permissions, authority, filesystem, network, shell, script, or command capability are rejected.
- Hostile instruction-like names and rule values are rejected.
- Delivery compilation uses fixed local strings and numeric bands; it does not copy pack labels or user text.
- The verified slice is committed before any wider integration resumes.

RAM / thermal / storage / latency:

- No resident service, database, worker, or UI allocation is introduced.
- The measured resolver average is 8.143 microseconds on the current host.
- No device thermal claim is made; hardware validation remains separate.

UI / design-language:

- NO VISIBLE UI CHANGE.

Improvement opportunities:

- Add additive Android-private persistence with reset, undo, and migration tests.
- Validate the canonical JSON asset against the in-code sparse maps in the repository contract suite.

Optimization path:

- Cache resolved immutable profiles only after persistence invalidation semantics exist and measurements justify it.

Innovation opportunity:

- Signed Experience Packs can later reuse the same inert validator without gaining executable authority.

Decision:

**KEEP**

Next highest-value action:

- Add bounded, additive Resonance persistence and canonical asset parity tests without touching Matrix truth or provider authority.

## Patch E-CORE-01 — Evolution Forge deterministic controller

Intent:

- Restore a pure controller-owned patch state machine, typed patch/review/backlog/guidance state, bounded context reconstruction, private proposal parsing, and exact process-death serialization before Android integration.

Acceptance evidence:

- Kotlin 2.3.10 compilation with `-Werror`: PASS.
- Focused JUnit suite: 12/12 PASS in 0.131 seconds.
- First test run: 11/12; a saturated context could truncate the final authority reminder.
- Lowest-risk correction: move the fixed controller-authority invariant ahead of variable evidence sections.
- Second test run: 12/12 PASS; no second implementation failure occurred.
- `git diff --check`: PASS.
- Synthetic checkpoint: 24,780 UTF-8 bytes.
- Saturated compiled mission context: 4,062 characters against a 6,000-character hard limit.
- 10,000 synthetic checkpoint decodes: 11,522.055 ms; 1,152.205 microseconds average on this host.

Pros:

- Persisted stage, not narration, owns mission progress.
- A controller-verified mutation is required before test evidence, and a controller-verified passing test is required before `KEEP`.
- Successful tests advance only from the persisted `TEST` stage.
- Ranked safety work overrides an arbitrary model suggestion at `SELECT_NEXT`.
- New patch selection clears patch-local evidence while retaining mission reviews, findings, budgets, and completed patch history.
- Guidance IDs stay monotonic after the bounded queue evicts old entries.
- Failed patches support `REFINE`, `PARTIAL REVERT`, or `REPLACE` without losing review history.

Cons / costs:

- Android persistence and native Work UI are not wired in this slice.
- JSON decode is comfortably bounded but slower than the earlier prototype measurement; optimization is deferred until integration profiling shows it matters.
- Strict ASCII workspace-relative evidence paths intentionally reject unusual filenames at this controller boundary.

Regression check:

- Existing Android foundation source-contract suite remains 42/42 PASS.
- No existing mission, workspace, Matrix, or UI implementation was changed.

Security / recovery:

- Traversal, absolute paths, drive-qualified paths, control characters, duplicate private proposals, unknown actions, and incomplete reviews are rejected.
- Process-death JSON round-trip preserves the exact typed state, including a paused mission's resume stage.
- Context reconstruction includes only bounded current state and newest guidance; it does not accumulate a transcript.

RAM / thermal / storage / latency:

- No worker, service, database, or model allocation is introduced.
- Synthetic checkpoint size and decode latency are recorded above; no device thermal claim is made.

UI / design-language:

- NO VISIBLE UI CHANGE.

Improvement opportunities:

- Persist the typed state inside the existing mission checkpoint and expose its stage without weakening Long Forge controls.
- Add canonical source-contract assertions before native UI wiring.

Optimization path:

- Profile JSON decode after real process-death integration; cache only if resume latency is user-visible.

Innovation opportunity:

- The same typed patch protocol can later drive GitHub/cloud Work while preserving the Android controller's evidence rules.

Decision:

**KEEP**

Next highest-value action:

- Add additive Resonance persistence and embed Evolution Forge state in the existing durable mission checkpoint.

## Verification blocker V-INT-01 — Android integration without dependency hydration

Exact blocker:

- The checkout contains Gradle wrapper properties but no wrapper script/JAR, no Gradle installation or cached distribution survived the executor recycle, and the prior 4 GiB dependency-hydration path is intentionally not being retried.
- The first repository-layer Kotlin compile exposed one real preserved-patch error: a heterogeneous Resonance undo `arrayOf` inferred an unsupported intersection type under Kotlin 2.3.10.
- Two new source-contract runs then failed on assertion drift only: the production constants are named `MAX_CHARACTERS` / `MAX_ESTIMATED_TOKENS`, and the canonical v1.1 starter artifact stores numeric `"schema_version": 1`.

Lowest-risk alternative:

- Replace the mixed undo tuple with a typed `ResonanceUndoTarget`.
- Parse every modified Kotlin file with the Kotlin compiler front end.
- Type-check the complete Matrix repository plus the real Resonance/Evolution/Profile/Continuity sources against temporary Android-compatible database interfaces.
- Calibrate source assertions to the exact recovered canonical artifact; do not mutate that artifact to satisfy a test assumption.

Additional result:

- Repository-layer Kotlin type-check after the typed undo fix: PASS.
- Full changed-file Kotlin syntax parse: PASS (6/6 files).
- First stubbed ViewModel semantic compile found and fixed a real suspension-boundary error: `buildEvolutionForgePrompt` now suspends because `controllerContext()` suspends.
- The second full ViewModel semantic compile produced no output for more than 90 seconds and was terminated with exit 130. It was not retried.
- Pure Resonance/Evolution tests remain 25/25 PASS; Android source contracts are 46/46 PASS after exact artifact calibration and executable SQLite DDL validation.

Status: **BLOCKER RECORDED; FULL VIEWMODEL TYPE-CHECK SKIPPED**

## Patch INT-01 — Resonance persistence and Evolution Forge runtime wiring

Intent:

- Wire the already-reviewed Resonance and Evolution cores into the existing Matrix, prompt,
  Long Forge, Workspace, Termux approval, and Work UI boundaries without adding a new service or
  widening filesystem, execution, network, memory, or provider authority.

Acceptance evidence:

- Pure Kotlin compile with Kotlin 2.3.10 and `-Werror`: PASS.
- Focused Resonance/Evolution JUnit suite: 25/25 PASS.
- Matrix repository semantic compile against temporary Android-compatible database interfaces: PASS.
- All six modified Kotlin production files parsed with the Kotlin compiler front end: PASS.
- Android foundation source-contract suite: 46/46 PASS.
- Resonance v6 migration DDL executed on stock SQLite with foreign keys enabled: PASS.
- Complete offline Python suite: 178 tests PASS; 5 environment-dependent tests skipped.
- `git diff --check`: PASS.
- The full Android/Compose build remains delegated to branch CI because of blocker V-INT-01.

Pros:

- Resonance is additive, app-private, scoped, reversible, bounded, and visibly user-controlled.
- Evolution state survives process death inside the existing mission checkpoint.
- Workspace writes advance only from controller receipts matching the accepted patch and attempt.
- Test advancement consumes one approved terminal Termux result through a monotonic execution cursor.
- Old write or test evidence cannot be replayed after `REFINE`, `PARTIAL REVERT`, or a new patch.
- Model-authored memory/profile payloads remain disabled inside private Story and Evolution missions.
- The existing Work and Agents surfaces expose stage, mission ID, approval state, and verified boundaries.

Cons / costs:

- The integration is intentionally strict: malformed or narration-only proposals pause after bounded recovery.
- A real device still needs the Termux permission, explicit execution approval, model package, and workspace grant.
- Full Android type-check, Compose compilation, instrumentation, and thermal behavior require CI/device evidence.
- Experience Pack persistence is inert infrastructure; a production signature-verification/import surface is not enabled.

Regression and blocker review:

- Final review caught two pre-commit integration defects: paused Evolution guidance could attempt to
  continue without the normal resume/test-result gate, and a mission-root list observation was recorded
  as the root name instead of `.`. The lowest-risk corrections route `/mission guide` through
  `resumeAgentMission()` and normalize root evidence without changing architecture.
- General Work Session and Story Forge routing remain on their existing paths.
- Evolution completion cannot be accepted from `[MISSION_COMPLETE]` narration.
- The stalled full ViewModel semantic-stub compile was not retried a third time.

Security / recovery:

- One private payload is accepted per Evolution cycle; paths, action/write budgets, patch file sets,
  attempts, and test execution IDs are controller-validated.
- Private model output is withheld from streaming UI, and protocol-leak detection now covers every
  controller envelope.
- Test commands remain visible, separately approved, and executed only through the existing Termux bridge.
- Every Resonance mutation stores a bounded reversible snapshot; Evolution checkpoints are capped at 512 KiB.

RAM / thermal / storage / latency:

- No new resident worker, service, provider connection, or background polling loop is introduced.
- Prompt additions are bounded; Resonance delivery remains capped at 720 characters / 180 estimated tokens.
- Device thermal and end-to-end latency claims remain pending the first CI-built daily-test APK.

UI / design-language:

- The existing Obsidian/fluorescent Work language is retained.
- `/resonance`, `START EVOLUTION FORGE`, stage labels, mission-linked execution cards, and the Agents
  status card make the new boundaries discoverable without creating another navigation system.

Optimization and innovation opportunities:

- After device evidence, measure checkpoint decode, prompt prefill, Matrix growth, and per-stage thermal cost.
- Add cryptographic Experience Pack verification only as a separate reviewed patch.
- Promote recurring device results into ranked Evolution backlog candidates without allowing telemetry to
  mutate controller authority.

Decision:

**KEEP; REQUIRE BRANCH CI BEFORE DEVICE DOGFOOD INSTALL**

Next highest-value action:

- Publish the checkpointed branch, consume the Android CI result, then perform the first approved
  Evolution Forge device run against one disposable workspace patch.

## Patch WS-ID-01 — Controller-owned workspace artifact identity

Objective:

- Stop spending model context on fragile path spelling by adding durable, exact artifact handles
  while preserving the existing Storage Access Framework authority boundary and scoped mission grant.

Verified before state:

- Work Session persisted one canonical mission root and exact project-event paths.
- Existing artifacts had no immutable controller address; every later read or write required the model
  to reproduce a path string.
- Root alias recovery silently collapsed case and punctuation variants, so safe recovery and ambiguous
  drift were not distinguishable.
- Android foundation source contracts passed 46/46 before the patch.

Change:

- Add schema-v7 `workspace_artifacts` operational truth keyed by exact controller `WA-…` ids and
  registered SAF document URIs.
- Re-observing the same document updates path/display/hash metadata without changing its id; a new
  document at the same path retires the old binding rather than inheriting its identity.
- Directory listings, reads, creates, and writes return/register handles. Active mission prompt
  reconstruction includes the bounded artifact index for its exact root.
- Existing-entry list/read/write actions may use an exact `artifact_id`; a supplied model path cannot
  override that identity.
- If a registered URI moved, one bounded 2,048-entry / 16-level scan repairs its canonical path. The
  scan runs only after exact lookup fails and retires an unreachable handle.
- Similar-looking paths now produce review candidates without selecting one. Only exact repeated
  mission roots collapse; case, punctuation, or spelling aliases fail closed.

Test:

- `python -m unittest tests.test_android_foundation`: 48/48 PASS, including executable SQLite
  artifact-registry DDL and duplicate-document rejection.
- `python -m unittest discover -s tests -p 'test_android*.py' -v`: 56/56 PASS, including the
  unchanged release-trust and update-publisher gates.
- `python -m unittest discover -s tests -v`: 180 PASS; 5 environment-dependent Textual tests skipped.
- `python -m compileall -q tests tools engine`: PASS.
- `git diff --check`: PASS.
- GitHub Actions `android-foundation` run `36223329606`: PASS. The pinned JDK 17 / Gradle job ran the
  Android unit tests, assembled the debug APK, staged provenance, and retained the debug artifact.
- Local Android/JUnit execution remains unavailable because the repository has wrapper properties but
  no checked-in wrapper executable/JAR or host Gradle installation.

Regression check:

- No delete, shell, network, provider, release-signing, updater, or permission boundary changed.
- Ordinary writes still require visible approval; scoped mission writes retain the existing bounded grant.
- Story Forge numbering/append logic and the recursion watchdog are unchanged.
- Schema migration is additive and does not reset or rewrite existing Matrix rows.

Known limitation:

- SAF providers that change document URI during a move cannot be correlated without a fresh exact
  listing; the old handle retires rather than guessing from filename or content.
- Rename/move controller actions remain intentionally out of scope for this patch.
- DEVICE VERIFICATION REQUIRED for provider-specific URI stability and process-restart behavior.

Patch Evolution Review:

- Pros: stable identity survives model spelling drift and normal process restart; stale paths never bind
  silently; lookup recovery is bounded and auditable.
- Cons: one additive SQLite table and a small bounded artifact index increase controller state; the first
  stale-handle recovery may scan up to 2,048 entries.
- Resource impact: no polling or resident service; registry writes occur only on observed workspace I/O.
- UX impact: no new human-facing opaque-id UI; ids remain in controller/tool evidence.
- Security/privacy impact: all ids are scoped to the exact persisted root URI and grant no new access.

Result: **KEEP; BRANCH CI PASS; DEVICE VERIFICATION REQUIRED**

Next candidate:

- Add deterministic parent creation as a bounded action bundle; the artifact-identity compile and
  migration gates have passed.

## Patch REC-01 — Bounded missing-parent mission bundles

Objective:

- Repair a safe, common mechanical failure without spending another model turn: when an already
  authorized mission creates a nested target whose parent directories are absent, create those exact
  parents in order and then execute the original target mutation.

Verified before state:

- `prepareWorkspaceMission` created and verified the immutable mission root, but `createFile` and
  `createDirectory` stopped on any absent intermediate parent.
- The model therefore had to emit one speculative directory action per level even though the target,
  root, authorization, and ordering were already known.
- Ordinary Chat mutations were and remain separately approval-gated.
- WS-ID-01 passed Android unit-test compilation and debug APK assembly in Actions run `36223329606`.

Change:

- Derive the exact ordered ancestor paths between the active mission root and a create target; never
  infer names, repair aliases, or cross the root.
- Plan only the missing ancestor suffix and reject duplicate, reordered, non-ancestor, or more than
  eight automatic parent repairs before any write occurs.
- Re-verify the mission root, every existing ancestor, and each newly created SAF directory. A file in
  the chain or an unexpected state change stops the bundle before the target mutation.
- Execute the original create only after its prerequisites are verified, and return one consolidated
  result containing ordered parent outcomes plus the final target result.
- If a provider fails mid-bundle, surface the exact parents already created/reused in the audited
  failure; no unbounded retry is attempted.
- Restrict the bundle to an active scoped mission. Visible ordinary-chat approvals continue to execute
  exactly one requested mutation and never gain implicit parent creation.

Test:

- `python -m unittest tests.test_android_foundation`: 48/48 PASS.
- `python -m unittest discover -s tests -p 'test_android*.py' -v`: 56/56 PASS.
- `python -m unittest discover -s tests -v`: 180 PASS; 5 environment-dependent Textual tests skipped.
- `python -m compileall -q tests tools engine`: PASS.
- `git diff --check`: PASS.
- JVM contract cases passed for exact parent ordering, root confinement, rejection of reordered plans,
  and the eight-directory repair ceiling.
- GitHub Actions `android-foundation` run `36228976805`: PASS. Android unit tests, debug APK assembly,
  provenance staging, and artifact retention all completed successfully.

Regression check:

- No delete, rename, move, shell, network, permission, release, updater, or SQLite schema boundary changed.
- The original target still requires complete non-empty content and byte-for-byte post-write verification.
- Existing files are never overwritten by parent recovery; create conflicts continue to fail closed.
- Mission action, write-byte, recursion, and controller-cycle watchdogs remain in force.

Known limitation:

- SAF provider behavior and partial-bundle reporting still require device verification.
- The bundle deliberately stops above eight missing parents; deeper creation needs a new reasoning step
  or explicit directory work so one proposal cannot fan out into an unbounded write sequence.
- This patch does not add fuzzy recovery, rename/move actions, or ordinary-chat implicit writes.

Patch Evolution Review:

- Pros: removes predictable one-operation-per-inference churn while preserving exact identity, scope,
  deterministic ordering, bounded work, and consolidated audit evidence.
- Cons: one model proposal can now produce up to nine SAF mutations (eight parents plus its target), so
  provider interruption may leave verified empty parents for the user or resumed mission to reuse.
- Resource impact: no resident work or polling; at most eight additional exact tree traversals and
  directory creates occur only for a requested nested mission mutation.
- UX impact: no new prompt or approval; the existing mission report explains the consolidated bundle.
- Security/privacy impact: authority is unchanged and every repair path is a strict target ancestor under
  the already-approved mission root.

Result: **KEEP; BRANCH CI PASS; DEVICE VERIFICATION REQUIRED**

Next candidate:

- Add the missing explicit text-file creation flow in Workspace Lens; existing files were editable and
  folders were creatable, but the visible editor could not create its own file.

## Patch FILE-01 — Verified user-created text files

Objective:

- Let the user create a new text/code file in the currently open Workspace Lens folder and begin editing
  it immediately, without routing a direct UI action through model inference or broadening storage access.

Verified before state:

- Workspace Lens could browse folders, navigate up/root, create a folder, edit and snapshot-save an
  existing text file, and move an entry to recoverable project-local trash.
- It exposed no `NEW FILE` action. Controller-authored creates existed, but those are a different,
  model-mediated approval surface and do not replace a basic editor file-creation workflow.
- REC-01 passed Android unit-test compilation and debug APK assembly in Actions run `36228976805`.

Change:

- Add an explicit `NEW FILE` action beside `NEW FOLDER`, with the current breadcrumb, one leaf-name
  field, visible confirmation, cancellation, and Android-back cancellation.
- Reuse the exact leaf-name validator for files and folders: trim once, preserve case/spelling, reject
  traversal, separators, dot aliases, control characters, and names above 120 characters.
- Reject `.anicloud-trash` and case-insensitive collisions in the open folder before invoking the SAF
  provider. No parent path can be supplied through this surface.
- Create one zero-byte text file through the existing persisted tree grant, read it back, and verify the
  provider retained both the exact empty bytes and exact requested display name.
- Open the verified file in the existing editor immediately. The first content save uses the unchanged
  pre-write snapshot path, so even its empty starting state is retained before replacement.
- Freeze browser open/trash actions while either creation review is active so the displayed parent cannot
  drift between review and confirmation.
- Keep model/controller create actions non-empty. Empty initial content is allowed only for this explicit
  user-driven editor action.

Test:

- `python -m unittest tests.test_android_foundation`: 48/48 PASS.
- `python -m unittest discover -s tests -p 'test_android*.py' -v`: 56/56 PASS.
- Added JVM contract cases for exact case/spelling preservation and rejection of blank, dot, traversal,
  separator, and control-character leaf names; execution is pending branch CI.
- `python -m unittest discover -s tests -v`: 180 PASS; 5 environment-dependent Textual tests skipped.
- `python -m compileall -q tests tools engine`: PASS.
- `git diff --check`: PASS.
- Focused Android/JUnit compilation and debug APK assembly: PENDING BRANCH CI.

Regression check:

- No manifest permission, workspace root, mission grant, controller protocol, delete/trash, shell,
  network, updater, release, or SQLite behavior changed.
- Existing dirty-draft and trash-review interlocks remain; file creation adds the same interlocks.
- The provider receives only the currently open document URI under the persisted tree grant.

Known limitation:

- Provider-specific zero-byte creation, display-name fidelity, editor focus, and first-save behavior still
  require device verification.
- Rename, move between folders, search, and arbitrary file import remain separate capabilities.

Patch Evolution Review:

- Pros: closes a basic editor capability with explicit user intent, exact name validation, provider
  verification, and immediate handoff to the already-protected save path.
- Cons: a confirmed empty file is a real workspace mutation before the user types content; cancellation
  therefore happens before confirmation, and later removal uses the existing recoverable-trash flow.
- Resource impact: one provider create, one empty write/fsync, one bounded read-back, and one directory
  refresh; no service, polling, or dependency was added.
- UX impact: the editor is now self-sufficient for starting a file, while one shared creation card keeps
  folder/file confirmation behavior consistent on phone and desktop.
- Security/privacy impact: no new authority; creation stays inside the currently displayed SAF folder.

Result: **LOCAL CONTRACTS PASS; ANDROID CI REQUIRED**

Next candidate:

- Publish FILE-01 and consume its Android gate, then assess exact user-driven rename as the next bounded
  file-management gap before moving to import or OLED work.
