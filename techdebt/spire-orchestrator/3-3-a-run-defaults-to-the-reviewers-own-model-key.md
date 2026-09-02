# A run defaults to the reviewer's own model key

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java` (`harnessCredentialSource`: the deployment's default LLM provider when no `llmProviderId` is named) |
| Found during | PR #95 four-lens review, round 2 (security-officer) |
| Date | 2026-09-02 |

## Issue

The harness credential is the key of the LLM provider named in the request, else the deployment's
default — the same key the review pipeline calls the model with. That key is then handed to the
agent container, which runs an untrusted model on an untrusted work item at `danger-full-access`,
so a prompt-injected agent can read it from its own environment and exfiltrate it. The review
path never gives the model its own key; the factory does by default.

## Risks

- One key compromise disables reviews and runs together, and a spend spike from a leaked key is
  indistinguishable in the ledger from legitimate factory use.

## Suggested Solutions

- ADR-031's credential pool (M1): a per-run, short-lived key minted for the agent, or at least a
  dedicated factory-role LLM provider the way `scm_provider.role` already separates the push
  identity — with `harnessCredentialSource` refusing the reviewer's default rather than falling
  back to it.
