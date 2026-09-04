---
name: fix-command-actor-gate-design-position
description: Where the design states its position on the empty author allowlist, and what the /fix round-1 security review (2026-09-04) raised about reusing it for a push-capable command
metadata:
  type: project
---

The M2 `/fix` command reuses the review's per-provider author allowlist, whose empty default means
"everyone" (`IntegrationSaga.authorAllowed`). Round 1 of `m2-t23-fix-command` (2026-09-04, PR #119,
commits 85c1398 + 13ce642) raised this as HIGH: deny-by-default was recommended for `/fix` because
its dispatch (next slice) runs an agent and pushes a branch as the machine account.

**Why:** the design already takes both sides in different documents, and a round-2 reviewer needs
both pointers rather than re-deriving them:
- `docs/factory/ROADMAP.md` M2 section (~L278-284): empty allowlist = "review everyone",
  *deliberately*; the operator's override is observe mode. Written for `/review` (one spend-capped call).
- `docs/factory/AUTONOMY.md` Rule 3 (~L143-156): the factory's actor allowlist is **per work source,
  not the SCM author allowlist**, and names the drive-by-contributor threat explicitly.
- `docs/factory/ROADMAP.md` ~L345-348 admits M2 "widens its trigger surface" to "any allowlisted
  author" and carries it deliberately.

**How to apply:** in any later round on `/fix` or its dispatch, check the disposition of that HIGH
first (`.claude/reviews/global/` state file for the feature). Also carried forward from round 1:
`ManualCommandReceived.args` is attacker-typed and must never reach the agent prompt unfenced;
dispatch idempotency must key on `commentId`, not `(reviewId, threadRef)`; `ManualCommandReceived`
carries no `providerType`, so a workspace registered on two SCMs is one review for `/fix` too.
