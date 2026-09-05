# Native Android APK and TPU feasibility

The implementation-facing product decisions are frozen in the
[AniCloudAI Android product contract](ANDROID_PRODUCT_CONTRACT.md). The initial
`android/` module is a deliberately disconnected native shell: it proves
responsive composition, authentication, multiline input, synthetic streaming,
STOP, and truthful unavailable states before private data or the model runtime
can cross the boundary.

## Verdict

A native Project Intermix APK is feasible today. Reusing the exact Gemma 4
E4B/8K workload on the Pixel 10 Pro TPU is **not yet a supported assumption**.
The sensible delivery order is a GPU-backed APK first, followed by a separately
measured Google Tensor NPU lane when a compatible NPU model artifact exists.

## What is officially available

Google publishes a [LiteRT-LM Kotlin API for Android](https://developers.google.com/edge/litert-lm/android)
with Maven packages, asynchronous streaming, and CPU/GPU/NPU backend concepts.
Its own guidance warns that engine initialization can be significant and should
run away from the UI thread. That maps cleanly to Intermix's existing resident
engine lifecycle.

Google also publishes a beta [Google Tensor SDK](https://developers.google.com/edge/litert/next/tensor-sdk)
that directly exposes the Pixel TPU through LiteRT. As of 2026-08-25, its public
support table names Tensor G5. This makes TPU work a real engineering path for
the Pixel 10 generation—not a theoretical AICore workaround.

The limitation is model-specific. The current
[LiteRT-LM NPU guide](https://developers.google.com/edge/litert/next/litert_lm_npu)
lists a Tensor-G5 NPU artifact for Gemma 3 1B with a 1,280-token context. It does
not list the current Intermix Gemma 4 E4B 8K artifact. An OpenCL/GPU `.litertlm`
file must not be assumed to contain the AOT-compiled Tensor dispatch payload.

## What the PC changes

The Tensor SDK beta currently documents a Linux x86_64 development workstation,
Ubuntu 22.04, Bazel, Android SDK/NDK, and at least 16 GB workstation RAM. The LLM
NPU guide calls for NDK r28b or newer to build the Android ARM64 runtime and
Google Tensor dispatch library. A capable PC therefore unlocks both a normal
Android Studio workflow and the native NPU experiment that is impractical to
assemble entirely on the phone.

## Recommended APK sequence

### Milestone 1 — native shell, then known GPU backend

- Kotlin + Jetpack Compose responsive UI. **Foundation scaffold present.**
- LiteRT-LM Android Maven dependency and asynchronous token flow.
- Foreground, user-visible inference lifecycle with cancellation.
- Existing SQLite schema migration or reviewed export/import.
- Storage Access Framework workspace selected by the user.
- Android Keystore-backed provider vault.
- User-selected external model file; no multi-gigabyte model inside the base APK.
- Backend telemetry matching the Termux cold/warm measurements.

This milestone should reproduce behavior before redesigning memory or autonomy.
The committed foundation does not claim model, database, network, thermal, or
voice integration; those signals are visibly disconnected until their adapters
are measured.

### Milestone 2 — native Android integration

- WorkManager for durable, deferrable refresh/report jobs.
- Android audio playback and optional on-device TTS adapter.
- Phone, tablet, foldable, and desktop-window layouts.
- Crash-safe request journal and low-memory callbacks.
- Import wizard from an explicitly exported Termux database.

Android recommends [WorkManager for reliable persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)
and requires foreground services to remain visible to users. The app should not
try to preserve an invisible immortal model process.

### Milestone 3 — Google Tensor NPU experiment

1. Enroll in the Google Tensor SDK beta.
2. Build the dispatch/runtime toolchain on the supported Linux workstation.
3. Test a model explicitly compiled for Tensor G5.
4. Measure correctness, first load, tokens/second, peak `MemAvailable`, energy,
   and thermal behavior against the current GPU path.
5. Enable NPU only for a verified model/runtime/device tuple; otherwise fall
   back to GPU and then CPU.

If a Gemma 4 E4B/8K Tensor-G5 artifact becomes available, it becomes a candidate
for this gate. Until then, TPU support and exact Core parity are separate goals.

## Packaging and model delivery

The base APK should remain small and model-agnostic. Early builds can use the
Android document picker to import a separately licensed `.litertlm` file. A
later Play-distributed build can evaluate Play for On-device AI / AI Packs for
SoC-targeted assets. Models, compiled packs, and their licenses remain separate
from the Apache-2.0 Intermix controller source.

## AICore is not the migration path

Pixel AICore/Gemini Nano is a managed Android system service with its own model
and API contracts. It should not be treated as general access to the Tensor TPU
or as a drop-in host for an arbitrary Intermix model. The relevant path is the
official LiteRT-LM and Google Tensor SDK toolchain.

## Go/no-go gates

| Gate | Go condition |
|---|---|
| Native UI | Responsive prototype streams fake tokens without frame stalls |
| GPU parity | Same model produces equivalent guarded responses and survives restart |
| Memory migration | Synthetic and copied-user exports round-trip without silent loss |
| Workspace | SAF scope and deletion review pass instrumentation tests |
| NPU | Officially compatible artifact initializes and passes correctness/pressure benchmarks |
| Public APK | Reproducible CI build, owner-controlled signing, provenance, SBOM, and privacy review |

The fallback is not failure: a polished GPU APK would already remove terminal
setup friction while retaining the working reference behavior.
