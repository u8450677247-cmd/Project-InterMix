# Android Interaction Profile Matrix

Status: implemented native controller contract for `0.7.0-interaction-matrix`.

The Interaction Profile Matrix lets AniCloudAI refine how Sovereign Core
communicates without allowing generated text to rewrite identity, permissions,
truth rules, or tool policy. It is a presentation and context-selection layer
inside the app-private Memory Matrix.

## What is learned

The controller stores six bounded values from `0.0` to `1.0`:

| Trait | Effect | Factory value |
|---|---|---:|
| Warmth | Conversational warmth without flattery | 0.78 |
| Directness | How quickly the answer leads with a conclusion | 0.72 |
| Detail | Brief versus thorough presentation | 0.64 |
| Emoji | Frequency of purposeful visual anchors | 0.42 |
| Initiative | How readily the Core anticipates a useful next step | 0.68 |
| Context precision | Resistance to incidental recalled/project context | 0.76 |

Automatic adaptation accepts only explicit communication preferences. A model
proposal must copy a verbatim evidence fragment from the current user message,
name an allowlisted trait, and request a small increase or decrease. The Android
controller checks the quote, rejects credential-shaped and sensitive evidence,
clamps the change, records a full before/after revision, and exposes one-step
undo. Deterministic phrases such as “keep it concise” are recognized even when
the model omits the proposal marker.

The profile never grants tool authority, broadens a Storage Access Framework
tree, changes model fingerprints, selects a network provider, or bypasses an
Agents approval. Memory and model output remain untrusted inputs to controller
policy.

## Context gate

Every generated turn receives one inspectable scope:

| Scope | Included context | Typical trigger |
|---|---|---|
| General | Directly matching durable memory only | A self-contained question |
| Continuity | Bounded recent turns and relevant memory | “Why did you say that?” |
| Project | Bounded recall plus the connected workspace controller | An explicit file, path, repository, or technical action |

General turns do not inherit the open file, recent project transcript, or
fallback memories. This prevents questions such as “What is nested quoting?”
from being answered as if they referred to the last selected source file.
Continuity turns can resolve references without opening workspace authority.
Project turns include the workspace only after deterministic evidence meets the
threshold. `/why` displays the last route, score, threshold, reason, and whether
workspace context was included.

This is retrieval discipline, not an “infinite context” claim. SQLite may retain
far more history than a model can read, while each E2B or E4B handoff stays
ranked, bounded, and auditable. A future E2B librarian can run only when normal
FTS retrieval is insufficient, create a compact checkpoint, unload, and then
hand that checkpoint to E4B. Only one large model remains resident at a time.

## Commands

| Command | Result |
|---|---|
| `/profile` | Shows all values, automatic/manual state, revision, and last evidence |
| `/why` | Explains the last deterministic route and context selection |
| `/adapt on` / `/adapt off` | Enables or pauses automatic explicit-preference learning |
| `/adapt reset` | Restores the reviewed factory profile as a reversible revision |
| `/adapt <trait> <value>` | Sets one trait using `0–1` or `0–100%` notation |
| `/undo-adaptation` | Reverts the newest non-undone profile change |

Examples:

```text
/adapt detail 80%
/adapt emoji 0.25
/adapt context_precision 90%
/undo-adaptation
```

The Matrix destination renders the current trait meters and most recent context
decision. System Lens exposes the profile revision and context scope so
adaptation is visible rather than covert.

## Long-running agent boundary

This layer is suitable backing for resumable plans, project journals, safe
interruptions, and later automatic documentation. It is not itself a shell or
agent executor. Native command execution and dependency installation require a
separate sandboxed provider with visible commands, allowlisted roots and network
destinations, resource ceilings, checkpoints, and approval policy. Those gates
must remain deterministic even when an E2B librarian or E4B planner recommends
an action.
