# A run's token usage is received and dropped, and no run writes to the charge ledger

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/FactoryRunProjection.java` (`finished` ignores `RunFinished.tokenUsage`), `spire-orchestrator/.../llm/CallRefs.java` (`forRun` exists and is called by nothing), `llm_charge` (`subject_kind = 'RUN'`, `capability`, `credential_ref` from V40, written by nothing) |
| Found during | PR #95 four-lens review, round 1 (rules-compliance, orchestrator side); the ledger side was already an open item on the PR |
| Date | 2026-09-02 |

## Issue

Task 7 put the harness's usage report on the wire precisely so it would not be lost, and Task 9
gave `llm_charge` a neutral subject so a run could be charged. The projection reads `RunFinished`
and writes status, ref and blocked paths; the usage map is discarded, and nothing writes a
`subject_kind = 'RUN'` row. The ADR-023 rule that unknown is not zero is honoured on the wire (a null
map) and then has nothing to be honoured against.

## Risks

- M0 spends real money that appears nowhere in the deployment's spend window, while the spend gate
  now refuses runs against that same window — runs are gated by a total they never contribute to.
- Pricing a run needs the model's rates, which the run's model is not guaranteed to have in the
  catalogue; the same unpriceable-model refusal the review path has would be needed at dispatch.

## Suggested Solutions

- On `RunFinished`, write one `llm_charge` row per token type with `subject_kind = 'RUN'`,
  `call_ref = CallRefs.forRun(runId, attempt, seq)`, priced by `LlmModelPricer`; `UNKNOWN` when the
  map is null, never zero.
- Store the raw usage map on `factory_run` as well, so the run's page can show it before pricing
  exists.
