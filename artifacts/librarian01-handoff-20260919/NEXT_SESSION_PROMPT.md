# Next-session prompt

Continue Project Intermix LIBRARIAN-01 from branch
`feature/intermix-librarian-continuity-20260919`. Treat implementation commit
`194a63b5748b2a0ae9b9e0c7982a676100379b1b` and the handoff files beside this prompt as
the software baseline. First inspect the PR checks and review feedback; do not rewrite
green continuity code without specific failure evidence.

The next objective is the controlled hardware flight:

1. Require Python and Android GitHub checks to pass.
2. Deploy the service to the battery-installed Redmi Note 9 Pro through the documented
   Termux runit path, initially down and loopback-only.
3. Capture real 4 GiB RAM, thermal, battery, restart and reboot evidence.
4. Connect the Pixel/Cortex over LAN/Wi-Fi, then over a reviewed encrypted WAN overlay;
   do not assume Pixel SIM ownership or add telephony control.
5. Exercise Cortex outage/outbox recovery and prove duplicate replay changes no counts.
6. Mount the DS215j only as an immutable snapshot/release archive, exercise outage and
   retry, verify SHA-256, and restore into a new local path.
7. Append raw commands, timestamps, hashes, health JSON and outcomes to a new flight
   report. Open a follow-up PR; do not merge automatically.

Preserve these constraints: Redmi is authority; Pixel is replaceable compute; NAS never
hosts live SQLite/WAL; raw events are immutable; derived memory requires evidence;
contradictions become friction; deterministic validators commit; no chain-of-thought;
embeddings remain optional; no bootloader/partition/ROM/signing/key action.
