# `HarnessAdapter.command()` receives credentials it must never place in argv

**Criticality:** 4 (Low) — **Complexity:** 2 (Low)

## What

`command(HarnessInvocation)` is handed the whole invocation, including
`credentials()`. The one thing it must never do is put any of that in argv, which is
world-readable through `/proc/<pid>/cmdline` and echoed by `docker inspect` — the field
EXECUTION-LAYER.md §179-181 requires a credential to stay out of, and NFR-F3 restates.

The SPI therefore makes the unsafe version the natural one. An arm for a harness that accepts
`--api-key <key>` is one obvious line away from
`List.of("harness", "--api-key", inv.credentials().get("KEY"))`, and nothing objects.

## Current mitigation, and what it does not cover

`HarnessAdapterContract.aCredentialNeverReachesArgv` fails any arm that copies a credential
**value** into argv, and it is mutation-verified. It does not cover:

- a credential the adapter **derives** rather than copies — base64, `Bearer <tok>`, or a
  URL-embedded `https://x-access-token:<tok>@host`, which is the exact form EXECUTION-LAYER.md
  §212 forbids;
- a credential written into a **config file** whose path is then argv, or into a `-c key=value`
  override;
- every argv builder **outside** the SPI — the `RunSpec` at RUN-TOPOLOGY §311 assembles the
  container spec, and no adapter test sees it.

## The fix

Split the type so the unsafe version cannot be written: `command()` takes a credential-free view
(`runId`, `prompt`, `workspacePath`, `model`, `wallClock`) and the credential map stays on the
`environment()` path only. That removes the first two bullets by construction. The third needs
its own assertion at the spec-build site, which is Task 6.

Low rather than Medium because the only shipped arm delivers its prompt on stdin and places
nothing but flags in argv, and the contract test holds that. It is a shape problem, not a live
defect.

## Discovered

2026-09-01, during the Task 1 and Task 2 four-lens reviews of the M0 walking skeleton.
