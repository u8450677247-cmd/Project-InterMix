# LIBRARIAN-01 repository map

Captured: 2026-09-19

## Repository state before implementation

- Remote: `https://github.com/u8450677247-cmd/Project-InterMix.git`
- Integration base: `origin/feature/anicloud-release-origin-20260917`
- Base commit: `329eed769c441c6687078c9b6cb4c032aa80f882`
- Working branch: `feature/intermix-librarian-continuity-20260919`
- Default branch: `main` at `d76498bd67bd0243a2b63dac11dabf9dafa55b87`
- Initial worktree: clean

The release-origin branch is the current cumulative product line. It contains the
Android cockpit and all continuity, workspace, runtime, and release hardening that
landed after `main`; basing this work on `main` would discard those systems.

## Current component map

| Concern | Current path | LIBRARIAN-01 target |
| --- | --- | --- |
| Termux entry point | `bin/intermix` | Keep existing interactive behavior; add a separate Librarian control entry point. |
| Termux inference/controller | `engine/agent.py`, `engine/llm_controller.py`, `engine/resident_engine.py` | Cortex client remains optional and failure-isolated. |
| Termux memory | `engine/memory_store.py`, `engine/memory_protocol.py`, `engine/memory_migrate.py` | Add the Continuity Lattice to the same local SQLite database without replacing existing tables. |
| Retrieval/context | `engine/memory_retriever.py`, `engine/prompt_builder.py`, `engine/second_brain.py` | Add a bounded, typed context compiler with provenance IDs and no embedding dependency. |
| Native Android app | `android/app/src/main/java/dev/anicloud/sovereign/` | Preserve the current cockpit; a later authenticated client can consume the Librarian protocol. |
| Android SQLite | `android/app/src/main/java/dev/anicloud/sovereign/MemoryMatrixRepository.kt` | Remains app-private; do not import or network-mount its live database. Exchange typed events only. |
| Android background lifecycle | `InferenceForegroundService.kt`, `ReleaseUpdateWorker.kt` | Existing lifecycle stays intact; no broad Binder or telephony privileges are added. |
| Android workspace | `WorkspaceRepository.kt`, `TermuxExecutionBridge.kt` | Workspace actions remain controller-owned and approval-gated. |
| Native/C++ | No project-owned native daemon is present | Implement the first reliable service in existing Python conventions; retain a language-neutral protocol. |
| Release publisher | `tools/publish_android_update.py`, `tools/verify_android_release_trust.py` | NAS remains a static verified artifact origin, never a continuity authority. |
| Device update helper | `tools/termux_dogfood_update.sh` | Preserve protected signing and independent device verification. |
| CI | `.github/workflows/tests.yml`, `.github/workflows/android-foundation.yml`, `.github/workflows/release.yml` | Add deterministic Librarian tests to the existing Python matrix; do not weaken Android/signing gates. |
| Existing tests | `tests/`, `android/app/src/test/` | Extend failure and end-to-end coverage while retaining all baseline suites. |
| Documentation | `docs/ARCHITECTURE.md`, `docs/MEMORY.md`, `docs/ANDROID_*.md` | Add an implementation/runbook document and milestone progress ledger. |

## Existing trust and signing assumptions

- Android dogfood signing is isolated in a protected CI environment.
- Release trust anchors are injected from protected variables and validated before a
  non-debuggable release candidate can reach signing.
- The release origin serves signed manifests and APK bytes; clients independently
  verify manifest signature, APK digest, package/version, and certificate pin.
- No signing key belongs on LIBRARIAN-01 or the DS215j.

## Database integration decision

The initial Librarian service will use the configured `MemoryStore` database path
(`sovereign.db` by default) and install additive Continuity Lattice tables. Existing
sessions, messages, memories, revisions, and FTS tables remain untouched. A
deterministic, idempotent bridge can project existing records into globally identified
events/atoms with provenance.

The Android Matrix remains a separate local database. Cross-device continuity uses
typed event envelopes, high-water marks, snapshots, and replay rather than sharing a
SQLite file.

## First executable milestone

1. Initialize/migrate the additive lattice.
2. Register `LIBRARIAN-01`, `CORTEX-PRIMARY`, and the archive identity.
3. Ingest and deduplicate one immutable Cortex event.
4. Validate and commit one evidence-backed memory delta.
5. Retrieve it in a bounded context packet.
6. Create and hash a consistent SQLite backup.
7. replicate through a filesystem NAS adapter with atomic verification.
8. Restore a copy, replay the event, and prove no duplicate state appears.
