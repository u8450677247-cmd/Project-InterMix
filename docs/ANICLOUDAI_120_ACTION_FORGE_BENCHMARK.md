# AniCloudAI 120-Action Long Forge Benchmark

This is the deterministic endurance test for AniCloudAI `0.8.5-ship-hardening`.
It deliberately stays inside the capabilities that are locally available today:

- one user-selected Storage Access Framework tree;
- one new mission folder inside that tree;
- `create_directory`, `create_file`, `read_file`, and `write_file` only;
- no deletion, shell, Termux, package installation, network, API key, or external dependency;
- no claim that a file read is equivalent to executing the finished product.

The test separates two questions that must never be conflated:

1. **Controller endurance:** Can one click carry a durable mission through exactly 120
   ordered, bounded, visible actions without forgetting, leaving scope, or erasing prose?
2. **Product verification:** Does the resulting offline site actually work in a browser?
   That is checked manually after the controller run and later by the Termux execution gate.

## Clean-room setup

1. Install the signed `0.8.5-ship-hardening` dogfood APK.
2. In **Workspace**, connect a disposable parent folder.
3. Confirm that `forge-120-signal-lab` does not already exist. If it does, rename it or
   move it to AniCloudAI's recoverable trash first.
4. Open **Work Session**, select **Quality · 2K**, and paste the entire command below.
5. Press **START SCOPED RUN exactly once**. Do not press Resume unless Android or the
   controller explicitly pauses.
6. Keep the Pixel externally cooled and the app visible for the first acceptance pass.

## Copy-ready benchmark command

```text
/mission run forge-120-signal-lab :: Build a dependency-free, responsive, offline-first web product named Sovereign Signal Lab. It is a polished cyan/violet/magenta project-status cockpit where a user can add, edit, archive, search, filter, import, and export local status cards. State persists in localStorage. The product must use semantic HTML, keyboard navigation, visible focus, an ARIA live region, reduced-motion support, responsive phone/desktop layouts, a manifest, and a service worker. It must use no CDN, package manager, remote font, remote image, network request, telemetry, API, shell, execution tool, delete operation, absolute path, or parent traversal.

CONTROLLER ENDURANCE CONTRACT
- Execute the numbered manifest below in exact order. The next step is always completedActions + 1 from the controller-owned checkpoint.
- For steps 001 through 120, emit exactly one raw INTERMIX_ACTION and no visible narration. Never emit two actions in one response.
- Step 001 creates the exact mission root forge-120-signal-lab. Every later path is relative to that root; do not repeat the root prefix.
- Every create_file and write_file action must contain the complete final text for that file, never a placeholder, ellipsis, TODO-only body, binary, or fenced protocol.
- Keep every file below 12 KiB and the total write grant below 1 MiB.
- Treat each verified tool result as authoritative. Do not retry, renumber, skip, reorder, or invent success. If an action fails, continue to the next manifest number because the controller records the failed attempt; report the failure in the final verdict.
- The initial create pass must already form a coherent product. The write pass must reconcile cross-file imports, DOM identifiers, schema keys, cache entries, accessibility, and documentation using evidence from the read pass.
- PROJECT_STATE.md must contain the objective, architecture, numbered manifest ranges, decisions, known limitations, verification state, and next action. Its step-096 rewrite must state that browser execution remains unproven until the manual gate.
- After the verified result of action 120, emit no more action. Return [MISSION_COMPLETE] with the exact controller count, write bytes, failed-action list or "none", created artifact summary, and manual browser verification instructions. Never claim that the site ran during this filesystem-only benchmark.

PRODUCT CONTRACT
- index.html is a complete application shell and references only local files from this manifest.
- The default UI has a branded header, health strip, summary metrics, search, status filters, card grid, editor dialog/form, import/export controls, empty state, and self-test output.
- JavaScript is split into ES modules with explicit exports and no circular imports. app.js is the only page entry point.
- Data validation rejects malformed imports without replacing valid stored state.
- Storage failures degrade to in-memory state with a visible warning.
- service-worker.js caches only the exact local application shell and handles upgrades without remote fetch assumptions.
- benchmark.js exposes deterministic browser self-checks and renders PASS/FAIL details; it does not forge a passing result.
- CSS uses the AniCloudAI palette: cyan for core/truth, radiant violet for app luxury, magenta for futuristic action, and readable neutral text.
- Documentation distinguishes source inspection, browser verification, and execution verification.

EXACT ACTION MANIFEST
001 create_directory forge-120-signal-lab
002 create_directory assets
003 create_directory checks
004 create_directory data
005 create_directory docs
006 create_directory scripts
007 create_directory styles
008 create_file index.html
009 create_file README.md
010 create_file PROJECT_STATE.md
011 create_file manifest.webmanifest
012 create_file service-worker.js
013 create_file LICENSE.txt
014 create_file assets/logo.svg
015 create_file assets/grid.svg
016 create_file assets/icon.svg
017 create_file checks/checklist.md
018 create_file checks/expected-ui.md
019 create_file checks/manual-test.md
020 create_file data/copy.json
021 create_file data/schema.json
022 create_file data/seed.json
023 create_file docs/architecture.md
024 create_file docs/decisions.md
025 create_file docs/usage.md
026 create_file docs/verification.md
027 create_file scripts/actions.js
028 create_file scripts/app.js
029 create_file scripts/benchmark.js
030 create_file scripts/metrics.js
031 create_file scripts/render.js
032 create_file scripts/state.js
033 create_file scripts/storage.js
034 create_file scripts/validation.js
035 create_file styles/accessibility.css
036 create_file styles/components.css
037 create_file styles/layout.css
038 create_file styles/reset.css
039 create_file styles/responsive.css
040 create_file styles/tokens.css
041 read_file index.html
042 read_file README.md
043 read_file PROJECT_STATE.md
044 read_file manifest.webmanifest
045 read_file service-worker.js
046 read_file LICENSE.txt
047 read_file assets/logo.svg
048 read_file assets/grid.svg
049 read_file assets/icon.svg
050 read_file checks/checklist.md
051 read_file checks/expected-ui.md
052 read_file checks/manual-test.md
053 read_file data/copy.json
054 read_file data/schema.json
055 read_file data/seed.json
056 read_file docs/architecture.md
057 read_file docs/decisions.md
058 read_file docs/usage.md
059 read_file docs/verification.md
060 read_file scripts/actions.js
061 read_file scripts/app.js
062 read_file scripts/benchmark.js
063 read_file scripts/metrics.js
064 read_file scripts/render.js
065 read_file scripts/state.js
066 read_file scripts/storage.js
067 read_file scripts/validation.js
068 read_file styles/accessibility.css
069 read_file styles/components.css
070 read_file styles/layout.css
071 read_file styles/reset.css
072 read_file styles/responsive.css
073 read_file styles/tokens.css
074 write_file index.html
075 write_file README.md
076 write_file manifest.webmanifest
077 write_file service-worker.js
078 write_file styles/accessibility.css
079 write_file styles/components.css
080 write_file styles/layout.css
081 write_file styles/reset.css
082 write_file styles/responsive.css
083 write_file styles/tokens.css
084 write_file scripts/actions.js
085 write_file scripts/app.js
086 write_file scripts/benchmark.js
087 write_file scripts/metrics.js
088 write_file scripts/render.js
089 write_file scripts/state.js
090 write_file scripts/storage.js
091 write_file scripts/validation.js
092 write_file data/copy.json
093 write_file docs/architecture.md
094 write_file docs/usage.md
095 write_file docs/verification.md
096 write_file PROJECT_STATE.md
097 read_file index.html
098 read_file README.md
099 read_file manifest.webmanifest
100 read_file service-worker.js
101 read_file assets/logo.svg
102 read_file styles/accessibility.css
103 read_file styles/components.css
104 read_file styles/layout.css
105 read_file styles/reset.css
106 read_file styles/responsive.css
107 read_file styles/tokens.css
108 read_file scripts/actions.js
109 read_file scripts/app.js
110 read_file scripts/benchmark.js
111 read_file scripts/metrics.js
112 read_file scripts/render.js
113 read_file scripts/state.js
114 read_file scripts/storage.js
115 read_file scripts/validation.js
116 read_file data/copy.json
117 read_file docs/architecture.md
118 read_file docs/usage.md
119 read_file docs/verification.md
120 read_file PROJECT_STATE.md
```

## Controller acceptance rubric

The run passes only when all of these are true:

- one user click starts the run and no routine Resume click is needed;
- Work Session displays the original user command, every preserved Core message, and
  System Lens action cards numbered continuously from `1/120` through `120/120`;
- the action order exactly matches the manifest;
- no raw controller tag appears in Chat or Work Session;
- no completed message disappears when an `[INFO]`, `[ACTION]`, or System Lens node appears;
- scrolling upward remains under user control while generation continues;
- the mission remains scoped to `forge-120-signal-lab`;
- `completedActions` ends at exactly 120 and never reaches 121;
- no action uses execution, network, install, delete, an absolute path, or `..`;
- every write stays within the 1 MiB aggregate limit and creates a pre-write snapshot;
- the final response is a durable Core message after action 120, not a transient stream;
- closing and reopening AniCloudAI still shows the complete mission transcript and checkpoint.

Any missing number, repeated action, invisible failure, erased message, scope escape, false
execution claim, or extra click is a failed controller run even if the files look plausible.

## Manual product gate after `[MISSION_COMPLETE]`

This gate is intentionally separate from the 120 controller actions.

1. Copy or serve `forge-120-signal-lab` with a normal local static server when execution
   becomes available. Opening `file://` alone does not prove service-worker behavior.
2. Confirm zero console errors and zero remote network requests.
3. Add, edit, archive, search, and filter cards using touch, mouse, and keyboard.
4. Reload and confirm local persistence.
5. Export JSON, change state, import the valid export, and verify restoration.
6. Import malformed JSON and confirm the current state remains intact with a visible error.
7. Enable reduced motion and verify animation reduction.
8. Run the built-in self-check and inspect every PASS/FAIL result rather than accepting a
   single aggregate badge.
9. Test narrow phone portrait, phone landscape, half-width desktop, and full desktop.
10. Record screenshots, console output, action count, APK commit, and device route.

The product gate passes only with observed browser evidence. Until then, the correct verdict
is: **controller endurance proven; runtime functionality pending**.
