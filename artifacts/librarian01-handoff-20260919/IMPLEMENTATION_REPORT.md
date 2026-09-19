# LIBRARIAN-01 implementation report

Date: 2026-09-19
Implementation commit: `194a63b5748b2a0ae9b9e0c7982a676100379b1b`
Base commit: `329eed769c441c6687078c9b6cb4c032aa80f882`

## Outcome

Project Intermix now contains an executable ordinary-service continuity milestone for
LIBRARIAN-01. The Redmi is the durable authority, the Pixel/Cortex is a replaceable
compute client, and the DS215j is a verified immutable archive. The implementation
completes the requested software loop:

`event -> deduplication -> evidence-backed memory -> bounded context -> snapshot -> verified archive -> restore -> duplicate replay`

The loop is covered by deterministic tests and a 1,000-event synthetic benchmark.
Physical deployment, Android compilation, device lifecycle observations and real NAS
failure flights remain deliberately outside this software-only change.

## Delivered systems

| Area | Delivered behavior |
| --- | --- |
| Authority store | Additive Continuity Lattice v0.3 in SQLite; existing memory tables remain intact. |
| Event ingestion | Canonical bounded envelopes, authority receive time, immutable rows, global/sequence conflict detection and exact replay deduplication. |
| Derived memory | Deterministically validated atoms, evidence, edges, goals and states; byte-changing identity replay fails closed. |
| Contradictions | Competing claims remain visible and open friction; only provenance-linked resolution changes the active state. |
| Retrieval | Bounded FTS/state/goal/friction/recent-event context packets with evidence IDs and no embedding dependency. |
| Service | Bounded authenticated JSON/HTTP API, node-role binding, loopback default, overlay interlock, request limits and replay-safe mutation IDs. |
| Cortex | Failure-tolerant client and local SQLite outbox; existing controller behavior is unchanged while the feature flag is absent. |
| Workers | Disposable, resource-gated jobs with leases, backoff, terminal friction and no authority-process dependency. |
| Snapshots | SQLite Backup API, canonical manifests, hashes, atomic verified archive copies, retry queue and restore to a new path only. |
| Health | Explicit DB, queues, resources, network, snapshot, NAS, Cortex and worker state vocabulary. |
| Android boundary | Unwired `ConnectivityManager` sentinel using only normal network-state permission and injected endpoint probes. |
| Deployment | Reversible Termux runit installer, disabled by default, mode-600 token and bounded log rotation. |

## Non-negotiable boundaries preserved

- The battery-installed Redmi remains the continuity authority.
- No live SQLite database, WAL or SHM file is placed on the DS215j.
- The Pixel is not assumed to own or expose a SIM; LTE is a route observation, not a
  telephony-control feature.
- Raw committed events are immutable.
- Model prose never commits directly to authority tables.
- No chain-of-thought is requested, stored or transported.
- Embeddings are optional and cannot block lexical/state/evidence retrieval.
- No bootloader, partition, ROM, signing key, credential or physical-device action was
  performed.

## Main entry points

- `bin/intermix-librarian`
- `engine/librarian_cli.py`
- `engine/librarian/schema.sql`
- `engine/librarian/service.py`
- `engine/librarian/client.py`
- `engine/librarian/snapshots.py`
- `tools/install_librarian_service.sh`
- `tools/benchmark_librarian.py`
- `docs/LIBRARIAN01.md`

## Deferred evidence

The following require controlled hardware access and are not claimed by this report:

- Redmi RAM, temperature, battery, reboot and Termux lifecycle measurements.
- Pixel-to-Redmi Wi-Fi/LAN and encrypted WAN-overlay behavior.
- Real DS215j mount loss, partial-copy cleanup and reconnection behavior.
- Kotlin compilation in the pinned Android workflow.
- Any Gemma E2B/E4B resident-model installation or performance assertion.
