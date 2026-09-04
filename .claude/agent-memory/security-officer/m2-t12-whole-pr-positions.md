---
name: m2-t12-whole-pr-positions
description: Positions raised on the M2 whole-PR security round (T12, PR #119, 2026-09-04) — the /fix claim keyed on a bare forge comment id, the run worker shipped with the owner DB role + shared keyset, username-match allowlist gating a push, finding plaintext reaching dlq_entry via ExecuteRun
metadata:
  type: project
---

Raised on the T12 whole-PR round of M2 (`feat/factory-m2-deliver`, ref HEAD, 2026-09-04). Check
`.claude/reviews/global/` for the disposition before re-raising any of them.

- **V56's claim index is `factory_run(comment_id)` alone**, and `fixRunFor(commentId)` reads it
  without `review_id`. Every ingress passes the forge's numeric comment/note id as a bare string,
  so two providers (or two self-hosted GitLabs) collide: a later `/fix` is refused naming another
  workspace's run id, and the race backstop dead-letters a legitimate dispatch after the pool slot
  was stamped. Proposed key: `(review_id, comment_id)`.
- **`deploy/compose*.yml` gives `run-worker` `POSTGRES_USER` (the schema owner) and
  `SPIRE_ENCRYPTION_KEYSET`** while its own SQL touches only the `runworker` schema. With the socket
  mounted this is moot (root on host); after the README's own recommended mitigation (remote
  daemon, drop the mount) it is the residual — the worker can decrypt every provider secret and
  harness key. The gateway already has a scoped role (`GATEWAY_POSTGRES_USER`) for the same reason.
- `authorAllowed` matches `username` OR `providerUserId` case-insensitively; a released/renamed
  handle passes the gate that now authorises a push as the machine account. Position: match
  `/fix` on `providerUserId` only, or require ids in the list. Related design deferral in
  [[fix-command-actor-gate-design-position]].
- `ExecuteRun.prompt` carries the DECRYPTED finding text (V36 encrypts it at rest); the run worker
  dead-letters `cs.run-commands` to `cs.dlq`, whose `dlq_entry.payload` is raw TEXT. Admin-only
  surface, ADR-014 covers the bus; filed LOW as the first encrypted-at-rest value copied into a
  command.

**Why:** these are seam findings no per-slice round could see (the claim key vs. multi-provider
deployments; compose env vs. the README's mitigation), and they are cheap to re-derive wrongly.

**How to apply:** on M3+ rounds, verify each against the current code before citing — the claim
index and the compose env are one-line changes that may already have landed.
