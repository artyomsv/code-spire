# Code Review State: global / m1-task1-failure-taxonomy

Last reviewed: 2026-09-02
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [cr-C2 / sec-M1 / rules-H2 / rules-M5 / qa-F7] The branch fixed `RunLauncher` and treated that as fixing the worker. `RunDispatcher`'s two sites still built details from a raw `e.getMessage()` and still hardcoded retryability, and `RESULT_UNPUBLISHABLE` said `false` on the wire while the set answered `true` for the cause stored beside it — `RunFailures` is now the one collaborator both classes build failures through, so the "no path bypasses this" claim is about the service rather than one class — round 1
- [cr-C1 / sec-M2 / qa-F4] The model API key was the one credential the scrub omitted, and the debt entry it closed named exactly that risk; a single failed SCM decrypt also disarmed the harness scrub. Both credentials are decrypted independently now, each failure logged without naming what failed — round 1
- [cr-I4 / sec-L4] `V46` sent every legacy spelling to `UNCLASSIFIED` although the alias map knew the correct target for eleven of them, and M0 wrote only alias spellings — so it discarded the classification of every failure the deployment had recorded. Translates first, then sweeps, preserving the original word exactly as the runtime path does — round 1
- [rules-M3] A `TransportException` collapsed into a refusal and became non-retryable, where it was retryable before this branch — inverting the answer for the case the debt entry was about. `PUSH_TRANSPORT_FAILED` is its own retryable value, the publisher's catch is split by who failed, and the legacy `PUSH_FAILED` alias points at the transport reading — round 1
- [cr-I1 / cr-I2 / qa-F2 / qa-F3] The producer scan was blind to `PUSH_FAILED` and `NON_FAST_FORWARD` (this task's own last commit assigned a cause to a local, and the pattern anchored on a call shape) and to `EVICTED` (single-word enum values needed an underscore). It matches statements now, and its sentinels are unique to their producer — `BUNDLE_UNREADABLE` appears in both modules, so the publisher scan could have broken entirely and still passed — round 1
- [cr-I5 / rules-H1 / qa-F5] `choosableNames()` had zero callers and the guard its javadoc promised was never written, so nothing bound the enum to the CHECK: adding a value without a migration would violate the constraint inside a result handler after the model was paid for. Asserted in both directions, against whichever migration most recently closes the set — round 1
- [cr-I3 / qa-F1] `aTransportRefusalIsNotReportedAsAMovedBranch` pushed to a fresh branch that SUCCEEDED, so no refusal was constructed and the flag was never read; hardcoding it to true passed both tests. Replaced by a construction-driven negative covering both non-divergence shapes — round 1
- [qa-F6] The publisher's half of the mapping had no test at all: the entire push-refusal catch was unreached. `PublishCycleTest` now drives a real moved branch through it — round 1
- [cr-Q1] `RUNTIME_UNAVAILABLE` had no producer while its exact case — `runtime.create` failing — was labelled `SANDBOX_LOST`, whose javadoc says "before it finished" and is wrong at create time — round 1
- [cr-S4] `RESULT_UNPUBLISHABLE` aliased to `WORKER_FAILED`, which answers retryable; the broker refused the result twice, so a re-run produces a result refused again. Its own non-retryable value — round 1
- [cr-I6] `PublishCycle`'s comment said `PUSH_FAILED` "is classified retryable", false as of two commits earlier in this same branch — round 1
- [sec-L6 partial] The failure detail was unbounded on the wire while agent-influenced text reaches it through the publisher — clipped at 8 KB in `RunFailures` — round 1
- [sec-L1 / rules-M4] A degraded scrub was silent, so the security control could become a no-op with nothing saying so. Logged without the exception or the credential's name — round 1
- [rules-8 / cr] `PushRefusedException(List, boolean)` was the flag-parameter shape the rule names — named factories `refused` / `branchMoved` — round 1
- [cr-S3] The alias map and a values() scan were two lookups; merged, so an alias shadowing a canonical name fails at class initialisation rather than winning silently — round 1
- [rules-M6] No doc was touched by six commits that added a terminal status, a closed vocabulary, two migrations and a deliberate behaviour change — while Task 0 on this branch updated CLAUDE.md in-commit — round 1
- [cr-S6] Stray blank line and a value filed under the wrong section heading — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- [cr-I3 preferred remedy] A `pre-receive` hook was the suggested way to produce a real non-divergence refusal. Attempted and abandoned on evidence: JGit's local transport implements receive-pack in-process and never runs server-side hooks, so the hook was not invoked and the test passed by pushing successfully — vacuous a second time, in a new way. The construction-driven negative is what shipped, with that reason recorded in the test (round 1)
- [rules-7, rules-10] Conventional Commits prefixes. The repository is 100+ commits deep in plain imperative style and the binding personal rule caps only the first line; the rules-compliance lens recommended keeping house style (round 1)
- [qa build-lane note] `--rerun` at the end of a multi-task line binds only to the last task. Accepted as a working correction rather than a code finding: the tier runs use the global `--rerun-tasks`, and QA independently re-ran every module and reproduced the counts exactly (round 1)

## Open (tracked as techdebt/ entries; not fixed in this round)
- [cr-S8 / rules-1] The scrub entry was closed while `RunDispatcher` was untouched, and the non-fast-forward entry while a resumed run still cannot deliver — the second half re-filed as `techdebt/spire-publisher/4-3-a-resumed-run-cannot-deliver.md`
- [sec-L6 / qa-F10] Three control-plane causes are accepted from the worker's channel, and the orchestrator is a fourth producer the scan does not cover — `techdebt/spire-run-worker/4-1-the-orchestrator-writes-a-cause-outside-the-taxonomy.md`
- [qa-F9 / sec-L4] Both migrations' row rewrites are exercised by no test; two lenses verified them by hand on a real Postgres — `techdebt/spire-orchestrator/4-3-migration-row-rewrites-are-verified-by-hand-only.md`
- [sec-L7] The viewer-readable failure detail stays an accepted posture with its condition now met; the entry records two content classes the first version did not weigh and is marked for re-decision — `techdebt/spire-orchestrator/4-1-a-runs-failure-detail-is-readable-by-a-viewer.md`
- [sec-L2] **CLOSED in PR #107**, all three halves. This entry named the read-username pairing, the
  length floor and `URLEncoder`'s form-encoding together, and correctly called them "latent while a
  deployment carries one alphanumeric token" — so it sat Open while every half stayed reachable.
  Two later rounds found them one at a time without noticing the entry that already named all
  three. Worth reading before opening another: an Open entry naming several defects is not a
  single item, and the one that goes latent last is the one nobody re-reads.
- [sec-L5 / cr-S1] `V47` drops an auto-named constraint by a guessed name; the guess is correct (verified on Postgres 18 by two lenses) and a projection test proves it landed, but `V44`'s `pg_constraint` lookup is the better pattern
- [rules-1, NFR-F9] `spire-ui` has no factory-run surface, so `delivered_nothing` has no UI half. This project has shipped that gap twice, both times defaulting into the success branch — belongs to the M1 UI work

## Notes
- **A false premise of mine was corrected by evidence.** The scrub's test comment claimed docker-java quotes the create request including its environment; the security lens read the pinned 3.5.1 and found exceptions are built from the HTTP response, never the request. The scrub is still right as defence in depth and the stated reason was wrong.
- **I overlapped two Gradle invocations while fixing this round**, corrupting shared results — the between-invocations version of exactly what Task 0 fixed between modules, and a bound Task 0's own documentation states. Re-verified with a single sequential run.
- Two of my own tests failed correctly during the fix batch, both asserting vocabulary the review had shown wrong.
