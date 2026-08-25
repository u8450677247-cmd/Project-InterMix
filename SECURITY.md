# Security policy

Project Intermix is a developer preview that combines local model execution,
web retrieval, durable memory, and a bounded code workspace. Treat it as
security-sensitive software even when it never leaves your phone.

## Supported version

Only the latest tagged public alpha receives security fixes. Earlier private
bundles and untagged snapshots are unsupported.

## Report a vulnerability

Do not open a public issue for a vulnerability that could expose credentials,
private memory, workspace data, or allow commands outside the fixed workspace.
Use GitHub's private vulnerability-reporting flow for the repository. Include:

- affected version and commit;
- device, Android, Termux, Python, and LiteRT-LM versions;
- a minimal reproduction using synthetic data;
- expected and observed boundaries; and
- impact without including live keys, databases, transcripts, or user files.

If private reporting is unavailable, open a public issue containing only a
request for a private contact channel. Do not include exploit details.

## Trust boundaries

- Model output, web pages, search snippets, tool output, and imported files are
  untrusted data.
- The deterministic controller—not the model—authorizes persistence, display,
  provider access, and workspace actions.
- Automatic file operations are confined to the configured workspace.
- Deletion is review-only and revalidates the file hash immediately before use.
- Provider secrets belong in the mode-600 provider vault and are never model
  context, memory, diagnostics, screenshots, or issue content.
- Forced grounding fails closed when sufficiently relevant evidence is absent.
- Fresh public installs keep sensitive wellbeing capture disabled until the
  user explicitly opts in.

## Before sharing a diagnostic

Prefer `intermix-doctor --json`. Never attach `memory/`, `models/`, `archive/`,
`.shaders/`, provider files, audio output, workspaces, or raw transcripts. Run:

```bash
python tools/public_release_audit.py .
```

The audit is a guardrail, not a guarantee. Review every attachment manually.

## Scope exclusions

Project Intermix does not attempt to bypass provider rate limits, CAPTCHAs,
Android application boundaries, model licenses, or platform security controls.
Reports requesting such bypasses will be closed.
