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
