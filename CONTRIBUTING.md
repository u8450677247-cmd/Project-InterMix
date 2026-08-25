# Contributing to Project Intermix

Project Intermix grows through measured evidence. A useful report is more
valuable than an unsupported compatibility claim.

## Before contributing

1. Read the [architecture](docs/ARCHITECTURE.md), [device matrix](docs/DEVICE_MATRIX.md),
   and [security policy](SECURITY.md).
2. Search existing issues before opening another.
3. Use synthetic conversations, memories, workspaces, credentials, and logs.
4. Keep changes deterministic around permissions, claims, deletion, secrets,
   and sensitive memory.

By participating, you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Development setup

The controller and memory tests do not require model weights or a GPU:

```bash
python -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m unittest discover -s tests -v
python tools/public_release_audit.py .
```

LiteRT-LM may be unavailable on a CI host. Tests mock the native inference
surface; device inference belongs in a separately labeled hardware report.

## Pull requests

- Keep one behavioral change per pull request.
- Add or update a deterministic test.
- Explain the safety and memory impact.
- Preserve fail-closed behavior.
- Do not weaken workspace path checks, deletion review, evidence validation,
  numeric integrity, context ceilings, or provider-secret isolation.
- Run the full test and public-release audit commands.
- Update relevant documentation and the changelog.

Maintainers may request a synthetic reproduction or separate device evidence.
Generated prose from a model is not a substitute for a passing test.

## Device reports

Start with:

```bash
intermix-doctor --json > intermix-device-report.json
```

Report cold initialization, first-token time, warm first-token time, total
generation time, prompt tokens, model/context, `MemAvailable` before and after,
thermal conditions, backend, success/failure, and any Signal 9 event. State
which features you actually exercised. Do not mark an inferred device as
verified.

Optional cooling observations must name the ambient conditions and remain
clearly separate from compatibility requirements.

## Provider adapters

New adapters must:

- use an official documented API or a source permitted by its terms;
- declare authority, freshness, and claim coverage;
- use bounded timeouts, result counts, and cooldowns;
- never expose the credential to the model or UI;
- include relevance and failure tests; and
- fail without falling back to invented facts.

Proxy rotation, CAPTCHA solving, and rate-limit evasion are out of scope.

## Memory changes

Use synthetic records. Sensitive-memory changes must remain explicit-only,
query-gated, inspectable, forgettable, and non-diagnostic. Schema changes need
an idempotent migration plus a rollback test.

## License

Unless explicitly stated otherwise, contributions are submitted under the
Apache License 2.0 in this repository.
