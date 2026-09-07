# `/fix` cannot find its finding when typed two replies deep on Bitbucket

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../pipeline/IntegrationSaga.java` (`requestFix`); `spire-orchestrator/.../readmodel/ReviewThreadView.java` (`rootOf`) |
| Found during | M2 task 2+3 review (the `/fix` command path), code-review lens |
| Date | 2026-09-04 |

## Issue

`rootOf` maps a comment ref back to its conversation root using `review_thread`, and **only comments
the bot posted ever get a row there**. Its own javadoc records the consequence and calls it
*"harmless for the anchor"*:

> on a parent-threaded SCM, a `/finding` typed as a reply to another human's reply (not to anything
> the bot said) resolves to no row at all and this returns that reply's own id as its root

It is harmless for an anchor. It is not harmless for `/fix`, which uses the root to find the finding.

**Reachable on Bitbucket only**, because it alone threads by immediate parent:

1. The bot posts the finding comment, id 900 → `review_finding.thread_ref = '900'`.
2. Alice replies "is this really a problem?" — `parent.id = 900`, so her reply is attributed to the
   thread. **No row is written for Alice's own comment id 901.**
3. Bob replies to *Alice* with `/fix` → the ingress reads `parent.id = 901`.
4. `rootOf(901)` finds no row and returns 901 unchanged.
5. `findByThread(reviewId, "901")` finds nothing, and `/fix` is refused.

GitHub is immune — `in_reply_to_id` always names the thread root. GitLab is immune — `discussion_id`
is the thread. This is the cross-provider divergence class this repository has been bitten by
repeatedly, and the ref being *carried* by all three (which the parity test asserts) does not mean it
means the same thing on all three.

## Risks

Medium, and bounded. The natural way to use `/fix` is to reply to the review comment itself, which
works everywhere. The failure needs a conversation that has already gone two humans deep.

**The false-claim half is already fixed.** The refusal used to read *"no finding on this thread"* —
an assertion about the reader's repository that is untrue while the finding comment sits visibly a
few comments up, and precisely the harm `findByThread`'s throw-rather-than-empty posture exists to
prevent. It now says what it could not do (*"I could not match this thread to a finding — reply
directly to the review comment the finding was posted on"*), which is honest on all three providers
and actionable on the one where it fires. What remains is the functional gap.

## Suggested Solutions

1. **Fall back to the thread's recorded LOCATION.** Step 2 above already wrote `(path, line)` via
   `markThreadLocation`, and `ReviewThreadView.locationOf` already exposes it; a `findByLocation`
   query on `FindingProjection` mirrors `findByThread` in about fifteen lines. This is the same
   thread-rule-then-location-rule ladder `FindingVerdicts` uses, for the same reason.
   **Not done in the round that found it, deliberately:** a location can host several findings across
   rounds, so "which one does `/fix` target?" is a targeting decision that wants its own design
   rather than a bolt-on inside a review fix. That design is the work here.
2. **Write a `review_thread` row for every comment the ingress sees**, not only the bot's. Closes it
   at the source and makes `rootOf` mean what its name says on every provider — but it turns a table
   of the bot's own threads into a mirror of the pull request's whole conversation, which is a
   storage and privacy decision (DATA-MODEL §5) rather than a lookup fix.
3. Leave it. Defensible while the refusal stays honest about what it could not do, and while nobody
   reports hitting it. It becomes indefensible the moment `/fix` is documented to an operator as
   working "on the finding's thread" without the Bitbucket caveat.
