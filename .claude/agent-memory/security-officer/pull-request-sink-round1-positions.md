---
name: pull-request-sink-round1-positions
description: Positions taken in round 1 of m2-t67-pull-request-sink (PR #119) so round 2 stays consistent — error-string false-positive bound, role enforcement, BBQL quote, fence claim
metadata:
  type: project
---

Round 1 (2026-09-04) of the PullRequestSink review took these positions; re-verify against HEAD before repeating them.

- **Error-string false positive: none reachable with evidence, gap is structural.** All three match
  phrases contain a space; git refnames forbid spaces and the factory branch charset is
  `spire/[A-Za-z0-9._-]+` (DispatchRequestParser.REF_SEGMENT), so an echoed branch cannot match.
  The exception message also carries the POST path (workspace/slug), which likewise cannot hold a
  space on GH/BB and is URL-encoded on GL. What is missing is any status/JSON-structure gating —
  a 500-char HTML proxy page is matched the same as a 422 `errors[].message`. Raised MEDIUM.
- **Reviewer credential in `pullRequestSink`:** the consequence is NOT automatically "reviewed by
  nobody". `IntegrationSaga.onPullRequestEvent` has no bot-self-loop guard on PR authorship, only
  the allowlist gate (empty = everyone). Cheap enforcement: read `role` into `ScmProvider` (column
  exists since V44) and assert FACTORY in `ProviderClients.pullRequestSink`.
- **BBQL quote injection is real but latent**: refnames allow `"`; only the factory charset flows
  in today, but `/fix` reads `source_branch` from the webhook projection. Asked for refusal of
  `"`/`\` in the Bitbucket adapter.
- **Fence claim in FactoryPullRequestBody is accurate**: paths cannot carry `\n`/`\r`
  (PublishRepo.safe), so break-out needs a top-level file named exactly ```` ``` ````; the fence
  never bounded what the reviewer's model reads anyway. Asked for dynamic fence length only.
  The unfenced multi-line `**Task:**` line is the larger surface if T8 passes finding-derived text.

**Why:** the T8 caller does not exist yet; several findings are "shape the caller" notes rather than
defects in this slice, and round 2 should not re-escalate them if T8 lands with the guards.
**How to apply:** when T8 (the RunFinished → open PR consumer) is reviewed, check the role assert,
the allowlist-vs-factory-account gate (MARK has no reader), and how NothingToPropose is caught.
