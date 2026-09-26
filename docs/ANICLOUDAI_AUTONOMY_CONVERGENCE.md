# AniCloudAI autonomy convergence evidence

Status: source-complete candidate; Android CI and physical-device gates remain independent evidence.

## Executable convergence units

- LiteRT-LM uses native typed tools with manual controller responses. Tagged text remains a
  compatibility transport only when native tools are unavailable.
- Mission, task, step, run, transaction, operation, receipt, and tool-call identities survive the
  mission checkpoint codec. Stale and duplicate results are audited without advancing progress.
- Controller failures carry typed codes and route to bounded deterministic, transient, model,
  human-authority, or security-stop dispositions.
- Multi-operation failures compensate verified replacements from snapshots and retain explicit
  uncompensated-side-effect evidence when deletion is outside authority.
- Workspace artifacts have stable identities, version history, verification checkpoints, and typed
  provenance edges.
- Cognition routing filters declared tool, context, strength, locality, privacy, network, backend,
  latency/cost, and thermal capabilities before applying preference.
- A user-issued execution grant atomically admits and queues a fixed offline
  inspect/build/test/lint allowlist with execution, wall-time, output, and expiry budgets. Network,
  dependency, destructive, chained, piped, and redirected proposals require separate authority.
- WorkManager persists typed scheduler state, rechecks power/thermal/runtime prerequisites, and can
  recover a granted Termux action stranded between durable queueing and dispatch.

## Evidence recorded in this branch

- `python3 -m unittest discover -s tests -q`: 186 tests passed, 5 skipped.
- `python3 -m unittest tests.test_android_foundation -q`: 54 tests passed.
- Git diff whitespace validation passes.
- GitHub Actions must pass `:app:testDebugUnitTest` and `:app:assembleDebug` for the final remote
  tree before it is eligible for protected dogfood signing.

## Physical-device gates not replaced by CI

1. Install the protected update over the existing Pixel 10 Pro app without clearing data and verify
   the Matrix schema 8→9 migration, conversation tail, active mission, and artifact graph.
2. Exercise one real native LiteRT tool call and confirm manual tool-response continuation without
   a tagged workspace payload.
3. Configure the Termux project root, issue `/exec grant`, run one offline build/test/lint action,
   force-stop between queue and dispatch, and verify exactly one recovered execution and one result.
4. Revoke a grant with a queued command and verify it becomes denied; verify network/dependency and
   shell-control proposals remain approval-gated.
5. Repeat mission recovery under backgrounding, process death, Doze, low-battery constraints, and
   severe thermal pressure. WorkManager reconciliation is implemented; fully headless model
   inference while no live native controller exists is not claimed.
6. Record cold/warm latency, memory, thermal, STOP, and sustained workload evidence on the target
   Pixel 10 Pro or the proposed actively cooled OnePlus 10 Pro.

Next executable step after the final branch CI succeeds: dispatch `android-foundation.yml` with
`sign_dogfood=true`, approve the protected `dogfood-signing` environment, then run
`tools/termux_dogfood_update.sh --open` on the target device.
