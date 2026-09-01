# Every publisher failure is classified retryable

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java` (`interpret`, the publisher-failure branch hardcodes `retryable=true`), `spire-publisher/.../PublishCycle.java` (one `PUSH_FAILED` cause for transport faults and forge refusals alike) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer, worker side) |
| Date | 2026-09-02 |

## Issue

The publisher's cause string is passed through and `retryable` is always true. Three causes are
permanent: `PUBLISHER_MISCONFIGURED` (a bad branch or a credential in the URL — refused identically
on every attempt), a `PUSH_FAILED` caused by a forge ruleset, and a `BUNDLE_UNREADABLE` for a bundle
over the size cap. The sibling `BAD_COMMAND` branch in the same method already gets this right,
saying "retrying a malformed dispatch is a loop".

## Risks

- Nothing retries automatically yet, so today this only misinforms the read model. Once M1 adds a
  retry policy, a misconfigured branch would pay for a full agent run on every attempt.

## Suggested Solutions

- Classify by cause: `PUBLISHER_MISCONFIGURED` never retryable; have the publisher distinguish a
  transport failure from a forge refusal in its cause, since only the first is worth retrying.
