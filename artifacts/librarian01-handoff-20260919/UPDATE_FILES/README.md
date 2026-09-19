# Applying the LIBRARIAN-01 update

The Git commit is the atomic update object. It contains every schema, script,
configuration, test and document required by the software milestone; no external patch
payload is needed.

From a clean checkout at the exact integration base, run:

```bash
artifacts/librarian01-handoff-20260919/UPDATE_FILES/apply.sh
artifacts/librarian01-handoff-20260919/UPDATE_FILES/verify.sh
```

`apply.sh` refuses a dirty checkout or an unexpected base. It fetches the published
feature branch and fast-forwards only to the immutable implementation commit. It does
not initialize a database, start a service or touch a device.

For a deployment migration, follow `../MIGRATION.md` only after review and successful
verification.
