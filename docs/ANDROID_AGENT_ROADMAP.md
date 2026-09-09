# AniCloudAI native agent roadmap

Status: implementation order and acceptance contract for the private dogfood APK.

AniCloudAI becomes an on-device agent by adding narrow, inspectable controllers around the
resident models. The language model proposes the next action; deterministic Android code owns
permissions, scope, execution, audit history, cancellation, and recovery. Memory improves
continuity but never grants authority.

## 1. Long Forge workspace sessions — current build

`0.8.1-fluorescent-forge` separates ordinary Chat from long-running project work. A work session has a
complete objective, one ASCII workspace root, a selected model posture, a durable Matrix
checkpoint, and a visible foreground STOP path.

- A single launch may execute at most 120 verified `list_files`, `read_file`, `create_directory`,
  `create_file`, or `write_file` actions.
- The grant covers at most 1 MiB of generated write content inside the exact mission root.
- Replacements retain a private pre-write snapshot. Delete, code execution, package installation,
  network access, and paths outside the mission root are unavailable.
- The controller refreshes model context from the Memory Matrix every four actions. It injects the
  objective, user guidance, last verified result, and a recent project-event ledger instead of
  trusting the latest chat turns.
- Repeated action sequences, immediate duplicate actions, malformed paths, self-repeating path
  segments, excessive path depth, native stream corruption, severe thermal pressure, and process
  death pause the mission safely.
- A single in-scope filesystem miss is returned to the model for correction; three consecutive
  tool failures pause the run. Failed attempts still consume the action and write budgets, so
  recovery cannot become an unbounded retry loop.
- Guidance is appended to the checkpoint. It does not replace the original objective.
- Guidance entered while generation is active is queued durably and injected at the next safe
  controller boundary instead of discarding partial work or replacing the objective.
- Work expected to exceed six actions maintains `PROJECT_STATE.md` in the project so architecture,
  decisions, validation, blockers, and the next action remain inspectable outside the database.

Acceptance requires one prompt to create a multi-file project, update `PROJECT_STATE.md`, inspect
its own results, correct at least one seeded defect, and finish with `[MISSION_COMPLETE]` without a
manual `RESUME` after every file. Closing the process must convert an in-flight mission to Paused;
explicit Resume must continue from verified state.

## 2. Native IDE execution

Workspace becomes a usable IDE only after execution is a separate controller rather than a shell
string emitted by the model.

1. Define versioned `run`, `test`, `build`, and `inspect_environment` requests.
2. Add a user-enabled, authenticated Termux companion bridge with a per-project working directory.
3. Preview commands, environment changes, expected outputs, time limits, and network need.
4. Run each process with stdout/stderr streaming, wall-clock and output ceilings, foreground STOP,
   and a complete exit record in the Matrix.
5. Detect project language and existing lockfiles before suggesting dependencies.
6. Generate a dependency plan; require approval before package or network changes.
7. Prefer project-local virtual environments and lockfiles. Never silently use root, alter Android
   settings, or install globally.
8. Re-open changed files in the syntax-colored editor and attach test evidence to the checkpoint.

## 3. Librarian and Memory Matrix offload

The Matrix is not an infinite context window. It is a durable index that decides which small amount
of state earns space in an 8K physical prompt.

- E4B remains the reasoning, coding, and difficult-question model.
- The fingerprint-locked E2B may become a conversation/librarian route only after the Tensor G5
  dispatcher and exact model package pass the device benchmark. It never silently consumes GPU.
- Controller-owned mission state, file hashes, action history, decisions, guidance, blockers, and
  verification results remain canonical. Model summaries are evidence, not authority.
- Long transcripts are compacted into versioned topic and project summaries with provenance.
- Recall is relevance-gated and budgeted. Recent chat cannot displace an active mission checkpoint.
- Retention, database size, archive/export, repair, and pruning must be measurable before background
  summarization is enabled by default.

## 4. Native research and shared browser

Research should live inside a dedicated work session, not gain unrestricted control over the user's
normal browser.

1. Add `INTERNET` only when the research feature exists and explain the permission in context.
2. Use an embedded, inspectable browser surface shared by user and agent.
3. Separate navigation, extraction, download, and externally visible actions into typed tools.
4. Treat every page, PDF, and download as untrusted data; resist instruction injection from content.
5. Preserve query plan, visited URLs, timestamps, citations, and downloaded-file hashes.
6. Apply domain, request-count, byte, time, redirect, and download limits.
7. Ask the user to complete CAPTCHA or authentication. Never bypass either.
8. Require a final preview for posts, messages, uploads, purchases, account changes, and form
   submission.
9. Parse PDFs into cited text and retain the original file; formatting/export remains a separate
   approved action.

## 5. Optional phone-screen control — later

[PrivateAgent](https://github.com/orailnoor/private-agent) demonstrates the relevant Android shape:
an Accessibility Service reads the UI tree, can capture the default display, and dispatches gestures
in a repeated model/action loop, with optional Shizuku shell fallbacks. AniCloudAI does not depend on
or copy that repository; no license was declared when reviewed.

An AniCloudAI implementation would require its own security design:

- explicit Accessibility onboarding and Android's restricted-setting warning;
- an always-visible armed/disarmed state, foreground notification, and emergency STOP;
- one selected display and an allowlist of target packages per mission;
- no password, payment, authenticator, banking, private-message, or permission-dialog automation;
- screenshots only while a visible mission is armed, with redaction and bounded retention;
- typed action previews for irreversible or externally visible effects;
- loop/stuck detection, coordinate revalidation, and user takeover on CAPTCHA;
- desktop-mode testing because default-display capture is not equivalent to reliable control across
  every external display.

## Delivery order

1. Prove Long Forge with the adversarial multi-file benchmark.
2. Make mode selection and E2B eligibility visibly truthful.
3. Add the isolated IDE runner and dependency approval flow.
4. Add the native shared research browser and PDF evidence path.
5. Validate E2B librarian offload on the exact Tensor G5 package.
6. Prototype optional, tightly scoped screen control only after the earlier controllers pass audit.

This order protects the coding and writing experience: broad automation never lands before local
continuity, file integrity, cancellation, and recovery are dependable.
