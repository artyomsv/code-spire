# Code Review State: global / p4-learned-memory

Last reviewed: 2026-08-29
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [security/H1 + code-quality/C1] Suppression filter ran after recordOpenFindings, so a hidden finding entered the next round's exclusion list and revocation could never restore it — filter moved above every derived write, saga-level seam test added — round 1
- [code-quality/C2] ReviewRuns.currentRun returns FIRST_RUN on read failure, making the round guard unreachable and a transient fault delete round 1's rows — roundOrUnknown sentinel added — round 1
- [security/M2] No category or severity floor on suppression; ACKNOWLEDGED evidence is manufacturable by the PR author — SECURITY/BLOCKER floor enforced at both proposal and filter, plus a two-distinct-review minimum shown on the card — round 1
- [code-quality/I3] MemoryResource read thresholds as fields off a CDI client proxy, showing "threshold: 0 findings, 0% dismissed" — accessors added — round 1
- [code-quality/I4] "Median rounds to fix" was the median round raised (ORDER BY round) — V39 verdict_round, rows predating it excluded rather than zeroed — round 1
- [security/M5] markSuppressed stamped every row on the line — narrowed to the newest unsuppressed row — round 1
- [code-quality/I5] recordConversationFinding had zero callers, so origin='conversation' rows could not exist — wired into IntegrationSaga — round 1
- [security/M6] PR comment pointed at a per-review list of hidden findings that does not exist — now names Settings → Memory — round 1
- [security/M3 + rules/5] Identity resolution used the internal io.quarkus.oidc.runtime.OidcJwtCallerPrincipal, duplicated in two classes — one shared OidcSubjects helper on the supported JsonWebToken API — round 1
- [security/M4] Preference scope carried no platform, pooling two SCMs' evidence — provider_type in scope_value and the GROUP BY — round 1
- [rules/1] FindingProjection 337 lines over the 300 max — JDBC plumbing extracted to FindingRows — round 1
- [rules/6] AnalyticsResource.myActivity used isEmpty()/get() — map/orElseGet — round 1
- [qa] MemoryResource had zero tests — MemoryAuthorizationTest covers viewer-refused on every verb, admin thresholds, 404 on a missing row — round 1
- [qa] PreferenceProposals.qualifies had zero tests — PreferenceProposalsTest covers both floors and the integer-truncation boundary — round 1
- [qa] FindingsParser's category wire-up untested at the join — omitted and invented labels assert null, never OTHER — round 1
- [qa] Four saga fakes reached a live DataSource through un-overridden methods — all overridden with a comment — round 1
- [rules] Stray oidcutils.txt committed twice by `git add -A` — removed and gitignored — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- (none)

## Deferred with a reason (not dismissed — genuinely open)
- [code-quality/I1] recordVerdicts can fall through from byThread to byLocation and stamp the current round's fresh finding. Needs a round bound plus a found-and-judged probe; the existing test asserts today's behaviour and would need rewriting with it.
- [code-quality/I2] recordThreadRefs picks the wrong row on a CommentsPosted redelivery when an earlier round's row is still unposted.
- [security/L7] OperatorIdentities reports a DB outage as "unlinked" — fails closed, but names the wrong state.
- [security/L8] The JWT identity path is untested; @TestSecurity yields a QuarkusPrincipal, so only the fallback runs.
- [security/L9] /rescan is unbounded — a full aggregate over review_finding with no time window.
- [qa] AnalyticsQueries arithmetic (dismissal rate, median, per-repo isolation) has no dedicated test.
- [rules/2, rules/3, rules/4] ResultSaga.onReviewGenerated length; parameter counts on recordConversationFinding/markSuppressed; two methods 2-3 lines over.
