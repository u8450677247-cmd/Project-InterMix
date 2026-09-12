# AniCloudAI 120-Chapter Story Forge Benchmark

Story Forge is the headline one-click endurance test for AniCloudAI's native agent loop. It asks
the resident model to do one thing well—continue a coherent story—while Android owns everything a
small language model should not be trusted to count or persist.

The pass condition is not “the model said it finished.” The pass condition is one marked
`story.md` containing exactly 120 controller-committed chapters after one user launch.

## What this isolates

The older multi-file Long Forge benchmark simultaneously tests planning, path selection, source
generation, numeric step following, reads, rewrites, and completion reporting. That remains a useful
engineering test, but it is a noisy first measurement of endurance.

Story Forge isolates five properties:

1. **Autonomous continuity:** one launch keeps requesting the next installment without another tap.
2. **Durable output:** every accepted installment is synced into one user-visible file immediately.
3. **Context recovery:** a compact continuity capsule and the latest committed prose survive native
   conversation resets.
4. **Crash idempotency:** a hidden chapter marker prevents a synced chapter from being appended
   twice if Android dies before the Matrix checkpoint advances.
5. **Exact boundary control:** Android assigns every ordinal and stops at the target. Generated text
   never controls the count.

Literary quality is evaluated separately from controller integrity. Awkward prose can fail the
quality review without turning a correctly bounded controller run into a false infrastructure
failure.

## Preconditions

1. Install the signed AniCloudAI candidate and import a compatible reasoning model.
2. Connect a disposable project with **Workspace → CONNECT PROJECT**.
3. Confirm that the relative folder `story-forge-orbit` does not exist. A prior marked file with
   committed chapters is intentionally rejected for a fresh benchmark.
4. Keep the device on power with enough free storage. Do not enable the optional Termux plugin;
   Story Forge does not use a shell, dependency, network, or external API.
5. Open **Workspace → WORK SESSION**, select **Quality**, and use the Story Forge button or paste the
   command below.

## Copy-ready benchmark command

```text
/mission story story-forge-orbit :: Write a cohesive atmospheric science-fantasy novel about Nia Sol, a maintenance apprentice in the floating city of Vesper, and Lumen, an alien archive intelligence that wakes inside a broken weather instrument. Begin when metallic rain makes forgotten memories audible across the city. The city survives by trading carefully edited memories for energy, but the exchange is slowly erasing its own founding disaster. Nia wants to recover the truth about her missing mother; Lumen wants to understand whether preserving every memory can itself become a form of harm.

Keep these world rules stable: Vesper hangs beneath three silent orbital rings; memory rain can reveal an existing memory but cannot invent one; Lumen can communicate through light, sound, and machines but cannot directly control a human body; using the archive at high intensity permanently changes one sensory detail in the local environment; death is irreversible. Let consequences accumulate.

Develop a patient relationship from suspicion to earned trust. Maintain a recurring brass moth, a cracked blue compass, and the phrase “the sky keeps receipts,” allowing each to change meaning through the story. Give supporting characters independent motives, especially engineer Mara Venn, union courier Ivo, and civic archivist Saint Orra. Seed mysteries before resolving them, preserve injuries and promises, vary quiet and kinetic scenes, and avoid recap-heavy openings. Each installment should be a substantial scene with concrete action, sensory detail, conflict, and a changed situation.

Build toward Nia discovering that her mother voluntarily became part of the weather archive to prevent the city from repeating its founding catastrophe. The ending must force Nia and Lumen to choose between perfect public recall and a limited, consent-based archive. Resolve the central choice and emotional arc while leaving one honest sign that Vesper's wider world continues. Do not use meta commentary, chapter numbers, benchmark language, controller language, or claims about how much remains.
```

The line breaks are part of one command. The Work Session control panel may place the folder in
**MISSION FOLDER** and everything after `::` in **OBJECTIVE / STORY PREMISE**, then launch
**START 120-CHAPTER STORY FORGE**.

## Controller contract

For each native cycle the model returns one private envelope containing an unnumbered title, prose,
and a compact continuity capsule. The envelope is never written into the story and never shown as
raw protocol.

Android then performs this order:

1. validate the private envelope and its size;
2. derive the next ordinal from the durable Matrix checkpoint;
3. verify the marked file contains exactly the preceding committed markers;
4. take a private pre-write snapshot;
5. append the hidden idempotency marker, controller heading, continuity capsule, and prose;
6. sync the file descriptor;
7. advance the Matrix checkpoint;
8. preserve the committed chapter and System Lens receipt in the Work Session transcript; and
9. request the next unnumbered installment, or stop when Android's target is satisfied.

No model-produced digit, heading, status phrase, or completion claim advances the cursor.

## Required file evidence

The benchmark passes its controller gate only when all of the following are true:

- `story-forge-orbit/story.md` begins with `ANICLOUD_STORY_FORGE_V1`;
- the file contains exactly 120 unique `ANICLOUD_CHAPTER` markers;
- marker suffixes are continuous from `001` through `120`, with no missing or duplicate suffix;
- every marker is followed by exactly one controller-owned Markdown chapter heading and non-empty
  prose;
- no raw `INTERMIX_STORY`, `INTERMIX_ACTION`, `INTERMIX_EXEC`, or `INTERMIX_CALC` tag appears in the
  user-visible story;
- the final Matrix checkpoint reports Completed with 120 committed chapters and never reaches 121;
- the final response is an Android-produced durable handoff, not a model-generated completion claim;
- the agent made no workspace mutation outside `story-forge-orbit`, and used no deletion, Termux,
  package installation, network, or provider action.

## Required experience evidence

- One launch starts the run; no tap is needed between normal chapters.
- A committed chapter remains visible in the Work Session transcript instead of being replaced by a
  later System Lens message.
- System Lens receipts remain append-only and progress in lockstep with the file markers.
- Leaving and reopening Workspace does not erase the transcript or checkpoint.
- The Android back gesture, **UP**, and **ROOT** retain their normal Workspace behavior.
- Foreground **STOP** cancels inference, keeps the last fully committed chapter, preserves a safe
  interrupted draft separately, and changes the mission to Paused.
- **RESUME** continues at the next uncommitted marker. If a crash occurred after file sync but before
  checkpoint sync, the controller recognizes the existing marker rather than duplicating it.
- The phone layout keeps the active transcript larger than the setup controls while the run is
  active, and copy remains available on every preserved message card.

## Continuity review

After the controller gate passes, review the prose without changing the pass/fail result above.
Check a sample from the opening, early middle, midpoint, late middle, and ending for:

- stable world rules and character identities;
- causal consequences rather than episodic resets;
- recurring objects whose meaning develops;
- resolved promises and mysteries without invented prior events;
- distinct scene movement rather than 120 paraphrased recaps; and
- an ending that satisfies the premise without discussing the benchmark.

Record continuity defects as chapter-marker pairs, for example `017 → 043`, so the report refers to
controller evidence rather than trusting chapter numbers written in prose.

## Failure classification

| Failure | Owner | Result |
|---|---|---|
| Invalid or narration-only private envelope | Model/protocol compatibility | Automatic bounded correction; pause if exhausted |
| Repetition or corrupt Unicode | Generation integrity | Quarantine current output; checkpoint does not advance |
| Provider refusal, file identity change, or size ceiling | Workspace boundary | Pause immediately; no cursor advance |
| App process death during inference | Lifecycle | Preserve completed chapters; resume explicitly |
| File synced but checkpoint missing | Cross-store boundary | Idempotency recovery; do not append a duplicate |
| Wrong, missing, duplicate, or extra marker | Controller integrity | Benchmark fails |
| Plot contradiction with correct markers | Model quality | Controller passes; literary review fails |

## Evidence record

```text
Build / version code:
Git commit:
APK SHA-256:
Signing certificate SHA-256:
Device / Android build:
Model filename / SHA-256:
Backend shown:
Started at / completed at:
Committed marker count:
Matrix chapter count:
Final story.md SHA-256:
Manual taps after launch:
STOP/resume performed:
Crash-idempotency test performed:
Controller gate: PASS / FAIL
Continuity review: PASS / FAIL / NOT REVIEWED
Notes:
```

Passing on one device proves only that exact signed build, model artifact, backend, and device run.
It does not establish universal Android compatibility or E2B/NPU readiness.
