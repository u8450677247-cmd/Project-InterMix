# LIBRARIAN-01 rollback

Code rollback and data restore are separate operations. Never downgrade or overwrite the
live continuity database merely because application code is rolled back.

## Before rollback

1. Stop the supervised service with `sv down intermix-librarian`.
2. Create and verify a local snapshot plus manifest.
3. Record `intermix-librarian health`, the current commit and the snapshot manifest hash.
4. Preserve the token locally with mode 600; do not place it in the code archive.

## Preferred installed-release rollback

Use the existing release swapper:

```bash
intermix-rollback --list
intermix-rollback
```

It swaps code releases while leaving memory/provider data intact. Leave the Librarian
service down if the selected code predates the v0.3 reader. Additive v0.3 tables do not
modify or delete the established memory tables.

## Git checkout rollback

For a clean development checkout, return to the reviewed integration base in a separate
branch or worktree. Do not use `git reset --hard`, and do not delete the v0.3 tables.
The pre-change commit is:

`329eed769c441c6687078c9b6cb4c032aa80f882`

## Data recovery

Use `intermix-librarian restore` with a trusted manifest hash. Restore always writes a
new destination and refuses an existing path. Run quick-check, foreign-key verification,
count comparison and a duplicate replay test on the restored copy before changing the
runtime database pointer.

Do not copy a NAS database over the live file, restore a database while the service is
running, delete WAL files manually, or infer trust from archive filenames alone.

## Re-enable

Re-enable only after the selected code can read the current schema and health reports a
valid database. Start with loopback, verify one authenticated health request, then restore
the reviewed transport boundary.
