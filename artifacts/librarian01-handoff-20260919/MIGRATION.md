# LIBRARIAN-01 migration

The migration is additive and transactionally upgrades the Continuity Lattice to
`0.3.0-ordinary-service`. Existing sessions, messages, memories, revisions and FTS data
remain in place.

## Preconditions

- Use the reviewed implementation commit.
- Ensure the live database is on local device storage.
- Ensure enough local space for the database, migration backup and first snapshot.
- Stop any process that bypasses the normal single-writer path.
- Keep bearer tokens and NAS credentials outside the repository.

## Initialize

```bash
intermix-librarian init \
  --token-file /private/local/path/librarian.token \
  --create-token
```

When an older lattice exists, `init` first creates a mode-600 consistent backup and
canonical manifest, verifies them, and only then applies the upgrade. Other commands
refuse to bypass this gate. Repeating `init` at the current version is idempotent.

## Optional legacy projection

```bash
intermix-librarian init --import-existing
```

This projects established messages and accepted memories into globally identified
events/atoms. It is deterministic and idempotent; originals remain authoritative in
their existing tables and are not deleted.

## Acceptance checks

1. `intermix-librarian health` reports schema `0.3.0-ordinary-service` and DB integrity.
2. A unique event commits once.
3. Replaying the same envelope returns duplicate status without changing counts.
4. An evidence-backed memory appears in bounded context with provenance IDs.
5. A local snapshot and canonical manifest verify.
6. Archive replication verifies byte count and SHA-256.
7. Restore to a new path passes integrity, count and duplicate-replay checks.

## Failure behavior

- Migration failure leaves the verified pre-migration backup available.
- A malformed delta rolls back its tentative derived records.
- NAS failure queues replication and does not block local ingest.
- Cortex failure queues client events and does not transfer authority.
- Unknown resource/thermal state fails closed for jobs that require that observation.
