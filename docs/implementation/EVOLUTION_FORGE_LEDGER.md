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
