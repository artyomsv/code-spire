# `HarnessAdapter.command()` receives credentials it must never place in argv

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-harness/src/main/java/dev/codespire/harness/HarnessAdapter.java` (`command(HarnessInvocation)` — the invocation carries `credentials()`), `spire-harness/src/testFixtures/.../HarnessAdapterContract.java` (`aCredentialNeverReachesArgv`) |
| Found during | PR #95 four-lens reviews of Tasks 1 and 2 |
| Date | 2026-09-01 |

## Issue

`command(HarnessInvocation)` is handed the whole invocation, including `credentials()`. The one
thing it must never do is put any of that in argv, which is world-readable through
`/proc/<pid>/cmdline` and echoed by `docker inspect` — the field EXECUTION-LAYER.md requires a
credential to stay out of, and NFR-F3 restates. The SPI therefore makes the unsafe version the
natural one: an arm for a harness that accepts `--api-key <key>` is one obvious line away from
`List.of("harness", "--api-key", inv.credentials().get(...))`, and nothing objects.

`HarnessAdapterContract.aCredentialNeverReachesArgv` fails any arm that copies a credential value
into argv, and is mutation-verified. It does not cover a credential the adapter derives (base64,
`Bearer <tok>`, `https://x-access-token:<tok>@host`), one written into a config file whose path is
then argv, or argv builders outside the SPI.

## Risks

- Low today: the only shipped arm delivers its prompt on stdin and places nothing but flags in
  argv. It is a shape problem, not a live defect — until the second arm.

## Suggested Solutions

- Split the type so the unsafe version cannot be written: `command()` takes a credential-free view
  (`runId`, `prompt`, `workspacePath`, `model`, `wallClock`) and the credential map stays on the
  `environment()` path only.
