# Two Galaxy XCover 5 Librarian pilot

Status: software staging, 2026-09-24. Neither phone has passed an Intermix
on-device compatibility or network-security flight. This changes the original
Redmi hardware proposal without changing the single-writer continuity rules.

## Chosen division of work

| Node | Current pilot responsibility | Does not do |
| --- | --- | --- |
| XCover 5 A | One local SQLite/WAL authority, FTS, event validation, bounded context, snapshots, low-duty scheduler | Resident Gemma, exposed HTTP, direct NAS SQLite writes |
| XCover 5 B | Separately stored, verified standby snapshot and restore test | Concurrent authority, automatic promotion or independent writes to A's database |
| Pixel 10 Pro | E4B chat/reasoning and existing local outbox; prospective on-demand E2B/embedding worker | Depend on either XCover for basic chat; silently switch model or grant the model database authority |
| DS215j | Verified snapshot and release artifact archive | Live WAL, update-safety decisions, public DSM/SMB listener |

Neither XCover needs a GPU for SQLite, FTS, SHA-256, or indexing. Any Gemma or
embedding job belongs to an optional disposable worker on the Pixel, with one
resident model at a time. An Android admin button could later request one bounded
job, show queue/progress/cancel, and leave the XCover in charge of validating a
proposed result. **That native worker and button are not implemented.** The
existing `preferred_role=cortex` queue is preparation, not a working remote
GPU lease. Bench the exact Pixel E2B model, memory headroom, 8K context, and
thermal behavior before enabling offload.

## Hardware and network gates

Samsung lists the 2021 XCover 5 as an Exynos 850 device with 4 GB physical RAM
and 64 GB internal storage. The ordinary full Intermix installer requires a
model and deliberately rejects the sub-8 GB tier. Use the separate model-free
install path below; do not force the full APK/Termux model install on this phone.

Samsung's SM-G525F firmware page lists an Android 14 security build dated
2026-05-26 with a 2026-05-05 patch. Availability varies by region/operator.
Samsung's Austrian Enterprise update chart also marks XCover 5 as end of life
in a footnote, despite showing a Q4 2026 timeline; do not assume future patches.
Before any 24/7 pilot, record the exact SKU, installed patch date, whether a
newer signed update is offered, free storage, battery/thermal readings, and
whether Termux service restarts after reboot. Do not advertise an XCover as a
supported or Internet-facing node based on a desktop-only test. Keep its battery
installed and screen off during idle service.

Official references:

- [Samsung XCover 5 specifications](https://news.samsung.com/de/samsung-galaxy-xcover-5)
- [Samsung SM-G525F firmware history](https://doc.samsungmobile.com/SM-G525F/018993210609/deu.html)
- [Samsung Austria Enterprise support chart](https://images.samsung.com/is/content/samsung/assets/at/downloads/files/Enterprise_Edition_Update_2025.pdf)

No public IP, port forwarding, wildcard bind, or direct bearer token over LAN.
The Python HTTP service binds to `127.0.0.1`. A remote Pixel needs a separately
reviewed authenticated TLS/overlay proxy on the host, plus the existing token
and registered node ID; this repository does not configure the proxy. Until
then, perform only local health and snapshot flights on each XCover.

## Model-free local install on XCover 5 A

From a verified checkout in Termux with Python 3 available:

```bash
bash tools/install_librarian_only.sh --dry-run --node-id librarian-xcover-a
bash tools/install_librarian_only.sh --node-id librarian-xcover-a
"$HOME/project-intermix-librarian/bin/intermix-librarian" init
"$HOME/project-intermix-librarian/bin/intermix-librarian" health
"$HOME/project-intermix-librarian/bin/intermix-librarian" snapshot \
  --reason "XCover 5 local pilot"
```

The installer copies only the standard-library Librarian runtime into private
Termux storage. It does not copy a model, install providers, start a listener,
create a service token, enable a boot service, or touch an existing Intermix
installation. It refuses to overwrite its target directory. It also does not
replicate a snapshot to the NAS until the archive mount is separately reviewed.
The default `init` creates an empty authority; it does not transfer old Redmi
or Pixel conversations.

To stage a disabled supervisor later, export
`INTERMIX_PROJECT_DIR="$HOME/project-intermix-librarian"`, prepend
`"$INTERMIX_PROJECT_DIR/bin"` to `PATH`, install `termux-services`, and run
`tools/install_librarian_service.sh --dry-run` followed by the installer without
`--enable`. The installer reads the pilot's recorded node ID and refuses a
different explicit ID. Do not enable a remote connection until the loopback proxy, token
handling, and device patch gate have been reviewed.

## XCover 5 B and recovery

Install the same model-free files on the second phone with
`--node-id librarian-xcover-b`, but keep its service down. Transfer a **verified
snapshot plus its manifest and independently recorded manifest SHA-256** through
the reviewed archive path. Use `intermix-librarian restore` to a new, private
path on B and run SQLite integrity checks there. Never share an active SQLite
file or WAL between phones.

The current code has verified snapshot/restore, not automated replica catch-up
or split-brain prevention. B must not become writable merely because A is
temporarily unreachable. Promotion requires an explicit recovery procedure:
freeze writes to A, verify a trusted snapshot and per-origin event watermarks,
reconcile the Pixel outbox, restore into a new path on B, and authorize B as the
sole writer. Until that is implemented and tested after power/network loss, B
is a cold standby and the Pixel remains usable with its durable local outbox.

## Release gate

First pass: both XCover devices complete independent local cold-start, health,
snapshot, restore-to-new-path, low-memory, thermal, and reboot trials without a
model or network listener. The current PC test suite proves the software path,
not Samsung hardware compatibility. Then validate one secured Pixel-to-A event
round trip and a deliberate A-offline outbox replay. Only after those results
should the optional Pixel GPU worker or native admin control be considered.
