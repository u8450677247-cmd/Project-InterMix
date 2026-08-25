# Installation and recovery

The public alpha installer is intentionally conservative. It checks capability,
runs tests, snapshots an existing install, and activates a versioned release
without loading the model.

## Supported reference path

- current official Termux build;
- Android on `aarch64`;
- Python 3.11 or newer;
- SQLite with FTS5;
- LiteRT-LM 0.16.1 reference stack;
- a separately obtained compatible `.litertlm` model; and
- at least 8 GiB physical RAM and 6 GiB free storage.

Twelve GiB or more is strongly recommended for the E4B/8K profile. A passing
resource check is not proof of device compatibility.

## Fresh install

```bash
termux-setup-storage
cd Project_Intermix_Public_v1.4.1-alpha.1
bash install.sh
```

The installer discovers models under the selected project and Termux shared
storage. It never downloads weights or requests provider keys.

## Non-interactive install

```bash
bash install.sh \
  --non-interactive \
  --model "$HOME/models/model.litertlm" \
  --user-name "Operator" \
  --assistant-name "Intermix Core" \
  --workspace-dir "$HOME/storage/downloads/intermix_workspace" \
  --context-tokens 8000
```

Use `--dry-run` first in automation. `--force` only bypasses installer policy;
it cannot make an unsupported runtime safe or functional.

## What is installed

```text
~/project-intermix/
├── current -> releases/1.4.1-alpha.1
├── previous -> previous active release
├── releases/
├── memory/                  private, never source-controlled
├── models/                  local weights and compiled caches
└── archive/                 private rollback snapshots

~/.config/intermix/
├── config.json              mode 600, non-secret paths and labels
└── providers.env            mode 600, optional credentials
```

The configured workspace may live elsewhere. It is never copied into a release.

## First launch and latency

```bash
intermix
```

The first GPU load can compile or hydrate several gigabytes of accelerator
artifacts. Distinguish these stages when reporting performance:

1. prompt assembly;
2. cold engine initialization;
3. prompt prefill;
4. first visible token;
5. generation; and
6. post-generation validation/memory processing.

Warm turns reuse the engine until explicit unload, memory pressure, or the idle
policy releases it. Android may terminate a process under extreme pressure;
SQLite and mission state should survive, but an in-flight answer does not.

## Upgrade safety

The installer:

- refuses to proceed while the cockpit is running;
- uses SQLite's backup API for a live database snapshot;
- stages the next release in a temporary directory;
- moves it into a versioned release directory; and
- swaps the `current` symlink only after staging succeeds.

Legacy conversation migration is dry-run only. Review counts before executing
`memory_migrate.py --apply` manually.

## Rollback

```bash
intermix-rollback
```

Rollback changes the active version; it does not erase current memory. Preserve
the installer's archive before testing schema-sensitive downgrades.

## Share-safe diagnostics

```bash
intermix-doctor --json > intermix-device-report.json
```

Inspect the JSON before attaching it. The tool avoids personal identifiers and
does not load the model, but only the user can decide whether a report is safe
for their context.
