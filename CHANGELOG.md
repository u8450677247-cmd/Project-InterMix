# Changelog

All notable public changes are documented here. Project Intermix follows
Semantic Versioning once the public API stabilizes; pre-1.0 and alpha contracts
may still change.

## [Unreleased]

### Added

- Optional E2B Memory Librarian with deterministic E2B/E4B routing, bounded
  handoffs, and one-resident-model-at-a-time switching for constrained Android.
- Reversible explicit communication preferences with an inspectable persona
  changelog and one-step undo.
- A fail-closed Sanctuary capability contract that refuses to describe Termux
  storage as encrypted and reserves biometric vault activation for the APK.
- Installer and diagnostics support for an optional librarian model and its
  independent physical context ceiling.
- Deterministic adaptive grounding with at most three anchored query wordings,
  a single parallel follow-up round, an eight-request hard ceiling, and
  `/web last plan` execution diagnostics.
- Evidence-backed “Further path” prompts for non-exact questions about
  implementation, design rationale, trade-offs, and limitations.
- A foreground STOP control, cooperative resident/PTY cancellation, hard native
  output ceilings, and an automatic stream-corruption watchdog for repeated
  words, duplicated blocks, leaked model control tokens, and legacy visible
  memory-delta markers, and invalid stream characters.
- Incomplete-turn quarantine: visible partial output remains on screen for
  inspection but is excluded from assistant history, memory proposals, and voice.
- An auto-growing chat composer with multiline paste, preserved paragraph
  spacing, `Enter` to send, `Shift+Enter` or `Ctrl+Enter` for a newline,
  seven-row capping, internal scrolling, and post-send collapse.
- The Sovereign Glass native Android design contract, anchoring responsive
  surfaces, state semantics, visual tokens, accessibility, privacy, motion, and
  thermal fallbacks before APK implementation.

### Preserved

- Python remains the authority for memory validation, model routing, grounding,
  numeric integrity, workspace permissions, and persistence.
- E4B remains the automatic fallback when the optional E2B asset is absent or
  its resident backend becomes unavailable.

## [1.4.1-alpha.1] - 2026-08-25

### Added

- First sanitized public-source candidate derived from the private Trust +
  Speed reference build.
- Runtime configuration for user/Core labels, model, workspace, paths, and
  physical context ceiling.
- Fresh/upgrade Termux installer with capability checks, atomic versioned
  activation, rollback snapshot, and non-mutating migration report.
- Share-safe device diagnostics and strict public-release audit.
- GitHub CI, community templates, compatibility evidence policy, and native
  Android roadmap.

### Preserved

- SQLite FTS5 virtual continuity, typed memory, mission ledger, and session
  restoration.
- Resident LiteRT-LM engine with resource-pressure guards.
- Fail-closed web grounding, exact-claim contracts, and numeric repair.
- Bounded ordered workspace actions with review-only deletion.
- Cyan/violet Textual cockpit and optional Resonance bridge contract.

### Changed for public safety

- Sensitive wellbeing capture defaults to off on fresh installs.
- Personal labels and device-specific paths are installer configuration.
- Models, databases, provider secrets, caches, shaders, audio, workspaces, and
  transcripts are excluded from the source release.
- Compatibility language separates reference-verified, runtime-candidate, and
  community-experimental devices.

### Known limitations

- End-to-end verification currently covers one Pixel 10 Pro reference device.
- Web quality depends on query relevance, provider availability, and credentials.
- First cold GPU initialization can be substantially slower than a warm turn.
- Phone-width mode cannot expose every desktop cockpit panel simultaneously.
- Resonance setup is optional and not yet included as a public installer.

[1.4.1-alpha.1]: https://github.com/u8450677247-cmd/Project-InterMix/releases/tag/v1.4.1-alpha.1
