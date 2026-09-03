# Code Review State: global / factory-m1-debt

Last reviewed: 2026-09-03
Rounds completed: 1

Round 1 over PR #106 (`chore/factory-m1-debt`), the branch closing the eight `techdebt/` entries the
M1 whole-PR review filed. Four lenses. Semgrep 27 files, 0 findings. qa independently reproduced
300 suites / 2579 tests / 0 failures before reporting.

**Three criticals, each found by two lenses independently, and each a consequence of a fix in this
same branch.** That convergence is the useful signal: none was a matter of taste, and none was
visible from the file it lived in — all three needed a path read end to end.

The sharpest lesson is not any single defect. It is that **the cancel gap has now had three reviews
each close a different half of it** — the executing case, the dispatch-uncertain case, then the
queued case — and this round found the fourth, which the retired `UNVERIFIED.md` entry had named
outright: *"plus registering the run before `create`"*. Deleting that entry deleted the note that
said what had not been done. **When retiring an entry from that register, check the whole of what it
specified, not the part that was implemented.**

## Resolved (fixed in code; do not re-raise)

- [sec/H1, cr/3, qa/HIGH] **The cancel window moved rather than closed.** One read of `CANCEL_SLOT`
  sat before `create()`, which blocks on an image pull and an init clone — up to ~25 minutes on the
  Docker arm. A cancel arriving in there found an empty registry, wrote the claim, and nothing read
  it again. The slot's own javadoc listed "cloning" among the cases it covered. Now read a second
  time immediately after `registry.register`; the listener writes the claim only after finding the
  registry empty, so one of the two reads sees it in either interleaving — round 1
- [sec/H2, cr/2] **`claims.taken` could throw past the ack**, breaking the class's own rule and
  leaving the run `queued` for ever with no terminal result and every redelivery refused. The lease
  guard eleven lines below already had the right shape — round 1
- [cr/1, sec/M3] **The publisher's clone was put on a 2 GiB RAM disk.** Bounding `/tmp` on all three
  containers was a regression: the publisher clones into `java.io.tmpdir`, so a repository larger
  than the budget became an `ENOSPC` at publish time, after the agent had been paid. The threat
  model settles it — the agent alone runs untrusted output. A test now pins that the other two are
  NOT bounded, with the reason — round 1
- [cr/5] **`aRedeliveredCommandIsStillRefusedByTheSameCancel` could not fail for its named property.**
  The second delivery never reaches the cancel check; the execute claim returns it as a redelivery
  first. Replaced by an assertion that the slot survives the read and that exactly one slot was ever
  claimed — round 1
- [cr/12] **The run worker linked JGit transitively.** `spire-workspace` exposes it as `api`, so
  moving `SecretScrub` there put a git library on the classpath of the process whose entire claim is
  that it runs no git. A scan refuses an import; it cannot refuse a capability. New JDK-only
  `spire-secrets`; verified by resolving the worker's `runtimeClasspath` — zero JGit references. The
  guard's allowlist is now empty — round 1
- [cr/11, qa/MEDIUM] **V53 could abort a deployment.** `json_agg` over zero rows returns NULL, which
  the surviving CHECK forbids, so an empty or whitespace-only legacy row failed the migration and the
  orchestrator would not start. `coalesce(…, '[]')`, `btrim` on each line, and a shape-based
  idempotency guard so a path beginning `[` is not mistaken for JSON. Re-probed on real Postgres 18
  across seven cases — round 1
- [qa/MEDIUM] **`BlockedChanges` had no test at all**, while two production files read it and its
  documented contract is that a malformed row degrades rather than throwing — round 1
- [cr/14] **An unreadable row rendered as "it changed ."** in the attention panel — round 1
- [qa/MEDIUM] **The contract's cancel rules were satisfied by a no-op `cancel`**, and the file
  contained its own proof: `salvageNeverDestroys` makes the identical assertion after a call that
  stops nothing. Renamed to what it checks, the missing half moved to the not-covered list, and the
  Docker arm now asserts container state — round 1
- [cr/13, qa/MEDIUM] **`quietUnit`'s specification said the opposite of what the rules need** — round 1
- [cr/6] **Two `RuntimeCapabilities` notes were wrong in the opposite direction** from the ones they
  replaced: `networkPolicy` and `nativeSidecar` are read by tests — round 1
- [rules/1, sec/M4, cr/4] **`ROADMAP.md` said both High debts were closed** while `RUN-TOPOLOGY.md`
  §9.7, added in the same branch, said a run can still fill the daemon's disk. `RunUnitBuilder`'s
  `DISK_BYTES` javadoc claimed the shared volumes were bounded on every arm and that a large clone
  would ENOSPC — the opposite of both, in the file where the number is chosen — round 1
- [rules/2] `UNVERIFIED.md` §A's heading and intro claimed a build guard for every entry; A3 has
  none — round 1
- [rules/3] `SMOKE-TEST.md` Mode Q told an operator to read `blockedPaths`, which the API no longer
  emits — round 1
- [rules/5] `CLAUDE.md`'s migration range said V42–V52 — round 1
- [rules/7, cr/9] **An orphaned javadoc**, again: `stop()`'s documentation was discarded by an
  insertion above it. Plus a stacked pair in `RunDispatcherTest` — round 1
- [sec/L1] A gitlink at or above a protected directory redirects a read as a symlink does — round 1
- [sec/L2] Blocked path and kind were length-unbounded into one operator-facing sentence — round 1
- [cr/8] `SecretScrub.of(String, String...)` was public in a shared module with no production
  caller, and is the shape whose misuse the class itself documents as having happened — round 1
- [cr/10] The two symlink test cases were named for each other's branch — round 1
- [cr/7] The symlink rule covers the floor and not a profile's globs. Stated on `decideCompiled`
  rather than closed: a compiled `PathGlob` no longer carries its pattern, so closing it is a change
  to that type, worth doing when a profile is first configured in anger — round 1
- [qa/2, cr] The kind was unasserted across the real publisher seam; `M0WalkingSkeletonTest` now
  pins ADDED and MODIFIED, which is the distinction the change exists for — round 1
- [cr] `DockerRunRuntimeIT`'s teardown destroyed unguarded, so one throwing destroy leaked every
  later unit — round 1
- [qa/5] The guard's vacuity check read RAW source, so a commented-out import satisfied it; and its
  inline-reference check skipped any line starting `import`, which `importantThing = …` does — round 1
- [rules/4, rules/6] `MODULES.md` gains a section for the new module and its glance-table row —
  round 1

> **Both security findings below were taken in PR #107**, in the dedicated pass this file said they
> wanted. They are left here under Dismissed rather than moved, because the disposition recorded at
> the time is what a reader of THIS round needs — but neither is open, and `sec/M1` was answered by
> a different remedy than the one proposed. See `.claude/reviews/global/credential-scrub-forms.md`.

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [sec/M1] **Refuse an SCM secret below `MIN_SECRET_LENGTH` at `PublisherConfig` and at FACTORY
  registration.** The diagnosis is right — Gitea/Forgejo accept an account password for
  git-over-HTTP, minimum 6 — and the floor is a real behaviour change for the publisher. Not taken
  here because it is a *product* decision about what an operator may configure, on a registration
  path this branch does not otherwise touch, and refusing at save time changes an existing API's
  contract. Worth doing; wants its own change so the refusal message and the migration story for an
  already-registered short secret get designed rather than bolted on.
  **PR #107 took the opposite remedy and it is better: the floor was removed instead, so a short
  secret is scrubbed like any other and no refusal is needed anywhere.**
- [sec/M2] **The proxy password is scrubbed decoded, while the container environment carries the
  operator's raw percent-encoded spelling.** Correct and narrow: it needs a password containing an
  escape whose case or `+`/`%20` form differs from `URLEncoder`'s output, and the exposure is an
  agent running `printenv` into a transcript. The fix is a second form on `Credential`, which is a
  change to the shared scrubber's data model — better with the M1 finding above, in one pass over
  what a credential's *forms* are.
  **Fixed in PR #107** — both spellings are collected, and the `+`-as-space half was found there too.
- [rules/6] Commit `30f3226`'s subject is 74 characters against a 72 limit. Already pushed; the
  finding says itself it is not worth a rewrite. Later subjects are within the limit.
- [qa/6] `DockerTestsAreSerialisedTest` does not scan `src/testFixtures`. Verified as theoretical
  today — the contract holds no daemon marker and `spire-runtime` has no concrete subclass — and the
  guard's own javadoc already carries a known-blind-spot section. Worth a line there when that class
  is next opened.
- [cr] Delete `ChangedPath`'s 2-arg convenience constructor in favour of a test helper. The argument
  is sound and matches `RunFinished`'s precedent. Left as-is because the production builder is a
  single private method that takes the mode, and the twelve call sites are all tests; the risk this
  guards is a *production* rebuild site, of which there is one and it cannot compile without the
  mode.
