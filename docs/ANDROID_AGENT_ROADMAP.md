# AniCloudAI native agent roadmap

Status: implementation order and acceptance contract for the private dogfood APK.

AniCloudAI becomes an on-device agent by adding narrow, inspectable controllers around the
resident models. The language model proposes the next action; deterministic Android code owns
permissions, scope, execution, audit history, cancellation, and recovery. Memory improves
continuity but never grants authority.

## 1. Long Forge workspace sessions — current build

`0.8.12-release-origin` separates ordinary Chat from long-running project work,
adds non-destructive fresh/reopen conversation controls, and turns one explicit start tap into
a bounded filesystem grant. A work session has a
complete immutable objective, one Unicode-safe workspace root, a selected model posture, a durable Matrix
checkpoint, and a visible foreground STOP path.

- Android compiles explicit work items once into a durable Mission Manifest. The manifest owns task
  identity, order, source instructions, current task, artifact ids, attempts, and exact completion;
  the model never owns or reconstructs the task count.
- The 120-step grant now means 120 verified **logical agent checkpoints**, not 120 filesystem calls.
  Mechanical work is counted separately and bounded at 1,440 tool operations. Inference cycles,
  failed tool attempts, automatic recoveries, written bytes, and completed manifest tasks also have
  separate durable counters.
- A read, list, create, or write never advances logical progress by itself. Failed tool attempts are
  audited and consume tool-operation capacity, but never consume a completed logical step.
- One model response may request a typed transaction of two through eight workspace operations.
  Android prevalidates the whole bundle, executes and audits every operation separately, and accepts
  an optional logical checkpoint only after every required operation succeeds.
- The grant covers at most 1 MiB of generated write content inside the exact mission root.
- Android creates or verifies the mission root before inference. Within that root, create,
  write, and directory actions run without per-file approval prompts; execution, dependency,
  network, and out-of-scope requests remain separately gated.
- Replacements retain a private pre-write snapshot. Model-generated delete, code execution,
  package installation, network access, and paths outside the mission root are unavailable.
  Human IDE removal is a separate confirmed move into recoverable project-local trash.
- The controller recreates native conversation state and rebuilds a bounded, lane-specific Matrix
  pack before every inference. It injects global constraints, the manifest summary, the exact current
  task source, newest guidance, and the last verified checkpoint instead of arbitrary prefixes of the
  original request or accumulated hidden model state. The complete original objective remains durable
  provenance and is integrity-bound to the manifest by SHA-256.
- Android derives deterministic ASCII-safe storage names for semantic deliverables while preserving
  the original Unicode display label. Existing artifacts use exact stable `WA-…` identities. User
  paths accept normalized Unicode, spaces, and apostrophes while still rejecting controls, absolute
  paths, traversal, oversized segments, and any access outside the granted SAF tree.
- A controller-owned Recovery Router handles malformed envelopes, operation-kind disagreement,
  stale/missing targets, repeated reparable requests, benign filesystem races, and test/compiler
  failure evidence without a Resume click. It preserves the last logical checkpoint, records the
  failure, applies deterministic repair where possible, otherwise returns a compact recovery packet,
  and stops after a small bounded set of attempts.
- Human interruption remains reserved for authority expansion, consequential semantic ambiguity,
  security/integrity boundaries, or recovery exhaustion. Process restart resumes a mission whose
  status was Running from its durable manifest cursor; a completed approved Termux test/build also
  returns automatically to the mission, whether the command passed or failed.
- Guidance is appended to the checkpoint. It does not replace the original objective.
- Guidance entered while generation is active is queued durably and injected at the next safe
  controller boundary instead of discarding partial work or replacing the objective.
- Unprefixed action paths are resolved relative to the exact mission root. A narration-only model
  response receives up to four automatic controller corrections before the mission pauses.
- Start and approval controls expose accepted, waiting, executing, verified, and failed states.
  File mutations require non-empty complete content and count as successful only after Android
  reads the exact bytes back; an unverifiable replacement attempts to restore its snapshot.
- A repeated mission-root alias—including dash/underscore spelling drift—is collapsed once before
  scope validation so it cannot create `root/root_alias/file` recovery loops.
- If the model proposes `write_file` for a target Android has verified does not exist, the
  controller transparently reconciles that proposal to `create_file`. It never performs the
  inverse conversion, so an accidental create cannot overwrite an existing file.
- Project-authored state files remain useful deliverables, but they are never the canonical mission
  cursor. The Matrix manifest and controller evidence ledger remain authoritative across restart.

Primary endurance acceptance uses the
[`120-chapter Story Forge benchmark`](ANICLOUDAI_120_CHAPTER_STORY_FORGE.md). Android owns every
ordinal, single-file append, idempotency marker, checkpoint, and exact stop while the model writes
only the next unnumbered scene. The reviewed folder, full premise, and Quality posture can be
filled from the Work Session preset without launching; the user still starts the run explicitly.
Restored Chat and Work Session transcripts initially reveal their true tail, expose a visible
centered `↓` return control, and stop auto-following after deliberate upward reader motion. The separate
[`120-operation Long Forge fixture`](ANICLOUDAI_120_ACTION_FORGE_BENCHMARK.md) remains a legacy
low-level audit/endurance case; it is no longer the definition of 120 logical steps. Closing or
losing the process must preserve the cursor and automatically continue a previously Running mission
from verified state once its reviewed model and workspace authority are available.

## 2. Native IDE execution — first candidate

Workspace becomes a usable IDE only after execution is a separate controller rather than a shell
string emitted by the model.

Implemented in `0.8.3-termux-execution`:

1. Typed `run`, `test`, `build`, `inspect_environment`, and `install_dependencies` requests.
2. A user-enabled Termux RUN_COMMAND bridge with Android permission, a configured project root,
   and a project-relative working directory per action.
3. Exact Agents previews for command, packages, network declaration, reason, and timeout.
4. One-at-a-time background dispatch with a 5–1,800 second wall-clock limit, STOP, and a durable
   exit record. Returned stdout/stderr are sanitized, marked untrusted, and bounded to 32 KiB each.
5. A controller prompt that requires manifest/lockfile inspection before a dependency proposal;
   dependency actions must name packages, declare network use, and receive separate approval.
6. Fail-closed checks against destructive/privileged/Android-control commands, parent traversal,
   and absolute Android data/storage paths. Long Forge write authority never grants execution.

The project working directory is a review boundary, not an OS sandbox. An approved script runs
with the Termux app's existing reach, so script provenance and exact-command review remain part of
the acceptance contract.

Remaining execution gates:

1. Stream bounded stdout/stderr into a visible foreground terminal instead of returning only the
   final PendingIntent result.
2. Add deterministic language/lockfile detection and project-local environment policy rather than
   relying only on the controller prompt and exact human review.
3. Re-open changed files in the syntax-colored editor and attach structured test evidence to the
   mission checkpoint.

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

Current parity checkpoint:

- Android now hard-reserves the newest roughly 1K tokens, retrieves FTS5 memories
  and archived turns, injects an active session checkpoint in ordinary chat, and
  deterministically captures verbatim explicit goals, preferences, project facts,
  decisions, and unresolved loops.
- Recent controller-verified project events can return to a relevant project
  question without relying on an assistant's narration.
- Recalled session, archive, and workspace material is explicitly untrusted data;
  only typed Android controllers can grant tools or assert execution.
- Native parity is still blocked on the incremental project manifest, typed event
  domains/retention UI, grounded freshness and citations, stronger verified-work
  completion rules, and an on-device E2B librarian flight.
- System now includes a Keystore-backed write-only Provider Key Drop. It exposes
  configured/empty state without a secret read path and keeps native grounding
  visibly off. The Termux `intermix-flight` command supplies daily live-authority
  health plus a disposable full continuity loop; exact-device E2B offload and a
  reviewed native outbound-data client remain separate gates.

## 4. Native research and shared browser

Research should live inside a dedicated work session, not gain unrestricted control over the user's
normal browser.

1. Keep research/provider traffic separate from the updater's narrow `INTERNET` lane and explain
   every new outbound-data purpose in context.
   Credential storage alone never activates provider traffic.
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
3. Prove the Termux IDE runner and dependency approval flow on-device, then add live result streaming.
4. Add the native shared research browser and PDF evidence path.
5. Validate E2B librarian offload on the exact Tensor G5 package.
6. Prototype optional, tightly scoped screen control only after the earlier controllers pass audit.

This order protects the coding and writing experience: broad automation never lands before local
continuity, file integrity, cancellation, and recovery are dependable.
