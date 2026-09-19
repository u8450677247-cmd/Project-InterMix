# LIBRARIAN-01 implementation progress

## 2026-09-19 — repository reconnaissance

Completed:

- Verified every file in the supplied handoff archive against its internal SHA-256 manifest.
- Located the public Project Intermix repository and selected the latest cumulative
  release-origin branch as the integration base.
- Recorded the actual Termux, Android, SQLite, release, CI, and test paths in
  `LIBRARIAN01_REPO_MAP.md`.
- Created local branch `feature/intermix-librarian-continuity-20260919` from
  `329eed769c441c6687078c9b6cb4c032aa80f882`.

Baseline tests:

- `python3 -m unittest discover -s tests -v`: 142 run, 142 passed, 5 skipped.
- Android source-contract tests are included in that count.
- Gradle/Kotlin execution is pending because this workspace has neither a `gradle`
  executable nor a repository `gradlew` launcher. CI pins Gradle 9.4.1 and remains the
  authoritative Android build environment.

Decisions:

- Use additive Python/SQLite implementation for the ordinary-service milestone.
- Keep the Android and Termux live databases local to their devices.
- Keep embeddings optional and all model-generated mutations behind deterministic validation.
- Do not touch signing, physical devices, ROM partitions, bootloaders, or credentials.

Next:

- Implement schema migration, typed ingestion, provenance/friction, bounded retrieval,
  snapshots/NAS queue, health, workers, and a Cortex client.

## 2026-09-19 — P0 through P4: authoritative continuity core

Completed:

- Added an additive v0.3 Continuity Lattice over the existing `sovereign.db`.
- Added transactional v0.1/v0.2 upgrades and a CLI backup gate for first migration.
- Preserved existing `MemoryStore` tables and provided an idempotent legacy projection.
- Implemented bounded canonical event envelopes, authority-owned receive time,
  immutable committed rows, sequence/global-ID conflicts, and exactly-once replay.
- Implemented transactionally validated atoms, evidence, edges, friction, state and
  goals. Derived identity replays reject changed bytes.
- Implemented unresolved-state preservation and explicit provenance-linked resolution.
- Implemented FTS/state/goals/friction/recent-event context packets with conservative
  token bounds and no embedding dependency.

Targeted verification:

- 100 identical event deliveries produce one commit and 99 duplicates.
- A malformed delta rolls back its tentative atom.
- Conflicting state preserves both atoms, opens friction, and changes only through a
  matching resolution.
- The supplied v0.1 plus v0.2 SQL artifacts were migrated in a temporary database,
  followed by event, memory, snapshot and integrity probes.

## 2026-09-19 — P5 through P8: service, archive, workers and Cortex

Completed:

- Added the bounded JSON/HTTP service and CLI with bearer authentication, node binding,
  loopback default, encrypted-overlay interlock, 16-request concurrency bound, body/path
  limits and read timeout.
- Added replay-safe events, jobs/results, heartbeats and snapshot requests. A reused
  identity with changed bytes fails closed.
- Added consistent SQLite Backup API snapshots, canonical manifests, SHA-256 verification,
  offline replication queue, atomic filesystem/NAS copy and restore-to-new-path.
- Added resource-gated workers for FTS, integrity, context checkpoints, duplicate and
  episode preparation, friction-resolution preparation, plus an explicit embedding-unavailable
  result. Synthetic worker OOM retries with backoff and terminal friction without killing the DB.
- Added a failure-tolerant Cortex client with an SQLite event outbox and job/heartbeat/
  snapshot methods.
- Wired the established Termux controller behind `INTERMIX_LIBRARIAN_URL`; disabled mode
  preserves current behavior and network failure degrades to local continuity.
- Added a runit installer that is down by default, requires `termux-services`, creates a
  mode-600 token, rotates bounded logs, and refuses ordinary non-loopback binds.

Targeted verification:

- Authenticated HTTP loop, offline outbox flush, origin spoof rejection and node-role denial.
- Idempotent heartbeat, job enqueue/result and snapshot request; changed replays rejected.
- Snapshot corruption/digest rejection, NAS failure queue, verified retry, restore and
  duplicate replay.
- Controller bridge user event → context → accepted local memory → remote atom/evidence →
  assistant event.

## 2026-09-19 — P9 and P10: explicit health and network boundary

Completed:

- Added DB/schema, uptime, queue, event, snapshot/NAS, worker-failure, RAM, storage,
  battery, thermal and network health data with the complete named state vocabulary.
- Added fail-closed resource leasing for minimum RAM, maximum temperature and charging.
- Added portable unknown/static sentinels and Linux/Android read-only resource sensors.
- Added an unwired native Android `ConnectivityManager` sentinel with an injected cached
  endpoint-probe boundary and only the normal `ACCESS_NETWORK_STATE` permission. It does
  not use telephony, SSID scanning, location or Wi-Fi-manager APIs.

Current blocker boundary:

- No physical Redmi, Pixel, DS215j mount or credentials are present, so on-device RAM,
  thermal, battery, lifecycle, LTE and real NAS evidence remains a human/device flight.
- Gradle and a repository `gradlew` launcher are absent locally. The Android source-contract
  suite runs here; the pinned GitHub Android workflow must compile the Kotlin skeleton.

Next:

- Run the complete regression suite, release audit, full synthetic benchmark and release
  builder; then publish the branch/PR and produce the required handoff directory.

## 2026-09-19 — validation gate complete

Completed:

- Full regression suite: 166 tests run, all passed, 5 environment-dependent tests skipped.
- Public-release audit: passed with no private state, credential signatures, model assets,
  generated caches or oversized files present.
- Deterministic source release: built successfully as version `1.4.1-alpha.1` with 182
  audited files.
- Live-service smoke: additive initialization, integrity health, authenticated loopback
  request, clean shutdown and mode-600 bearer token all passed.
- Synthetic closed loop: 1,000 unique events, 100 evidence-backed atoms, 100 bounded
  context queries, 1,000 duplicate deliveries, consistent snapshot, verified archive copy,
  restore and exact replay all passed.
- Benchmark observed 1,086 event ingests/second, 141 context queries/second and 19,900 KiB
  maximum resident memory in this workspace. These figures are software evidence only,
  not Redmi Note 9 Pro device measurements.

Remaining hardware flight:

- Compile the Android sentinel in the pinned GitHub Android workflow.
- Deploy the ordinary service to the battery-installed Redmi through the documented,
  reversible Termux service procedure.
- Exercise Pixel LAN, Wi-Fi and WAN-overlay routes without assuming SIM ownership.
- Exercise DS215j outage/recovery and verify that it receives only immutable snapshots and
  release artifacts, never a live SQLite database or WAL files.
