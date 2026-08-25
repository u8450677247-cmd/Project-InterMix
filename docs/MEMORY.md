# Memory, continuity, and privacy

Project Intermix creates the experience of long continuity through durable storage and selective retrieval. It does not enlarge the model's physical context beyond the configured KV ceiling and does not silently retain every old token in every prompt.

## Memory planes

| Plane | Purpose | Retrieval rule |
|---|---|---|
| Session messages | Reopen recent conversations and inspect history | Active-session recency plus explicit session selection |
| Session checkpoint | Preserve summary, task, decisions, open loops, tone, and project | Included for the active session within budget |
| Durable memories | Preferences and facts with revision history | FTS5 relevance, salience, confidence, expiry, and token budget |
| Typed events | Explicit work, project, goal, preference, commitment, and opt-in wellbeing facts | Query-gated by domain |
| Mission ledger | Verified workspace actions, failures, tests, hashes, and completion | Active mission or prior-work recall intent |
| Web evidence | Cached current evidence and fact watches | Freshness, relevance, expiry, and source policy |

## Why SQLite FTS5

- transactional writes survive restart better than appended free-form text;
- schema fields distinguish fact, preference, task, and sensitive event;
- FTS5 supplies fast lexical recall without loading a second neural model;
- conflicts preserve the previous revision instead of overwriting history;
- provenance links memories to source messages and web evidence; and
- every memory can be inspected, expired, superseded, forgotten, or purged.

The database remains local at `~/project-intermix/memory/sovereign.db` by default and is ignored by source control.

## Hidden proposal protocol

The model can append a bounded hidden JSON memory proposal. A streaming filter removes the protocol even when markers cross token-chunk boundaries. Controller validation then checks operation type, field limits, source message, explicitness, sensitivity, and provenance before applying anything.

The model cannot make an inferred health diagnosis durable merely by formatting it as memory.

## Sensitive wellbeing memory

Fresh public installs use:

```text
sensitive_memory_mode = off
```

Opt-in command:

```text
/memory sensitive on
```

Opted-in behavior is still restricted:

- only explicit first-person self-reports are eligible;
- inferred diagnoses and implicit emotional profiling are rejected;
- entries are marked sensitive and receive bounded retention;
- retrieval requires a matching wellbeing query;
- controller reports omit private text by default; and
- the user can inspect, forget, purge, disable, or change retention.

Relevant commands:

```text
/memory sensitive status
/memory sensitive on
/memory sensitive off
/memory sensitive retention 90
/memory audit
/memory timeline wellbeing
/memory event forget <id>
/memory event purge <id>
```

This subsystem is a personal continuity tool, not clinical assessment. Do not use it as a medical record or emergency service.

## Work and project trajectory

Deterministic patterns can capture explicit work context, project decisions, goals, commitments, and stated preferences without spending a second model inference. Mission completion is stored separately from conversational claims: a task is complete only when its acceptance criteria and required tests have verified events.

This prevents a compressed conversation summary from becoming the sole source of truth about what code was changed.

## Context assembly

Each turn independently selects:

1. immutable identity and safety contracts;
2. active session checkpoint;
3. bounded recent messages;
4. relevant durable memories;
5. matching typed events;
6. verified mission state when relevant;
7. live web evidence when required; and
8. the current request.

Older dialogue is compacted first. Mandatory current instructions are preserved. The prompt report records estimated tokens and selected block sizes without exposing hidden model state.

## Retention and deletion

- Normal conversations remain until the user deletes or exports their local data.
- Sensitive event retention is configurable and bounded.
- Web cache rows expire according to volatility.
- Audio retention is a separate optional subsystem.
- Workspace checkpoints and deletion reviews live beneath the fixed workspace.

Generated workspace deletions require human review. Memory forget/purge commands are explicit user actions and should be backed up first when the information matters.

## Backup and migration

The installer uses SQLite's backup API before changing a live installation. Legacy TXT and v1 SQLite migration is dry-run by default and idempotent. It never imports automatically.

Review:

```bash
cd ~/project-intermix/current/engine
python memory_migrate.py --dry-run
```

Only after reading the candidate and duplicate counts should a user deliberately run `--apply`.

## Public issue hygiene

Never attach:

- `sovereign.db` or another SQLite database;
- `convo.txt`, exports, raw transcripts, or prompt reports containing text;
- `identity.txt`;
- provider files or environment dumps;
- a complete workspace or `.intermix` control directory; or
- screenshots with private conversation content.

Use synthetic reproduction data and `intermix-doctor --json` instead.
