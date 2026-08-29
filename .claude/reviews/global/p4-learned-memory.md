# Code Review State: global / p4-learned-memory

Last reviewed: 2026-08-30
Rounds completed: 1 (all findings fixed in-round)

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
- [code-quality/I1] recordVerdicts fell through from a settled thread to the location rule and stamped the current round's fresh finding — verdicts now bounded to earlier rounds, and the thread path probes rather than inferring from a row count — round 1
- [code-quality/I2] recordThreadRefs stamped an earlier round's unposted finding on a CommentsPosted redelivery — a row already carrying the ref now wins over the newest unattached one — round 1
- [security/L7] OperatorIdentities reported a database outage as "unlinked", telling an operator to request a mapping they already had — the lookup now raises a marked failure the read path turns into 503, while authorization still fails closed — round 1
- [security/L8] The JWT identity path was untested because @TestSecurity yields a QuarkusPrincipal — OidcSubjectsTest covers the sub-over-name rule and both fallbacks — round 1
- [security/L9] /rescan was an unbounded aggregate — SPIRE_MEMORY_WINDOW_DAYS bounds it, and also keeps a preference reflecting what the team believes now — round 1
- [qa] AnalyticsQueries arithmetic untested — AnalyticsQueriesTest covers the null-vs-zero rate, rounds-taken median, per-repo and per-platform isolation, the uncategorized row and archive exclusion — round 1
- [rules/2] ResultSaga.onReviewGenerated over 30 lines — the corpus-then-hide block extracted, with the load-bearing ordering documented on the extraction — round 1
- [rules/3] recordConversationFinding (7 params) and markSuppressed (5) — ConversationFinding and SuppressionBatch parameter objects — round 1
- [rules/4] recordVerdicts, insertAll, scan and totals over 30 lines — verdict logic to FindingVerdicts, suppression to FindingSuppressions, SQL lifted to constants, row binding split out — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- (none)

## Deferred with a reason (not dismissed — genuinely open)

- (none) — every finding from round 1 is fixed. `ResultSaga.handle` (221 lines), `onReviewFailed` (53)
  and `ResultSaga.java` itself (759) remain over the size limits, but all three were already so on
  `master` before this branch: pre-existing debt this PR neither introduced nor widened.
