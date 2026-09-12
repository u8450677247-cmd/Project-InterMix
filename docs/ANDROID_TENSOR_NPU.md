# Tensor G5 NPU adapter

Status: **implemented candidate; on-device validation pending**.

The native AniCloudAI cockpit has two explicit model roles and keeps only one
LiteRT-LM engine resident:

| Role | Package | Backend policy |
|---|---|---|
| Conversation + Memory Matrix | `gemma-4-E2B-it_Google_Tensor_G5.litertlm` | Tensor NPU only |
| Reasoning + coding | compatible E4B `.litertlm` | GPU first, measured CPU fallback |

The E2B lane is fail-closed. It cannot silently load a package compiled for a
different accelerator and it cannot fall back to GPU. Adaptive mode uses E2B
for ordinary conversation and memory-oriented turns, and E4B for technical,
coding, research, or long-form work. Performance prefers E2B; Quality prefers
E4B. The deterministic Android controller—not generated model text—owns the
decision.

## Supply-chain pins

- LiteRT-LM Android: `com.google.ai.edge.litertlm:litertlm-android:0.17.0`
- Google Tensor dispatcher release: LiteRT `2.2.0`
- Dispatcher archive SHA-256:
  `b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d`
- Extracted arm64 dispatcher SHA-256:
  `35b59265eb8595a1d28c2f69693b1cad39d1fa4a38c2c18d5d54550d079264ac`
- Reviewed Tensor G5 E2B SHA-256:
  `af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc`
- Packaged native library: `libLiteRtDispatch_GoogleTensor.so`

CI downloads the official archive, checks the complete archive hash, extracts
only the arm64 Google Tensor dispatcher, and then builds the APK. The binary is
not committed to this repository. See `tools/fetch_tensor_dispatcher.sh` and
`.github/workflows/android-foundation.yml`.

Primary upstream references:

- [LiteRT-LM Android Kotlin API](https://developers.google.com/edge/litert-lm/android)
- [LiteRT NPU acceleration](https://ai.google.dev/edge/litert/next/acceleration/npu)
- [LiteRT-LM 0.17.0 release](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.17.0)
- [LiteRT 2.2.0 release](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0)

## Device check-up gate

System Lens and `/device` report every controller input used by the gate:

1. Android SoC model and hardware codename identify the Tensor G5 reference
   target;
2. the pinned dispatcher exists in the installed APK native-library directory;
3. the imported E2B byte fingerprint is an exact reviewed match;
4. Android `MemAvailable` is at least 1.75 GiB before load; and
5. Android is below severe thermal pressure.

A green readiness result means the controller is allowed to attempt NPU
initialization. It is not proof of successful inference. The first Pixel-native
proof requires a successful initialization, correct first response, second warm
turn, STOP/recovery cycle, and recorded RAM/thermal observations. If NPU startup
fails, AniCloudAI closes the partial engine, states the sanitized error, and
restores E4B when available.

## Operator validation

After installing the persistently signed dogfood APK:

1. open **System → Device Check-up** before importing E2B;
2. import the G5 E2B file through **Import E2B · Tensor G5**;
3. confirm the full check-up becomes **NPU READY** and `/models` says the E2B
   backend policy is NPU-only;
4. send a short prompt in Performance mode and record model load plus TTFT;
5. send a coding prompt in Adaptive mode and confirm the route switches to E4B;
6. send another ordinary prompt and confirm the engine unloads E4B before E2B;
7. test STOP during a long turn; and
8. capture System Lens with no personal workspace or conversation content.

Do not report the NPU route as reference-verified until this sequence passes on
the exact installed APK commit and both APK/certificate hashes are recorded.
