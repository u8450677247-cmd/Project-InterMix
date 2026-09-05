# Privacy model

Project Intermix is local-first, not magically private. Local data remains
sensitive, provider requests leave the device, and a user can still disclose
information through screenshots, issues, backups, or workspace files.

## Local data classes

| Data | Default location | Policy |
|---|---|---|
| Conversations and memory | `memory/sovereign.db` | Private; never publish |
| Identity/lore | configured identity file | Private; prompt-visible locally |
| Missions and task state | SQLite and workspace control state | Private |
| Model weights and caches | `models/` | Separate license; never publish here |
| Provider credentials | mode-600 provider vault | Controller-only; never model-visible |
| Workspace files | configured fixed workspace | User-owned; scoped agent access |
| Voice requests/output | optional bridge directory | Local transient output; bounded archive |
| Device report | explicit user-generated file | Share-safe by design; review manually |

## Persona and Sanctuary

The persona profile records only explicit instructions about communication
style. Each change is appended to a local revision log and can be undone. It
must not silently convert mood, jokes, disclosures, or writing style into a
psychological diagnosis or immutable identity claim.

Termux Sanctuary is intentionally unavailable. `/sanctuary on` remains off and
does not increase raw-message retention because the current SQLite store is not
an authenticated encrypted vault. The planned APK boundary requires Android
Keystore-backed keys and biometric authentication before that label is valid.

## Sensitive wellbeing memory

Fresh public installs default capture to `off`. If explicitly enabled, the
controller may retain direct first-person self-reports such as “I feel anxious
today.” It must not infer a diagnosis, hidden condition, or protected trait.
Sensitive events are query-gated and carry a retention policy.

This feature is for continuity, not clinical assessment. It is not a medical
device and must never silently convert behavior, tone, browsing, or tool output
into a mental-health conclusion.

Users can inspect and remove state:

```text
/memory audit
/memory events
/memory event forget <id>
/memory event purge
/memory sensitive off
```

## Web grounding

When a question is classified as time-sensitive, an optimized query and the
minimum necessary context may be sent to configured providers. For a difficult
lookup, the controller may derive up to two additional query wordings from the
same user-supplied terms and send them in one bounded follow-up round when the
primary evidence is absent or uncorroborated. Full transcripts, durable memory,
workspace files, persona state, and provider keys are not provider inputs.

`/web plan …` shows planned wording without sending it. `/web last plan` shows
non-secret execution diagnostics such as query wording, provider names, request
counts, and rejected-result counts. It never displays provider credentials.

Provider privacy policies and retention terms still apply. Local-first does not
mean web requests are anonymous. Intermix does not rotate proxies, solve
CAPTCHAs, or evade rate limits.

## Diagnostics and community reports

Never publish:

- live databases, memory exports, or raw transcripts;
- provider vaults, shell history, `.env` files, or API responses containing keys;
- model files or compiled GPU/NPU caches;
- workspaces, audio, screenshots, or logs without manual review; or
- Android IDs, serials, hostnames, usernames, IP addresses, or absolute private paths.

Run `python tools/public_release_audit.py .` before packaging source. This catches
known hazards but does not replace human review.

## Deletion and backups

Deleting a memory from the active database does not erase independent Android,
cloud, filesystem, or rollback backups. Users are responsible for the lifecycle
of copies they create. Project Intermix does not remotely delete data.
