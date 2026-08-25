# `/finding`, like `/review` before it, ignores observe-only mode

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-orchestrator/.../pipeline/IntegrationSaga.java` (`onManualCommand`, `triggerManualReview`, `raiseConversationFinding`) |
| Found during | Final fix wave for `feat/prompt-scope-and-conversation-findings` (whole-branch review) |
| Date | 2026-08-24 |

## Issue

`policy.observeOnly()` is consulted in exactly one place in `IntegrationSaga`: `onPullRequestEvent`,
which is what keeps a PR open/update event to "register only, no diff/LLM/comments" (the log line in
`ReviewPolicy.describe`). Every `/command` PR comment reaches `onManualCommand` instead, which never
checks it.

`/review` already had this hole — an operator who flips a deployment to observe-only can still force a
paid re-review by commenting `/review`, and the resulting comments post for real. This branch adds
`/finding`, which widens the same hole from one path to three: a human can now also `/finding` a
concern in observe-only mode, and `IntegrationSaga` will write it to the read model
(`addConversationFinding`), advance the aggregate (`RaiseConversationFinding`), and post a live
confirmation reply on the PR — all under a mode whose stated contract is no comments at all.

`/finding` spends no LLM credential, so the ADR-016/spend-cap reasoning that might excuse `/review`
(it's the operator's own paid command) does not apply here: this is a real write and a real posted
comment with no cost gate anywhere near it, in a mode whose whole point is "look but don't touch."

## Risks

Low-to-moderate. Observe mode exists for an operator evaluating the deployment before turning it
loose on a repository — the failure mode is the tool posting into a PR (or silently mutating its own
read model / aggregate) during that trial, which is exactly the surprise observe mode exists to
prevent. Not urgent: it requires observe mode to be active AND an allowlisted author to type a
`/command`, and nothing about it is a security or spend hole (no LLM credential is brokered).

## Suggested Solutions

1. **Gate at the top of `onManualCommand`**, mirroring `onPullRequestEvent`'s own read of
   `policy.observeOnly()`, and refuse both `/review` and `/finding` the same way — a timeline note,
   no reply (matching the existing authorization-refusal posture, since a reply would confirm to a
   prober that the command exists). This is the fix that closes all three paths (`/review`,
   `/finding`, and any future `/command`) in one place, the same reasoning that put the author
   allowlist check ahead of the command switch rather than inside each branch.
2. **Decide the product question first.** Whether `/finding` (and `/review`) should work at all in
   observe mode is not obviously wrong the way a silent authorization bypass would be — an operator
   who explicitly types a command might reasonably expect it to work regardless of the passive
   default. That reading needs a product decision, not a unilateral fix, which is why this was filed
   as debt instead of patched in the same wave that found it.
3. Leave it. Defensible only as long as observe mode is documented as governing automatic triggers
   rather than explicit operator commands — which it currently is not.
