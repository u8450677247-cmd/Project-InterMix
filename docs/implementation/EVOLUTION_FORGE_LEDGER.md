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
