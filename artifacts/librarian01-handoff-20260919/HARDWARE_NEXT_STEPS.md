# LIBRARIAN-01 hardware next steps

These are controlled operator flights. Do not combine them with bootloader, partition,
ROM, signing or battery-removal work.

## 1. Gate on GitHub CI

1. Require the Python test workflow and Android foundation workflow to pass on the PR.
2. Review the manifest diff: the only new Android permission is
   `android.permission.ACCESS_NETWORK_STATE`.
3. Review `LibrarianNetworkSentinel.kt` for the intended injected endpoint-probe boundary.
4. Do not enable the unwired sentinel merely to prove compilation.

## 2. Prepare the Redmi authority

1. Keep the installed battery in place and record its condition before the flight.
2. Update a clean checkout to the reviewed implementation commit.
3. Install Termux `termux-services`; confirm `sv` and `svlogd` resolve.
4. Run `tools/install_librarian_service.sh --dry-run` and inspect every resolved path.
5. Run the installer without `--enable`; confirm the service remains down.
6. Initialize the lattice. If legacy projection is desired, add `--import-existing` only
   after the migration backup and manifest have been inspected.
7. Verify the token is mode 600 and never copy it to the repository or NAS.
8. Run `intermix-librarian health`; preserve the JSON as flight evidence.
9. Start on loopback. Use a reviewed HTTPS proxy or an authenticated encrypted overlay
   before allowing a remote Pixel connection.

## 3. Exercise the Pixel cortex

1. Register and authorize only `cortex-primary` for the service token.
2. Test Wi-Fi/LAN first with one event, one evidence-backed memory proposal and one
   bounded context retrieval.
3. Disable the route and confirm the Cortex outbox retains events while Redmi health
   reports `NO_CORTEX` rather than losing authority state.
4. Restore the route and confirm exact replay drains without duplicate events or atoms.
5. Test WAN only inside the reviewed encrypted overlay. Treat LTE as a route property;
   do not assume the Pixel owns or exposes SIM hardware.

## 4. Exercise the DS215j archive

1. Mount a dedicated, non-executable archive path with least privilege.
2. Confirm no live `.db`, `-wal` or `-shm` path points at that mount.
3. Create a local snapshot first and record the manifest SHA-256.
4. Replicate the immutable snapshot and manifest to the NAS.
5. Unmount the NAS, request another snapshot and confirm local ingest continues with a
   retryable replication row.
6. Remount, retry and compare size plus SHA-256 before the atomic final name appears.
7. Restore the archived pair to a new local path, run integrity checks, then replay the
   original event and confirm cardinality remains unchanged.

## 5. Collect the 4 GiB envelope evidence

Record at minimum:

- idle and peak resident memory;
- free RAM before/after 1,000-event replay;
- battery percentage, charging state and battery temperature;
- thermal limit behavior under queued work;
- database, WAL and snapshot sizes;
- service restart and full-device reboot recovery time;
- 30-minute offline Cortex and offline NAS behavior;
- queue depth and recovery after routes return.

Stop the flight on integrity failure, repeated process death, uncontrolled heat, token
exposure, unexpected public binding or any attempt to place the live database on NAS.
