# Sovereign Glass Android design language

Status: version 0.1 design contract for the native Project Intermix APK.

Sovereign Glass is the internal name of the interface system, not a final app
name or trademark decision. The Pixel 10 Pro is the reference canvas, but the
rules below are based on Android window size and capability rather than a device
model check.

## Product principles

1. **Conversation first.** The transcript and composer own the primary surface.
   Diagnostics, memory, tools, and model controls remain one deliberate gesture
   away instead of permanently shrinking the conversation.
2. **Truth is visible.** Grounding, handoffs, interruption, corruption recovery,
   and offline fallbacks have distinct, inspectable states. Animation must never
   imply progress that the controller cannot verify.
3. **Private by construction.** Local operation is the default. Sanctuary and
   provider credentials use native Android security boundaries, and no analytics
   leave the device unless a future user explicitly opts in.
4. **Calm luxury.** Depth comes from restrained smoked surfaces, light, spacing,
   typography, and motion—not decorative noise or constant animation.
5. **Adaptive, never cramped.** The same capability remains reachable at every
   supported window size even when its presentation changes.

## Core palette

| Token | Value | Role |
|---|---:|---|
| Obsidian | `#070A12` | Primary canvas |
| Smoked | `#0C1422` | Raised panels and opaque blur fallback |
| Smoked deep | `#09101C` | Navigation and system surfaces |
| Horizon cyan | `#39D5FF` | Grounded facts, focus, active controls |
| Cognition violet | `#A970FF` | User intent, memory, model handoff |
| Soft violet | `#C39AFF` | Secondary cognition accents |
| Resonance mint | `#67E8C2` | Ready, verified, completed |
| Waiting amber | `#FFCA6B` | Waiting, degraded, attention |
| Intervention coral | `#FF6F91` | Stop, corruption, destructive review |
| Primary text | `#E7EDF5` | Main readable content |
| Muted text | `#8492A8` | Metadata and secondary labels |

Color is never the only state carrier. Every state also receives a stable icon
and text label. Contrast must be tested against the actual composited surface,
including any wallpaper or content visible behind a translucent layer.

## Material and depth

- The conversation canvas remains nearly opaque and visually quiet.
- Blur is reserved for navigation chrome, the composer, bottom sheets, and
  short-lived overlays. Message content does not become a stack of glass cards.
- The balanced default uses a dark translucent tint over a deterministic opaque
  fallback. Reduce-transparency, battery, accessibility, or thermal signals may
  select that fallback without removing information.
- Cyan and violet edge light establishes hierarchy; shadows stay soft and sparse.
- The app must remain complete and legible when system blur is unavailable.

## Type and spacing

- Android system sans is the default for native controls and conversation text.
  The project does not bundle a proprietary font without an explicit license.
- A redistributable monospace face may be used for code, exact values, hashes,
  and telemetry. It is not the default conversation font.
- Dynamic type is supported without clipping critical controls.
- Paragraph rhythm and intentional blank lines survive composition, storage,
  restoration, and rendering.
- Touch targets meet Android accessibility guidance even when the visible icon
  is compact.

## Reference surfaces

| Surface | Contract |
|---|---|
| Conversation stream | Full-height reading surface with restrained speaker accents and preserved Markdown structure |
| Sovereign Composer | Grows from one to seven visible lines, scrolls after the cap, supports multiline paste, and exposes an immediate STOP state during generation |
| Truth Thread | Compact phase strip for recalling, grounding, reasoning, verifying, degradation, and recovery; expands into evidence details |
| System Lens | Bottom sheet on compact windows and side rail on expanded windows for runtime, memory, providers, thermals, and model state |
| Memory Matrix | Searchable, provenance-aware view with review, correction, expiry, and conflict controls |
| Model Handoff | Inspectable E2B/E4B route, handoff reason, resident model, and fallback outcome without exposing internal chain-of-thought |
| Sanctuary Gate | Biometric entry surface backed by Android Keystore; sensitive content is concealed in recents when the user enables that policy |
| Workspace Lens | User-scoped document tree with explicit write state and destructive-action review |

## Runtime states

The UI renders controller truth through this stable vocabulary:

| State | Meaning | Required affordance |
|---|---|---|
| Ready | No request is active | Composer enabled |
| Recalling | Local memory retrieval is active | Source count or neutral progress |
| Grounding | Bounded network research is active | Provider and cancel access |
| Reasoning | A model is generating | Live elapsed time and STOP |
| Verifying | Claims or stream integrity are being checked | Preserve the pending response |
| Recovering | A cancelled or corrupt turn is quarantined | Explain what was kept and what was excluded |
| Degraded | A provider, model, or visual capability fell back | Name the actual fallback |
| Offline | Network features are unavailable | Local conversation remains usable |

No indefinite spinner is allowed. Long stages expose elapsed time, cancellation,
and a plain-language explanation. A watchdog intervention uses coral only for
the affected turn; it does not present the whole system as failed.

## Responsive composition

Layout follows measured window classes, not keyboard or desktop-mode guesses.

| Window | Primary layout |
|---|---|
| Compact | Conversation first; System Lens, Memory Matrix, and tools open as full-height or modal bottom sheets |
| Medium | Conversation plus an optional resizable contextual pane |
| Expanded | Cockpit layout with persistent navigation or telemetry rail when the user leaves it enabled |

Rotation, split-screen, freeform desktop windows, display scaling, physical
keyboards, and software keyboards must preserve the draft, transcript position,
active request, and reachable STOP control. Hidden panels remain reachable from
the same semantic navigation destinations.

## Motion and haptics

- Standard transitions target roughly 120–220 ms and never block input.
- Streaming text does not pulse, shimmer, or reflow unrelated content.
- Send, successful verification, STOP, and destructive confirmation may use
  distinct, user-disableable haptics.
- Reduced-motion disables decorative interpolation and retains direct state
  changes.
- There is no perpetual ambient animation while the app is idle.

## Thermal and resource policy

Visual quality and inference quality are separate controls. A thermal fallback
may remove blur, reduce animation, lower repaint frequency, or pause background
previews. It must not silently switch models, shorten context, weaken claim
checking, or discard user content. Any inference degradation is named in the
Truth Thread and remains inspectable in System Lens.

The default profile is **Balanced Glass**. A future **Full Glass** preference may
enable richer translucency when the device is cool and accessibility settings
permit it; **Opaque** remains a first-class appearance rather than an error mode.

## Accessibility and input

- TalkBack labels describe icon-only actions and dynamic model states.
- Focus order follows reading order across resized layouts.
- Hardware keyboard, touch, stylus, and software keyboard input share the same
  submit/newline semantics where practical.
- Critical controls remain reachable at large font and display scaling.
- Exact values have copy actions and do not rely on abbreviated visual labels.
- Flashing, rapid color cycling, and color-only warnings are prohibited.

## Privacy surfaces

- Provider keys are stored with Android Keystore-backed encryption.
- Sanctuary authentication uses `BiometricPrompt`; the app does not invent its
  own fingerprint verifier.
- Concealing Sanctuary in screenshots and the recents preview is an explicit
  user policy because it also disables convenient capture and sharing.
- Crash and diagnostic exports are local, previewable, and redacted before the
  user deliberately shares them.
- The base experience has no advertising identifier and no silent telemetry.

## Native component map

| Intermix contract | Android implementation direction |
|---|---|
| Responsive surfaces | Jetpack Compose adaptive layouts and window size classes |
| Controller phases | Lifecycle-aware immutable UI state |
| Persistent memory | Room/SQLite behind the portable memory contract |
| Long visible work | Bound foreground service with notification and cancellation |
| Deferrable maintenance | WorkManager with explicit constraints and bounded runs |
| Workspace | Storage Access Framework user-selected tree |
| Credentials and Sanctuary | Android Keystore plus `BiometricPrompt` |
| Model files | User-selected external assets with verified metadata and separate licensing |

## Acceptance gate for the first visual prototype

The design is implemented only when a prototype can:

1. stream synthetic tokens while keeping scrolling, resize, and STOP responsive;
2. preserve a seven-line draft through rotation and compact/expanded transitions;
3. expose every System Lens value from a compact phone window;
4. render every runtime state with text, icon, and accessible semantics;
5. pass contrast, large-text, TalkBack, reduced-motion, and opaque-mode checks;
6. survive process recreation without inventing request or memory state; and
7. show an honest GPU/CPU/NPU and E2B/E4B route based on measured runtime data.

This contract deliberately anchors behavior before high-fidelity decoration.
Reference mockups may evolve, but they may not hide truth, privacy, cancellation,
or compact-window capability to achieve a cleaner screenshot.
