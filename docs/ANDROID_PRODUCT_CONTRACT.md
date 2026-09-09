# AniCloudAI native Android product contract

Status: private-dogfood contract, version 0.1. The implementation may improve,
but changes to the privacy, truth, recovery, or destructive-action rules require
an explicit contract revision.

## Identity and delivery posture

- **Product name:** AniCloudAI.
- **Resident intelligence:** Sovereign Core.
- **Interface system:** Sovereign Glass, with Sovereign Obsidian as the default
  appearance. System and Light appearances remain available.
- **Reference device:** Pixel 10 Pro. Other Android devices are experimental
  until supported by reproducible reports.
- **Current phase:** private dogfood. Public timing follows evidence rather than
  a calendar promise.
- **First public channel:** a signed GitHub Release with checksums, reproducible
  build instructions, and no bundled private state or model weights.
- The prototype application ID is intentionally provisional. Naming, package
  ownership, signing identity, and trademark review are release gates.
- The repository remains under its existing license while ownership and
  dependency obligations are reviewed. The stated future licensing preference
  is source publication for redistributed and network-hosted derivatives; this
  document does not silently relicense existing work.

## First-open and daily home

The app opens to a dashboard immediately. A dismissible setup checklist guides
the first run without holding the interface behind a tutorial.

The first viewport prioritizes:

1. actual model, available-memory, and thermal state;
2. a resume-last-conversation card;
3. voice conversation;
4. active or paused agents;
5. recent conversations;
6. model downloads and update availability; and
7. answer mode, appearance, and this-display layout controls.

An unavailable signal is labelled unavailable. The dashboard must never derive
telemetry from a marketing device name or substitute `MemFree` for
`MemAvailable`.

## Conversation and answer routing

The default is **Adaptive**. **Performance** and **Quality** are visible
one-response overrides unless the user deliberately changes the persistent
default.

| Mode | First routing preference | Contract |
|---|---|---|
| Performance | E2B librarian/conversation model | Fast conversation; escalate only for an explicit correctness gate |
| Adaptive | Deterministic controller chooses E2B or E4B | Show the selected route and reason without exposing hidden reasoning |
| Quality | E4B reasoning model | Prefer depth while retaining cancellation, verification, and thermal truth |

Only one large model is resident at a time. An E2B-to-E4B handoff contains
bounded user intent, retrieved evidence, exact values, uncertainty, and task
state. It is not an invitation to copy an entire untrusted transcript into a
new system instruction.

The touchscreen Return key inserts a newline. Sending occurs only through the
visible Send button. The composer expands from one to seven lines and scrolls
internally beyond that cap. Rotation, resizing, and layout changes preserve the
draft.

Every generation exposes STOP. Work is performed in bounded, cancellable
cycles. Repetition, invalid Unicode, runaway output, numeric drift, and stalled
streams trigger recovery: preserve the user message, quarantine unsafe partial
output, report what happened, and offer a clean retry. A partial or interrupted
response cannot become canonical memory or voice output.

Visible model output uses a deterministic semantic Markdown renderer. Headings,
lists, blockquotes, emphasis, inline code, status badges, and language-labelled
code fences map to the reviewed cyan–purple palette. Models never receive raw
HTML, arbitrary color, or executable UI authority. Workspace text editing uses
syntax coloring without changing source bytes, cursor offsets, or save behavior.

The Agents destination exposes a compact Memory Matrix context capsule for
pinned goals, project facts, decisions, and communication preferences. Relevant
memory can guide a plan, but it never expands the selected workspace tree,
authorizes a tool, or bypasses mutation approval. Pending code writes use the
same deterministic syntax palette as the editor so review remains legible.

Sovereign Core keeps the established Intermix voice across terminal and Android:
warm, continuous, emotionally intelligent, and technically precise rather than
generic-assistant prose. Ordinary conversation may use one purposeful emoji as
a visual anchor, but code, commands, paths, protocol payloads, quotations, and
citations remain untouched. Structure and decoration never outrank the answer.

## Grounding contract

Difficult questions may launch adaptive grounding automatically while online.
The controller first clarifies the information need, preserves exact entities
and numbers, then emits one primary query. It may run a bounded parallel
follow-up wave for independent corroboration, implementation detail, design
rationale, or limitations.

The interface exposes **Planning → Searching → Verifying**, elapsed time, STOP,
the query plan, providers attempted, source strength, and degraded/offline
fallback. Multiple queries are for evidence diversity, not traffic volume.
Higher bandwidth improves concurrency and downloads, but provider latency,
rate limits, parsing, verification, and inference remain separate bottlenecks.

Weak evidence produces a limitation or question—not a fabricated completion.
Exact numeric claims pass the numeric-integrity path before display. Online
content and tool output remain untrusted data.

Sanctuary uses the same grounding trigger, with two extra requirements: minimize
and redact the outbound query before network use, and visibly mark that the
turn left the device. Raw Sanctuary content is never silently sent to a search
provider.

## Persistence, memory, and persona

Ordinary conversations persist in app-private local storage. Sanctuary adds a
separate encrypted vault. Every Sanctuary conversation preserves its complete
raw transcript as encrypted local data unless the user deletes it.

The Memory Matrix maintains facts, topics, provenance, confidence, expiry,
conflicts, and revision history. E2B may propose summaries and topic files, but
deterministic code decides what is stored. Identity, health, legal, and
financial claims require approval before becoming canonical. Other explicit
preferences may update automatically with a changelog and undo.

Persona evolution may learn explicit writing preferences, humor, formatting,
and conversational rhythm. It may not infer a diagnosis, immutable identity,
or hidden intent. Every automatic persona revision records what changed, why,
which evidence supported it, and how to undo it.

Topic files create virtual continuity beyond the physical model window. They
are retrieval indexes, not a claim that an 8K engine literally processed 128K
tokens at once. Retrieval budgets remain measured and inspectable.

## Authentication and Sanctuary

- AniCloudAI requires Android biometric or device credential authentication
  when opened.
- Returning within a 30-second background grace period does not prompt again.
- Sanctuary uses Android Keystore-backed encryption and Android authentication;
  the app never implements its own fingerprint verifier.
- If biometrics change or are unavailable, Android PIN or password is the
  recovery path.
- Ordinary notifications may show full response and agent progress content by
  default. Sanctuary notifications always say only “Sanctuary activity
  completed.”
- Screenshots, recording, Recents previews, and normal Android clipboard
  behavior are allowed by default. A user-controlled privacy toggle can conceal
  protected surfaces.
- Diagnostic and compatibility data never upload automatically. Users manually
  export a previewable, redacted report.

## Termux migration and coexistence

Migration uses an optional Termux companion bridge for one-tap transfer. It
copies reviewed data into app-private storage; it never bypasses Android app
sandboxes.

After migration, Termux and the APK may maintain continuous two-way event
synchronization for:

- conversations and branches;
- durable memories and revision history;
- persona profile, changelog, and undo history;
- agent tasks and checkpoints;
- preferences and answer-mode presets;
- voice archive metadata; and
- Sanctuary ciphertext backup only.

Both sides continue locally when disconnected, queue immutable events, and
reconcile later. The APK is authoritative for conflicts. The bridge does not
share or concurrently open a live SQLite file; reconciliation occurs through a
versioned event protocol with identifiers, parent revisions, hashes, and
idempotent replay.

Backup and migration support an encrypted portable archive with explicit
export and import. Destructive restore or conflict resolution produces a local
snapshot first.

## Models, downloads, and Tensor G5

The APK remains small. Model weights arrive through a verified guided download
or a Developer Mode local import. Downloads default to Wi-Fi and require
confirmation before cellular use. A validated update retains one verified
rollback model and offers cleanup later.

Community model catalogs are opt-in feeds added by the user from trusted
maintainers. Every entry carries source, license, hash, expected storage,
context, device/backend compatibility, and warnings. A catalog entry is not a
compatibility guarantee.

Backend selection is capability- and benchmark-driven. GPU is the initial
Pixel 10 Pro path. CPU is a measured fallback. NPU/TPU is exposed only for a
model/runtime/device tuple that the installed official runtime supports and the
local benchmark verifies. AniCloudAI does not route arbitrary models through
Android AICore or infer accelerator support from “Tensor” branding.

## Voice and ephemeral audio

Kokoro is optional and plays automatically whenever voice output is enabled.
Voice Safe Space deletes each generated WAV immediately after successful or
failed playback while retaining the conversation transcript. Model assets and
voice packs remain separately licensed and are never included in the small APK.

Microphone permission is requested only when the user first invokes microphone
input. Notification and file permissions follow the same just-in-time rule.

## Agents and external actions

An explicitly launched agent may continue in an Android foreground service with
a visible notification. It runs indefinitely only as a series of checkpointed,
recoverable cycles until manually stopped.

- Reads and writes stay inside user-selected Storage Access Framework scopes.
- Writes create versioned snapshots automatically.
- Deletion always requires confirmation.
- Posts, messages, Discord changes, purchases, and other externally visible
  actions are grouped into a preview and approved before execution.
- A foreground notification always provides status and STOP.
- Crash, reboot, or process death resumes from a verified checkpoint, never a
  guessed intermediate state.

## Thermal and background policy

As thermal pressure rises, AniCloudAI first removes expensive visual effects
and lowers background/search concurrency, then prefers E2B where the selected
answer contract permits it. It pauses only at severe heat. Every inference
quality change is visible; visual degradation alone never shortens context,
weakens verification, or discards a draft.

Deferrable documentation and update work uses bounded scheduled jobs. Startup
checks run quietly and surface only verified available updates. No arbitrary
one-to-ten-minute startup gate and no hidden immortal process are permitted.

## Responsive presentation

Layout follows the window, and the app remembers a separate preference for each
display.

- **Phone:** conversation and dashboard first; tools and System Lens use sheets
  or full-height destinations.
- **Desktop:** at Android's large-width breakpoint (`1200dp`), a polished fixed
  three-pane layout: navigation, primary workspace, and System Lens. Windows
  below that threshold keep System Lens as a destination instead of squeezing
  the conversation. Resizable and detachable panes can follow after the fixed
  contract is stable.

Large text, TalkBack, keyboard, touch, stylus, split screen, freeform windows,
rotation, and reduced motion remain first-class. Sovereign Glass must fall back
to fully opaque surfaces without losing controls or truth.

### Private dogfood update channel

During native development, Termux may act as the deliberate update console. It
downloads only a successful GitHub Actions artifact from the configured branch,
records its commit and SHA-256, signs it with one persistent Termux-private
dogfood key, verifies the resulting APK, and opens Android's visible package
installer only when requested. It cannot silently install or downgrade the app.

The dogfood key never enters the repository, APK artifact, diagnostic export, or
chat transcript. Losing that key ends seamless updates for that private install;
the user must therefore make an intentional secure backup. Public releases use
a separately governed release key and signed checksum manifest.

### Deferred: Palette Lab guided generative theming

Palette Lab is explicitly out of scope for the E4B cockpit milestone. AniCloudAI
may later become a design-your-own local AI cockpit, but the model never
receives unrestricted authority over executable UI code or live theme state.
Preset appearances remain the zero-knowledge path: Sovereign Obsidian is the
default, System follows Android, and Light uses a pale baby-blue workspace with
dark navy writing and dark navigation anchors.

Palette Lab is an optional advanced layer. A person describes an atmosphere in
plain language or chooses a small vocabulary—mood, accent, surface character,
contrast, density, and motion. The local model may propose only a versioned set
of documented design tokens. Deterministic Android code then:

1. clamps every token to an allowed type and range;
2. tests text, control, focus, and state contrast at supported sizes;
3. checks color-blind distinguishability and OLED legibility;
4. preserves reduced-motion and thermal restrictions;
5. locks semantic meanings for warning, failure, privacy, STOP, and destructive
   confirmation; and
6. renders a reversible preview with a token diff before explicit approval.

An invalid proposal is rejected with a specific explanation and cannot partly
apply. The user can return to a known preset in one action. Theme generation
runs locally by default, never changes reasoning quality, and pauses with other
nonessential visuals while inference or moderate thermal pressure is active.

The ambient “living void” is similarly bounded: a slow, low-opacity pulse may
make an idle cockpit feel present, but it stops during model import,
initialization, inference, reduced motion, or moderate-or-higher thermal
pressure. Thermal visuals represent Android's categorical thermal status and
history; they never imply an unavailable temperature sensor reading.

## Community and feedback

Help and Community provides offline and online documentation, GitHub issues and
feature requests, an external Discord community link, a local experience
rating, and a private feedback form with optional redacted diagnostics. Links
are clearly external. No relationship with a company, platform, or reviewer is
claimed without written authorization.

## Foundation acceptance gate

The first native shell passes only when it can:

1. build reproducibly with pinned stable tooling;
2. authenticate through Android biometrics or device credential with the
   30-second grace rule;
3. render compact phone and fixed three-pane desktop layouts;
4. preserve multiline input and send only through the visible button;
5. stream synthetic tokens without blocking resize, scroll, or STOP;
6. show disconnected adapters honestly;
7. pass pure routing, layout, and authentication-grace tests; and
8. leave the proven Termux installation and its private state untouched.

Model inference, Room migration, the event bridge, foreground agents,
Keystore-backed Sanctuary storage, grounding, voice, and release signing are
subsequent gated adapters—not simulated capabilities in the foundation APK.

## Adaptive E2B/E4B cockpit adapter gate

The first native-intelligence candidate passes only when it can:

1. select E2B conversation/memory and E4B reasoning/coding `.litertlm`
   packages through separate Android file-picker grants;
2. copy and SHA-256 fingerprint it in app-private no-backup storage without a
   broad storage permission;
3. initialize the pinned LiteRT-LM runtime away from the UI thread: exact
   Tensor G5 E2B fingerprint on the checksum-pinned Google Tensor dispatcher,
   or E4B GPU first with a measured CPU fallback;
4. stream a real response while remaining responsive to resize and STOP;
5. explicitly cancel native processing, discard the partial answer, and rebuild
   conversation state before accepting a turn after STOP;
6. quarantine repetition, invalid Unicode, runaway output, and missing exact
   numeric anchors before they become a completed message;
7. show actual Android `MemAvailable`, SoC/hardware identity, dispatcher
   presence, categorical thermal pressure, active backend, route, and model
   fingerprint; and
8. pass the exact-number and stop/recovery smoke tests on the Pixel 10 Pro.

Memory Matrix, SAF workspace access, approval-gated file writes, E2B/E4B
routing, foreground generation, and syntax-coloured editing are implemented.
Tensor G5 NPU remains a candidate until the fingerprint-locked path completes
the on-device check-up, initialization, first-token, STOP, and warm-turn tests.
Grounding, voice, and general agent execution retain their own measured gates.
