# Device matrix and test protocol

Compatibility is evidence-based and dated. Similar hardware, the same Android version, or an upstream LiteRT-LM benchmark is useful prior evidence, but it is not an Intermix verification.

## Current matrix

| Device / platform | Intermix status | Evidence as of 2026-08-25 | Next required evidence |
|---|---|---|---|
| Pixel 10 Pro, Tensor G5, Android 17 | **Reference verified for Termux; native NPU candidate** | Owner-tested 8K E4B GPU/OpenCL inference plus native memory/workspace/glass dogfood; exact G5 E2B and LiteRT 2.2.0 dispatcher are integrated behind a fail-closed check | Native NPU initialization, first-token, warm-turn, STOP, RAM, thermal, and restart report |
| Galaxy S26 Ultra | **Runtime candidate** | Google's Gemma 4 guide publishes Android E2B/E4B CPU and GPU benchmarks on this device | Intermix clean install, DeX geometry, OpenCL/GPU behavior, memory pressure, restart recall |
| Pixel 11 family | **Candidate, unverified** | No Project Intermix test report | Exact model/backend probe and complete test protocol; do not infer from Pixel 10 behavior |
| Other Pixel desktop-mode devices | **Community experimental** | Shared OS concepts only | Device report, model smoke test, cold/warm latency, compact/desktop captures |
| Samsung DeX devices | **Community experimental** | DeX is a plausible TUI surface; no Intermix validation | Termux package/runtime test and model-specific GPU evidence |
| Xiaomi/POCO devices with display output | **Model-specific candidate** | USB-C display output and desktop behavior vary by model | Exact SKU report; never blacklist or approve an OEM as a whole |
| Linux ARM64 / x86_64 | **Runtime candidate** | LiteRT-LM Python supports Linux; Intermix source is largely portable | Backend profile, path abstraction, Textual tests, workspace sandbox review |
| macOS / Windows | **Future contributor path** | LiteRT-LM supports both platforms | Replace Termux installer assumptions and validate process/file containment |

Primary upstream references:

- [LiteRT-LM Python API](https://developers.google.com/edge/litert-lm/python)
- [Gemma 4 deployment and published device benchmarks](https://developers.google.com/edge/litert-lm/models/gemma-4)
- [Termux application and Android process-kill warning](https://github.com/termux/termux-app)

## Resource tiers

These are conservative Intermix installation tiers, not universal LiteRT-LM requirements.

| Physical RAM | Installer classification | Default guidance |
|---:|---|---|
| 14 GiB or more | Recommended E4B tier | 8,000-token context candidate after clean preflight |
| 12–14 GiB | E4B candidate | 8,000 only with measured headroom and background apps closed |
| 8–12 GiB | Reduced model/context recommended | Start with E2B or 4,096 tokens; treat E4B as experimental |
| Below 8 GiB | Below community floor | Installer stops unless a contributor deliberately uses `--force` |

Storage also matters: the E4B model is several gigabytes and compiled artifacts can add several more. The installer stops below 6 GiB free and warns below 10 GiB.

## Share-safe probe

Run outside the cockpit:

```bash
intermix-doctor --json > intermix-device-report.json
```

Review the file before attaching it. The default probe excludes unique identifiers and personal content by design.

## Required community test sequence

1. **Record the exact environment:** device SKU, SoC, RAM, Android build, Termux source/version, Python, LiteRT-LM, model filename/hash prefix, backend, context ceiling, screen geometry, and cooling state.
2. **Run source tests:** all deterministic tests must pass. UI tests should run on the target device.
3. **Cold start:** close Intermix, confirm no resident LiteRT process, launch, send an exact short probe, and record initialization and first-text latency.
4. **Warm turn:** send the second probe within ten seconds and record whether the engine was reused.
5. **Memory pressure:** record `MemAvailable` before load, resident hot, after a long response, and after unload. Never substitute `MemFree`.
6. **Continuity:** create a synthetic workspace mission, verify it, restart, open a new session, and recall the verified outcome.
7. **Grounding:** test one supported current exact claim and one intentionally unsupported claim. The unsupported claim must fail closed.
8. **Workspace repair:** write a synthetic failing Python file, observe stop-on-failure, repair in a new epoch, test, and complete.
9. **Geometry:** capture desktop and phone/compact layouts with synthetic, non-personal text; verify multiline paste, blank-line preservation, `Shift+Enter`, seven-row capping, internal scrolling, and post-send collapse.
10. **Stability:** note Signal 9, Android process kills, thermal throttling, UI stalls, provider failures, and cache growth during repeated daily use.

Never upload raw memory databases, prompts, provider vaults, model files, shader caches, Android IDs, or screenshots containing personal content.

## Cooling

Active cooling is optional. The reference owner recommends the Black Shark Magnetic/FunCooler 6 Pro (BR62) and observed roughly 25 °C during their fixed setup. That is one setup under one environment, not a promised temperature or performance figure.

If using a Peltier cooler:

- power it independently when possible;
- prevent it from obstructing buttons, radios, or the USB display connection;
- watch for condensation when operating below ambient dew point;
- avoid trapping moisture against the phone; and
- report ambient temperature and cooler mode with performance results.

Cooling does not prevent Android memory management or guarantee protection from Signal 9.

## Status promotion

A device moves to **community verified** only after a complete report is reproducible, private data is absent, the test suite passes, failures are documented, and at least one maintainer reviews the evidence. A single successful answer is not enough.
