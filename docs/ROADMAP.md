# Roadmap

The roadmap is evidence-driven. Dates are deliberately omitted until the
preceding gate is reproducible.

## Phase A — public alpha evidence

Goal: prove that the current Python/Textual architecture can be installed,
measured, recovered, and extended without publishing private state.

- Clean-install reports on the Pixel 10 Pro reference path.
- Community profiles for other Pixel, DeX, Android desktop, Linux ARM, and
  desktop systems.
- Reproducible cold/warm latency, `MemAvailable`, thermal, and Signal 9 data.
- Provider success/failure telemetry without query or key leakage.
- Compact-layout and accessibility fixes.
- Deterministic PDF/report export from selected, reviewed content.
- Optional Resonance/Kokoro installer separated from the core release.

Exit gate: repeatable installation plus no critical privacy, workspace, claim,
or recovery regressions across a documented device set.

## Phase B — native Android application

Goal: replace terminal-specific presentation and orchestration with an APK while
preserving the tested controller contracts.

The native interface follows the versioned [Sovereign Glass design
contract](ANDROID_DESIGN.md): conversation-first on compact windows, inspectable
runtime truth, biometric Sanctuary boundaries, accessible opaque fallbacks, and
resource-aware visual effects.

| Concern | Native direction |
|---|---|
| Interface | Kotlin + Jetpack Compose; responsive phone, tablet, and desktop layouts |
| Inference | LiteRT-LM Android/Kotlin API behind a backend capability adapter |
| Long work | Bound foreground service with visible lifecycle and cancellation |
| Memory | Room/SQLite migration compatible with the existing schema or an audited export/import boundary |
| Workspace | Storage Access Framework tree selected explicitly by the user |
| Idle work | WorkManager for bounded deferrable tasks; never an immortal hidden loop |
| Credentials | Android Keystore-backed encrypted provider configuration |
| Voice | Android audio APIs; Kokoro remains optional and capability-gated |
| Packaging | Gradle version catalog, reproducible CI, signed release artifacts, SBOM |

The first APK should support import rather than silently reading Termux private
storage. Android application sandboxes are a security boundary, not an obstacle
to bypass.

### PC build requirements

A practical native build needs a PC with Android Studio, a supported JDK,
Android SDK/platform tools, Gradle, and potentially the NDK/CMake toolchain for
native LiteRT components. The repository can contain the full project and CI,
but release signing uses a private keystore controlled only by the owner.

### TPU/NPU policy

“Tensor phone” does not imply unrestricted third-party TPU access. Intermix will
only expose an NPU/TPU option when the installed LiteRT-LM API and device driver
officially advertise a compatible backend for the selected model. The app will:

1. enumerate supported backends without loading user data;
2. run a bounded local benchmark after consent;
3. retain the measured working profile per model/device/runtime version;
4. fall back to GPU, then CPU, on initialization failure; and
5. label the result as measured, never inferred from the marketing name.

Pixel AICore/Gemini Nano and a general LiteRT-LM NPU backend are not assumed to
be interchangeable. No proprietary service or privilege boundary will be
circumvented.

Exit gate: an installable APK with equivalent memory, grounding, workspace,
privacy, cancellation, and recovery tests on at least the reference device.

## Phase C — portable controller and richer cognition

- Platform-neutral controller/memory package shared by TUI and APK.
- Versioned knowledge entities, conflicts, provenance, and expiry.
- Reviewable self-audit proposals rather than silent self-modification.
- Tool orchestration with per-tool permission and resource budgets.
- Multimodal adapters only after text/image data boundaries are explicit.
- Desktop Linux and possibly Windows/macOS packaging based on contributor demand.

## Explicit non-goals

- Rate-limit or CAPTCHA evasion.
- Publishing model weights or third-party credentials.
- Diagnosing mental health from inferred behavior.
- Unreviewed self-modification of the controller or its security policy.
- Claiming compatibility from chipset specifications alone.
