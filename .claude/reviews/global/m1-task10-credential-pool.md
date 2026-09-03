# Code Review State: global / m1-task10-credential-pool

Last reviewed: 2026-09-03
Rounds completed: 1

Covers commit `0c06be3` (Task 10 — the harness credential pool with rotation, FR-F12). Four lenses:
security-officer, code-reviewer, rules-compliance, qa.

**The round's headline: the feature's self-healing half does not exist, and three documents said it
did.** `CREDENTIAL_REJECTED` — the cause that retires a dead key — has no producer anywhere in the
shipped pipeline. Two lenses established it by grep rather than by reading: the harness tier's
`FailureCause` has no credential value, the publisher's vocabulary has none, and nothing aliases onto
it. A refused key surfaces as `MODEL_UNAVAILABLE`, which the feedback rule deliberately ignores, so
the pool hands the dead key out again on its next turn. That is verbatim the state V52's own header
calls *"how a pool quietly stops rotating while looking healthy"* — written in the change that
shipped it. The test could not see it because it constructs the wire string by hand.

**Not fixed by inventing a producer.** Matching a real provider's auth-error output requires observing
it, and inventing the pattern would be fabricating behaviour. Every claim is corrected instead, and
`spire-arch`'s `CredentialRefusalHasNoProducerTest` fails the build when a producer DOES appear, so
the documentation cannot go stale in the other direction either.

## Resolved (fixed in code; do not re-raise)

- [security/H-1, qa/6] **Neither exhaustion state has an automatic producer**, while the class javadoc,
  the commit message and the debt entry all said the refusal half was closed and tested. Every claim
  corrected; guard added in `spire-arch`, where the test task already declares every module's main
  sources as a Gradle input — the first version lived in the orchestrator and reported a cached PASS
  from the very edit it existed to catch, measured not assumed — round 1
- [code-quality/3, qa/6] **A re-armed dispatch retired a HEALTHY key and misattributed its spend.**
  The re-arm exists because the first command may be the one running, so the row can be asked to name
  two members and can hold one. Overwriting meant the run executing with member A reported its key
  refused, the row named B, and the pool retired B. `harness_credential_id` is now NULLED on re-arm:
  the honest answer, and `harnessCredentialOf` already treats empty as "mark nothing" — round 1
- [code-quality/1] **A key that cannot be decrypted failed one dispatch in N for ever.**
  `EncryptionService` throws `IllegalStateException`, not `SQLException`, so the catch missed it — with
  the use stamp already committed, the member still in the pool, and nothing naming which one. It now
  retires itself, because the answer is the same as a provider refusal — round 1
- [code-quality/8, qa] **Deleting the saga's call to the feedback path broke no test.** Every case
  called `reactTo` directly. The class's own javadoc names the installed-and-inert seam as the thing
  this project keeps rediscovering, and then did not assert it — round 1
- [code-quality/4] **A mixed pool's refusal was false and the two surfaces disagreed.** "Every
  credential is rate limited, retry then" concealed that N were permanently dead — while the attention
  row said it correctly. `Resting` now carries the rejected count, and both messages build from
  `PoolHealth`, which owns the one predicate that had been written three times — round 1
- [code-quality/4, second half] The transient race (a rate limit expiring between the two statements)
  answered "No harness credential is configured" to an operator who has several — the exact wrong
  sentence the design set out to avoid, arriving through the other door — round 1
- [security/M-1] **`add` skipped the SSRF/https guard every sibling registry applies**, and it matters
  more here: this is the endpoint an agent container would be pointed at, so `http://` ships the key in
  cleartext off the sandbox network. Type allowlist added with it — round 1
- [security/M-2] `PoolMember` and `Selection.Chosen` printed the decrypted key via the record's
  generated `toString()`. Masked, like `ExecuteRun` and `Credentials.Scm` — round 1
- [security/L-2, code-quality/2] `select()` is a WRITE and ran before two refusals, so a refused
  request consumed a rotation slot. Moved last. The `queued` 409 still follows it and cannot be moved —
  the row write needs the id — which is stated rather than glossed — round 1
- [code-quality/5] `select()`'s throw promised the caller would "refuse with the right sentence" and no
  sentence existed; it produced a bare 500 that says nothing in `%prod` — round 1
- [security/L-5, code-quality/6, qa] `POST /{id}/rest` answered 204 for an unknown id AND for an
  already-refused member, and logged a rest that was never written. Its two siblings 404 — round 1
- [code-quality/7] **Disabling was one-way**, so disabling the last member left an operator with a pool
  refusing every run and an `add` that 500s on the label they just disabled. `POST /{id}/enable` added;
  the duplicate label is now a 409 — round 1
- [code-quality/6-table] **The delete javadoc was inverted** — it said a hard delete would take the
  attribution with it, when the FK has no `ON DELETE` and refuses the delete. Three other places stated
  it correctly; the operator-facing one was wrong — round 1
- [rules/1 HIGH] **SMOKE-TEST Mode Q instructed the wrong setup for this very feature**, telling an
  operator to configure the LLM provider registry it stopped using. Step 5 rewritten, five
  troubleshooting rows replaced, and the missing-producer gap stated where an operator will meet it —
  round 1
- [rules/5] `ARCHITECTURE.md` put the pool in the WORKER schema under a different name; **PRD FR-F12
  promised "several credentials for one harness"** and the table has no harness column, while the
  refusal message implied the scoping. Both corrected, and the message no longer promises it — round 1
- [security/L-3, rules/3, code-quality/10] The reviewer's `LlmProviderRegistry` was still injected into
  the class whose whole point is that it no longer touches it, with four dead imports — round 1
- [code-quality/9] `update(sql, id, params...)` took the id twice — once for the message, once as a
  bind. Every statement ends in `WHERE id = ?`, so it is now bound once — round 1
- [code-quality/12] The default rest window was computed from the JVM clock while every other timestamp
  on the row comes from `now()`. A feature promising "capacity returns at a stated time" must not state
  it against a second clock — round 1
- [qa/2] **Three tests asserted something weaker than their name**, each proved by mutation:
  `aRejectedMemberIsNeverHandedOutWhileAHealthyOneExists` passed with the rejection filter deleted
  (fixture ordering, not the filter); `aMemberIsDisabledRatherThanDeleted` never asserted `enabled`;
  `aReviewsChargeNamesNoCredential` drove the run path — round 1
- [qa/6] **Three mutations survived and now do not**: `markRateLimited`'s rejected guard, the re-arm's
  attribution, and the everyday healthy-pool rotation — round 1
- [qa/3] `HarnessCredentialPoolTest` left an enabled, rejected member behind, raising a global
  attention row for every later suite. An `@AfterEach` clears it — round 1
- [rules/6, code-quality/14] `.env.example` pointed at a filename with a literal ellipsis; the plan's
  Files line still listed a settings UI that was deliberately not built — round 1
- [qa/5] The V52 CHECK was unnamed, unlike every other constraint this project adds — round 1
- [rules/2] The charge-attribution debt entry said "both paths, or neither" and the run half shipped
  alone. The deviation is now recorded WITH the objection answered (V52 documents the split, so a NULL
  means something), and the entry's own premise corrected: `ResultSaga.charge` has no credential in
  hand, so the review half is not a one-line addition — round 1
- [rules/4] The size entry's numbers went stale again; `RunResource` crossed 300 on its own preferred
  code-line measure — round 1
- Placeholder corruption (`run@Qs`) left in a committed comment by my own edit tooling — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [security/L-1] Add `FOR UPDATE SKIP LOCKED` to the selector. **Declined on QA's evidence**: a
  concurrency probe (8 sessions × 40 picks over 4 members) came out 80/80/80/80 with no deadlock, and
  the javadoc already states that two dispatches may legitimately take the same member because a key is
  not exclusive. Security read the "read-then-update" sentence as claiming exclusivity; the wording is
  clarified instead. Adding locking semantics to a non-exclusive resource would cost without buying.
- [qa/M3] The mutation dropping `last_used_at` from the ORDER BY is **uncatchable, and that is a fact
  about Postgres rather than a test gap**: V52's partial index carries that column as its second key,
  so the plan returns the same order whether the clause asks or not. Two reviews independently failed
  to kill it. Recorded in the code so nobody hunts it a third time; the mechanism the rotation actually
  rests on — the use stamp — IS asserted and its mutation fails.
- [code-quality/1 (Q1)] Do not split `HarnessCredentialPool`. Both the extraction that mattered
  (`PoolHealth`) and the reason are done; the rest shares one table, one row shape and one encryption
  boundary, and splitting would put the boundary in three places.
- [code-quality/11] `PoolMember.label`, `.type` and `.baseUrl` are carried and unread by dispatch. Now
  documented on the record: they are operator metadata, `baseUrl` is validated on the way in, and the
  endpoint the agent calls is the harness image's. Wiring them into `ExecuteRun` is a product decision,
  not a review fix.
- [security/L-4] Disabling never destroys the ciphertext. Documented — revocation of a leaked key is a
  vendor-side action and nothing here can do it.
- [security/L-7] Bounding a rejection cascade when a producer exists. Recorded on the debt entry for
  the producer task, where it belongs.
- [qa] The `RunAttentionRows` overflow rows are untested — pre-existing for the refusal row too, so not
  a new asymmetry.
- [qa] 13 files tracked under a mangled Windows path at the repository root. Added on `master` by an
  unrelated commit, not this branch.
