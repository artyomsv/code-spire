# Unverified claims — what this project asserts but has not proven

A register of things the code or the documents claim, that **no test establishes**. Not a bug list:
a list of places where a green suite is not evidence.

It exists because the same failure shape has now cost this project three milestones in a row — a
feature that was green, documented, and did not work — and in every case the test asserted the half
that was easy to observe rather than the half the claim rested on:

| Case | What the test asserted | What the claim needed |
|---|---|---|
| The credential pool (M1 Task 10) | the translation, from a hand-built input | that the pipeline ever produces that input |
| The corporate CA bundle (M1 Task 11) | the file is mounted | that something trusts it |
| The conformance checker (M1 Task 12) | the clause mapping | the verdict against a real image |

None was found by reading a file. All three were found by reading a **path**, end to end.

## How this relates to the other two homes

`techdebt/README.md` already splits debt in two, and this is a third thing that indexes across both:

- **`techdebt/<module>/*.md`** — a defect in the code, resolved by an edit. Each entry holds its own
  detail; this page does not repeat it, only points at it when the entry is really an *unproven
  claim* rather than a known-wrong line.
- **GitHub issues labelled `tech-debt`** — deferred verification needing a corpus, a live deployment,
  a spend budget, or elapsed time. That is the right home for anything below that needs money or
  weeks; this page is the readable index of them.
- **This page** — the standing answer to "what do we believe that we have not checked". Add a row
  when you ship a claim you could not test; delete it when evidence lands.

Every entry says the same three things: **the claim**, **why nothing catches it today**, and **what
evidence would settle it**.

---

## A. Known not to work — documented, and guarded where a guard is possible

These are not suspicions: each gap is proven. **A1 and A2 are build-enforced** — a guard fails the
build if the gap silently closes, so the register cannot go stale without somebody noticing. **A3
is not**, and saying so is the point of splitting this sentence: what would close it is a provider
starting to resolve, which no source scan can see.

> A fourth entry stood here and is gone: a cancel for a run that had not started was accepted and
> dropped. It is **fixed** (PR #106) rather than merely recorded — twice over, because the first
> attempt closed only the queued half and left the fifteen-minute clone window open. The entry
> itself had named the missing half (*"plus registering the run before `create`"*), and deleting
> it deleted the note that said what had not been done. If an entry here is retired, check the
> whole of what it specified, not the part that was implemented.

### A1. The credential pool cannot retire a dead key

**The claim.** `harness_credential` has two exhaustion states: a rate limit that heals itself, and a
rejection that needs an operator. Both were described as implemented.

**Why nothing catches it.** Nothing in the pipeline emits `CREDENTIAL_REJECTED`. The harness tier's
failure vocabulary has no credential value, the publisher's has none, and nothing aliases onto it. A
refused key arrives as `MODEL_UNAVAILABLE`, which the feedback rule deliberately ignores — so the
pool hands the dead key back out on its next turn. V52's own header calls this *"how a pool quietly
stops rotating while looking healthy"*, written in the change that shipped it. The test passed
because it constructed the wire string by hand.

**Evidence needed.** One real run with a deliberately invalid model key, capturing the agent
container's actual output and exit code. Then the mapping is written *from the observation*, in the
harness adapter. Guessing the pattern is worse than the gap: it would pass a test built from the same
guess and retire nothing in production.

**Guarded by** `spire-arch`'s `CredentialRefusalHasNoProducerTest`, which fails the build when a
producer appears — that red is the signal to remove it and correct the three documents.
**Tracked in** `techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md`.

### A2. The rate-limit half has no producer either

Same shape, same fix, same evidence — a provider response that states a retry-after. Until then
`SPIRE_RUN_CREDENTIAL_RATE_LIMIT_DEFAULT_SECONDS` applies only when an operator rests a member by
hand.

### A3. Code context resolves nothing in the containerised e2e stack

**The claim.** `spire-context-code` contributes resolved definitions to a review.

**Why nothing catches it.** Context providers fail **soft**, so a provider that resolves nothing and
a pull request with genuinely no context are indistinguishable. The e2e probes are disabled with the
failure unexplained. The first diagnosis offered — an SSRF guard refusing site-local addresses — was
**false**, and reached five documents before anyone read the guard.

**Evidence needed.** A reproduction in the e2e stack with the resolution step instrumented. The
operator-facing risk outlives the specific bug: a silently failing context provider looks exactly
like a repository with nothing to retrieve.

**Tracked in** `techdebt/global/3-3-code-context-resolves-nothing-in-the-e2e-stack.md`.

---

## B. Works in tests, never proven on a live deployment

Each has a runbook mode. None has been run by an operator.

| What | Runbook | Why a test cannot settle it |
|---|---|---|
| The corporate CA bundle and proxy reach all three containers | `SMOKE-TEST` **Mode R** | The unit tests prove trust against a *self-signed* endpoint. Only a real TLS-inspecting proxy exercises the JVM half, the `NO_PROXY` list, and a proxy that authenticates |
| `spire-agent-image verify` against a real image | **Mode S** | The IT builds a minimal image around the real entrypoint. A real Codex image with a toolchain is a different size, a different base, and a different `PATH` |
| The whole M1 lifecycle against a real forge | **Mode Q** | Cancel, steer, the watchdog, the push gate and the charge ledger have only ever met a WireMock LLM and a local origin |
| Corporate-only bundle → the failure it produces | Mode R §5 | The documented trap (internal forge works, model API fails) is asserted nowhere; it is the mistake an operator will actually make |
| A private-registry pull | Mode S §4 | Nothing pulls from a private registry in any test. `authFor` and the attachment are unit-tested; the *pull* is not |

**Evidence needed.** An operator pass per mode. These are cheap and the runbooks are written.

---

## C. Paths no test reaches

Real code, exercised by nothing. Each is a place where a regression would be silent.

- **The run event stream's live tail.** Three of the plan's seven Task 2 scenarios remain unwritten,
  including the one that would have caught both of that task's criticals. It needs a *real*
  subscriber: a faked connection returns whatever endpoint id the test chooses, so it cannot catch
  the defect that mattered. — `.claude/reviews/global/m1-task2-run-event-stream.md`
  *(The socket's unknown-run guard was also listed here and did not belong: it was not untested,
  it was DEAD — `countFor` ran `SELECT count(*)`, which always returns a row, so the `-1` its
  javadoc promised was unreachable. Fixed; recorded here because "untested" and "cannot work" are
  different claims and this page exists to keep them apart.)*
- **A lease with no unit is reclaimed by nothing.** `WorkspaceLeases.staleLeases` was written for
  exactly this, with a javadoc naming the watchdog, and has **no production caller**. The run's row
  stays `queued` forever. — `techdebt/spire-run-worker/3-3-a-lease-with-no-unit-is-reclaimed-by-nothing.md`
- **Migration row rewrites.** Both are exercised by no test; two review lenses verified them by hand
  on a real Postgres. — `techdebt/spire-orchestrator/4-3-migration-row-rewrites-are-verified-by-hand-only.md`
- **The attention panel's overflow rows** are untested, for the run and review halves alike.
- **`RunEventRecord` is outside `ContractSchemaSnapshotTest.ROOTS`**, so a renamed component breaks
  the wire silently — and the snapshot already **does not recurse into nested wire types**, which is
  a wider blind spot than this one field.
  — `techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`
- **WebSocket behaviour under auth, from a browser.** Measured from `curl` only.
  — `techdebt/global/4-2-websocket-behaviour-under-auth-is-unmeasured-from-a-browser.md`
- **Proxy buffer sizing.** Reproducing it needs a real chunked session from a live identity provider,
  which neither `deploy/e2e.sh` nor the chart tests have.
  — `techdebt/global/4-3-proxy-buffer-sizing-is-unverified-by-any-check.md`
- **`ReviewRetryScheduleIT` races the live 5-second retry sweep**, which calls the same method the
  test calls. Deterministic in itself, so it fails only under load — and names the wrong cause when
  it does. — `techdebt/spire-orchestrator/4-2-the-retry-schedule-test-races-the-live-scheduler.md`

---

## D. Claims that need a corpus, money, or elapsed time

A test cannot settle these. They belong in issues, where results accumulate.

- **Does retrieved code context make reviews better?** ADR-026 §9's gate returned a **null** on this
  repository, and the null is *corpus-limited*: 3 code findings against 15 documentation findings,
  against a noise floor of five differing findings when the identical arm was run twice. The gate
  established that this corpus cannot measure the feature, **not** that the feature does not help.
  Needs a majority-code corpus with cross-file dependencies. — issue
  [#89](https://github.com/artyomsv/code-spire/issues/89), harness at `docs/superpowers/gates/`
- **Is rung 2's citation worth anything?** `callersOf` naming a real caller is a fact and is proven —
  precision 6/6, recall 46% after one review. That a cited caller makes a review *better* is not.
- **Does learned memory hide the right findings?** The suppression mechanism is tested; whether the
  proposals it generates are ones a team would accept needs a real corpus of accepted and rejected
  findings, which only accrues from here (`review_finding` has no backfill, deliberately).
- **Under-reported token usage.** Nothing inside a run unit can distinguish an honest small usage
  report from a dishonest one. Only reconciliation against the provider's own billing or usage API
  can. The call-count axis is the partial mitigation that already exists.
- **Fleet spend caps on an UNMETERED deployment.** A money-denominated cap is inert by design where
  every charge is an asserted zero; the call-count axis carries it. Whether that is sufficient in
  practice is unmeasured.

---

## E. Accepted and unverifiable — recorded so nobody re-derives them

Not work. Written down because each has been rediscovered at least once.

- **The agent can read the proxy credential.** Every container must route through the proxy, so the
  URL — basic auth included — is in the agent's environment, and the agent runs untrusted model
  output at full shell access. A deliberate trade; give the proxy a scoped service account. What *is*
  guaranteed is that it never reaches anything stored.
- **Docker socket access is root-equivalent on the host.** Stated in `SECURITY.md` rather than
  mitigated; the Kubernetes arm removes it.
- **The bundle path is validated in the worker's filesystem and resolved in the runtime's.** The same
  one while the worker runs on the host, which is how it runs today. Packaging the worker inverts the
  guard in both directions.
- **A rotation mutation is uncatchable.** Dropping `last_used_at` from the pool selector's `ORDER BY`
  changes no query plan, because V52's partial index carries that column as its second key. Two
  reviews independently failed to kill it. The use stamp is the mechanism rotation actually rests on,
  and *that* mutation does fail.
- **One SCM token serves the clone and the push.** `Credentials.scm` packs the machine
  account's single secret into both slots, so the init container holds a token that can write —
  while six places describe a read-only clone token. The agent is unaffected and that is the
  isolation that matters: it gets no git credential, JGit persists none under the workspace, and
  the remote is removed after the clone. What is missing is the second line of defence. Closing it
  needs a forge-specific read scope, which is a product decision rather than a code change; the
  six documents now say what the code does.
- **The spend cap is soft, and softer than this page first said.** Charges land only when a call
  completes, so overshoot is bounded by **queued + in-flight** runs × per-run cost — not by
  in-flight alone, which is what an earlier version of this line claimed. The worker consumes one
  command at a time and never re-checks the cap at consumption, so N dispatches accepted while the
  window reads empty become N sequential paid runs after it has tripped. A live-run cap at dispatch
  (`SPIRE_FACTORY_MAX_LIVE_RUNS`) bounds the queue; the residual softness is the in-flight half.

---

## How to use this page

**Before claiming something works**, check whether it is listed here. If it is, the claim needs
evidence, not a re-read.

**When you ship a claim you could not test**, add a row. The cost of an entry is two minutes; the
cost of the alternative has now been measured three times.

**When evidence lands**, delete the entry in the same commit — and if it disproves the claim, say so
in the commit rather than quietly narrowing what was promised.
