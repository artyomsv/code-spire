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

## Repository bridge — rollout evidence still needed (ADR-042, 2026-09-13)

- **The actual dev upgrade is not yet measured.** Slice 1 tests upgrade populated private
  PostgreSQL schemas, exercise the gateway outbox and real broker consumer, and compare legacy
  and explicit role resolution with distinct encrypted credentials. The running dev services have
  not been rebuilt for slice 1. The existing `.handoff/spire-dev-pre-m3-2026-09-13.dump` is the
  verified backup (182 objects; 492,480 bytes). The read-only continuity probe captured and compared
  9 real credential/reference entries successfully before upgrade; that does not prove post-upgrade
  continuity. Slice 2 must compare the same encrypted baseline after its migration on the real rows.
- **Historical forge origin can be unknowable.** Legacy history and gateway registrations do not
  always record a host. The bridge uses the immutable legacy account snapshot only when there is
  one matching origin backed by registration metadata or persisted review URLs; unknown origins,
  conflicts and missing accounts become attention rows with explicit repair. Runs with no such
  origin evidence remain pending even when only one legacy workspace account exists.
  Tests establish that refusal and repair, not which host an old live row actually belonged to.
  Inspect actual migrated mappings against the operator's known forge origins before cutover.
- **Native per-forge identity and permission behavior is unchanged in slice 1.** No new remote
  identity lookup or permission API is introduced here. Each introducing slice must add separate
  GitHub, GitLab and Bitbucket observations, stating the measured endpoint/token family and host;
  fixtures are not live evidence. The automated GitLab M2 gap remains a separate entry below.

Repository-origin normalization is local URL interpretation, not an identity API measurement:

| Forge behavior | Measured in this slice | Still needed before relying on a live mapping |
|---|---|---|
| GitHub public web URLs map to `https://api.github.com`; self-hosted origins remain unchanged | `RepositoryForgeOriginTest`, public URL and `TEST-forge.example.test` fixtures; no remote call | Inspect the actual account API base and persisted PR URL, particularly custom API proxies. |
| GitLab web/API path suffixes share the same canonical origin | Nested-namespace PostgreSQL history fixture plus `RepositoryForgeOriginTest`; no live GitLab call | Compare the actual API and web origins on the target installation. |
| Bitbucket Cloud public web URLs map to `https://api.bitbucket.org` | `RepositoryForgeOriginTest` public URL fixture; no live Bitbucket call | Compare a live persisted PR URL with the selected account API origin. |

## Accounts normalization — live evidence still needed (ADR-041, 2026-09-12)

Sources below were retrieved **2026-09-11**. WireMock checks prove how the application handles
responses; they do not establish which real token families send those responses. One live
GitHub `/user` request returned HTTP 200 and `X-OAuth-Scopes` on 2026-09-11, but the credential
was an **OAuth token**, so that observation does not establish classic PAT behavior.

| Unmeasured claim | Why the suite does not establish it | Evidence needed |
|---|---|---|
| GitHub classic PATs return `X-OAuth-Scopes` | The [GitHub scope documentation](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps) documents OAuth; the live request used OAuth, and PAT fixtures supply their own header. | A live classic PAT `/user` response, recording only status and scope header. |
| GitLab project/group access tokens answer `/personal_access_tokens/self` | The [API contract](https://docs.gitlab.com/api/personal_access_tokens/) covers PAT introspection; fixtures cannot prove bot-token support. | One project token and one group token on a recorded server version. |
| GitLab's minimum supported version provides `self` | The retrieved [API page](https://docs.gitlab.com/api/personal_access_tokens/) does not establish the minimum version for this deployment's supported range. | Versioned documentation or a live check at the minimum supported version. |
| Bitbucket Basic email/API-token responses carry `x-oauth-scopes` | The [REST authentication documentation](https://developer.atlassian.com/cloud/bitbucket/rest/intro/) and synthetic headers do not demonstrate this request/credential combination. | A live Basic request to `/user`, or the workspace repositories fallback, recording status and header only. |
| Bitbucket Basic API-token reports use the vocabulary understood by the warning rule | The [REST scope documentation](https://developer.atlassian.com/cloud/bitbucket/rest/) describes scope families; the fixture chooses its own vocabulary. | Observe real read/write token reports and compare them with both classic and modern scope names. |

Fine-grained GitHub tokens are represented as unknown when no granted-scope header is present;
`X-Accepted-GitHub-Permissions` is never treated as a grant. That fixture passes, but no live
fine-grained PAT was exercised for this change. Unknown scope reports still allow registration;
an empty report is displayed separately. Advice never establishes per-repository access.

**Scoped Atlassian tokens remain unsupported by the site-host flow.** Atlassian's
[token guidance](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/)
requires the `api.atlassian.com/ex/jira/{cloudId}` or `/ex/confluence/{cloudId}` gateway for scoped
tokens. The account probe tries site-host Jira and Confluence identity routes; readers keep their
existing site-relative paths. Gateway-base compatibility has not been measured and is not claimed.
Classic email/API-token identity and Confluence-only fallback are covered by synthetic responses,
not by a live Atlassian credential in this change.

**Upgrade evidence is synthetic.** A real PostgreSQL/Flyway V58→V59 test seeds six forge accounts
and five legacy source types; reconciler tests exercise deduplication, new-AAD decryption,
idempotency, per-row rollback and recovery. The operator's actual five source credentials were
not decrypted or migrated. Legacy code sources use a one-time recognition of GitHub's public hosts
or a hostname containing `gitlab`; all other hosts remain unmigrated for explicit operator account
selection. The heuristic itself is still not proof of the server's platform. Unmigrated rows now
produce a named Attention entry. Legacy Bitbucket code rows are retained for recovery, since the new code-source picker
supports GitHub and GitLab only. No production rollout is claimed. A headless Chrome rendering of
the actual account-table component and stylesheet at 1280/1440/1920 widths exercised long values:
smaller widths scroll inside the table without body overflow, and 1920 fits fully. This isolated
render is not a live, authenticated application walkthrough.

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
| **OIDC sessions actually renew instead of re-authenticating** | **Mode J check 11** (2026-09-10) | The bug it fixes needs a real browser, a real Keycloak and **fifteen elapsed minutes**. No suite here has any of the three: there are zero WebSocket client tests, and nothing observes a token reaching its `exp`. `OidcSessionsAreRenewedTest` asserts the four `application.yml` files *say* renewal is on — it cannot assert Quarkus *does* it |

**Evidence needed.** An operator pass per mode. These are cheap and the runbooks are written.

**The renewal row is the newest and the least settled**, so it says what would settle it precisely.
Two things are asserted by nothing:

1. **That `token.refresh-expired` applies at all under `application-type: hybrid`.** The Quarkus
   reference scopes the option to `ApplicationType#WEB_APP`; all four services are `hybrid`. Hybrid
   is web-app plus service and the web-app leg *should* honour it, but this project has twice shipped
   a setting that read as applied and was not — the `${VAR}` with no default, and the `ARG` BuildKit
   ignored. Both were green.
2. **That the session cookie still fits.** Renewal requires the refresh token in the cookie. The
   phase-0 spike measured the cookie **already chunked** with two roles and no custom claims
   (`docs/D10-AUTH-PLAN.md:239-240`), and proxy buffer sizing is itself unchecked
   (`techdebt/global/4-3-…`).

**Pass looks like:** dev stack up with authentication on, a value typed into a Settings form, the tab
left alone for **15 minutes**, and afterwards the value still present *and*
`docker logs spire-orchestrator-dev | grep "no longer valid"` showing no new line in that window.
Until that has been run, the reload fix is a config change nobody has watched work.

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
- **Every per-forge string in SCM-MAPPING §8 is read from vendor documentation, not measured.**
  The pull-request-open mapping — endpoints, field names, and especially the quoted error wordings
  for "nothing to propose" and "already exists" — has met no live API. `GitHubPullRequestSinkTest`
  drives a WireMock stub this repository wrote, so it establishes what the adapter does with a
  given response and nothing about what GitHub actually sends. The adapter is built so a wrong
  guess degrades safely: an unmatched 4xx stays a fault rather than being reported as "the agent
  changed nothing". **All three cloud columns now have adapters** (83 + 87 + 75 tests) — driven by
  the same locally-written stubs, so all three are established against this repository's idea of
  each API and none against the API. The Bitbucket DC column has no implementation at all.
  *(This line previously said the GitLab and Bitbucket rows had no implementation, which was true
  for one commit and false for the next — the doc-vs-code drift this page exists to catch, caught
  by a review rather than by me.)*
- **`GitLabPullRequestSink`'s nothing-to-propose arm may be unreachable, and the adapter and the
  mapping table disagree about it.** SCM-MAPPING §8 lists GitLab's "nothing to propose" as
  `409 "branch conflicts"` or an empty-diff 400; the adapter matches `409` + `"no changes"`, a
  phrase neither cell contains, and the test that covers it stubs a body this repository invented.
  Two reviewers flagged the contradiction independently, and one raised the stronger possibility
  that GitLab CREATES a merge request with no commit difference rather than refusing — in which
  case the arm never fires. **The failure direction is why it ships anyway:** if the phrase never
  matches, a no-diff run reports the forge's own error, which is honest; the status gate makes a
  wrong match much harder. One measurement against a live GitLab (SMOKE-TEST Mode G) settles it,
  and nothing should depend on this arm until then.
- **~~The M2 loop has no joined live proof~~ — CLOSED 2026-09-12.** On the live GitHub pull
  request `artyomsv/spire-test#31`, runs `3987682681:1` and `3987682176:1` traversed finding → fix
  run → push → reconciliation. The review threads were resolved and verdicts persisted. This
  observation closes the live-chain claim; it does not establish an automated GitLab loop.
- **The automated GitLab M2 loop still cannot join dispatch, push and reconciliation.**
  `FixRunDispatcherTest`, `Adr040ExistingBranchTest` and `ReviewChainTest` cover those legs
  separately. A run unit cannot resolve the e2e stack's `gitlab` service: `RunUnitSpec` has no
  network field and `DockerRunRuntime` never sets one. Rebinding GitLab off loopback would undo a
  deliberate security control in `compose.e2e.yml`, so it is not the answer.
  — `techdebt/spire-runtime-docker/2-3-a-run-unit-has-no-network-so-it-is-neither-isolated-nor-reachable.md`
- **~~The publisher's trunk floor is not exercised end to end~~ — CLOSED 2026-09-11.**
  It now is. This entry said the run died as `RUNTIME_UNAVAILABLE, init container failed with exit 1`
  before the publisher was consulted, because `WorkspaceClone.populate` called
  `checkout().setCreateBranch(true)` and a clone has already materialised the remote's default
  branch locally — so deleting `PublisherConfig.looksLikeATrunk` left `Adr040ExistingBranchTest`
  green. The clone fix (#150) replaced that checkout with a branch create and a reset for an
  unrelated reason, and the side effect is that the clone succeeds and the run reaches the publisher.
  Measured: the refusal is `PUBLISHER_MISCONFIGURED`, naming the trunk, and the test now asserts that
  cause by name. Two things made this visible and are worth carrying: the entry was only true while
  an OUTER guard fired first, and the test had also been passing on a leftover workspace volume from
  its own previous run — see `TestImages.clearUnit`.
- **`/fix` trusts the pull-request state the deployment last saw, not the one that is true now.**
  `pr_state` is set to `OPEN` by every pull-request event, so a redelivery after a merge flips a
  closed pull request back to pushable in `FixTargets` — the row is the KEY to the target, never
  the PROOF of it. The same is true of `from_fork` and of `source_branch`. Closing it needs a
  dispatch-time re-read from the forge, which the orchestrator may do and the publisher (ADR-039)
  may not; it is the same re-read the shared-branch gap wants, so the two want one design.
  Recorded here because it lived only in a javadoc on the class that has it, where nobody
  planning the next slice would find it.
  — `techdebt/spire-orchestrator/3-3-a-long-lived-shared-branch-passes-every-fix-check.md`
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
