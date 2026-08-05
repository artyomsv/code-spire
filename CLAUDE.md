# Code Spire — project context

> **"Code Spire" is a working name, not the final product name.** Treat it as provisional: don't
> register trademarks, buy domains, or design brand assets around it, and don't add new user-visible
> occurrences of it. The user-facing name lives in **six production literals across four files**:
> `PromptCatalog.REVIEW_PERSONA` and `.FOLLOWUP_PERSONA` ("You are Code Spire…"), `ReviewWorker`'s
> summary header (`"### Code Spire review"`, also asserted in `FindingConversation.test.ts`), the bot
> display name in `FindingConversation.tsx` and `render.tsx`, and copy in `PromptsSettings.tsx`.
> Renaming means all six — it spans backend *and* UI. Centralising them into one constant is the
> obvious cheap win if a rename looks likely. The internal surface (`dev.codespire` package group,
> `spire-*` modules, `SPIRE_*` env vars, docker volumes) is private and need not follow a product
> rename.

Self-hosted, event-driven, plugin-first AI code reviewer. One bot account reviews every PR in a
workspace via webhooks (no per-seat licensing); SCM platform, LLM provider, context sources, and
storage are pluggable. Bitbucket Cloud first. **Source-available, split per module** (ADR-021):
Apache-2.0 for the plugin SPI, libraries and reference adapters; FSL-1.1-ALv2 for the runnable
services — see `LICENSING.md`. Never call the project "open source" in docs or UI.

## Read first

The design is fully specified in `docs/` — **treat those files as the source of truth**:

| Doc | Contents |
|---|---|
| `docs/PRD.md` | Problem, users, goals, FR-1..13 / NFR-1..9, scope, success criteria |
| `docs/ARCHITECTURE.md` | Event-driven plugin-first core; module layout; build sequencing |
| `docs/EVENT-MODEL.md` (+ `docs/diagrams/event-model.html`) | Slices S1–S11 in Event Modeling notation |
| `docs/CONTRACT.md` | Event/command catalog, `ReviewLifecycle` decide table, SPI ports, topics, Bitbucket mapping |
| `docs/DATA-MODEL.md` | Value types, event store, object store, read models, encryption boundaries |
| `docs/SCM-MAPPING.md` | Provider-neutral SCM model verified against Bitbucket/GitHub/GitLab/DC APIs |
| `docs/SECURITY.md` | Trust boundaries, OIDC/RBAC, Tink encryption, LLM threat model, cost gaps |
| `docs/REPO-RULES.md` | The `.codespire` file: format, the target-branch rule and why, writing effective rules |
| `docs/DECISIONS.md` | ADR-001..020 — every locked decision with its why |
| `docs/RESEARCH.md` | Market landscape + the PR-Agent code evaluation that justified greenfield |
| `docs/ROADMAP.md` | Phases P0–P4 with exit criteria |
| `docs/CICD-AND-PACKAGING.md` | **Parked plan.** No CI exists today; analysis of GitHub Actions + GHCR images + Helm/kustomize/ArgoCD, why Terraform is declined, and why it waits for D10 |
| `docs/D10-AUTH-PLAN.md` | **Planned, not started.** The auth gate: hybrid OIDC, per-service URL prefixes so cookie scoping is real, the spike that must precede code, and the two designs review falsified |

## Status (keep current)

- **Phase 0 delivered:** `spire-contract` (pure domain lib: events, commands, hand-rolled
  Decider/View/Saga, SPI ports, `ReviewLifecycle` decider + GWT tests) and `spire-orchestrator`
  (single-process pipeline over SmallRye in-memory channels, Postgres event store with optimistic
  concurrency, live WebSocket timeline dashboard).
- **Phase 1 feature delivered (single-process):** `spire-diff` (unified-diff parser with dual line
  numbers, token clip, prompt renderer, anchor resolver; PR-Agent studied as prior art, no upstream code used),
  `spire-scm-bitbucket` (real Bitbucket Cloud `ScmIngress` with HMAC verify + bot-drop + /command
  parse, `DiffSource`, `CommentSink` per SCM-MAPPING), `spire-llm` (LangChain4j OpenAI-compatible
  `LlmProvider`, injection-fenced review prompt, lenient findings parser), orchestrator wiring
  (`/webhooks/bitbucket` returning 202, real `ReviewWorker` with comment_idempotency
  insert-before-post + stale-run pre-check, dev/test stub toggles `spire.scm.stub` /
  `spire.llm.provider` — real SCMs/LLMs are the UI registry, not config). Exit criterion green:
  `BitbucketWebhookE2ETest` — signed webhook → real adapters (WireMock Bitbucket) → inline+summary
  posted exactly once, duplicate delivery posts nothing.
- **Phase 1 code-reviewed:** 4-agent review (security-officer, code-reviewer, rules-compliance, qa);
  all 15 findings fixed — recovery-aware comment idempotency (reclaimable NULL claims + id reuse),
  per-finding post isolation + retryable classification, PR-head re-check before LLM/post,
  prompt-fence sentinel neutralization, host-pinned redirects, HTML-escaped model output, unordered
  dispatcher. Semgrep clean. **86 tests green** across 17 suites (incl. webhook E2E + idempotency
  integration). Two LOW items tracked in `techdebt/`.
- **P1 service split delivered:** three deployables over the Kafka protocol — `spire-gateway`
  (webhook -> cs.integration, :34081), `spire-orchestrator` (deciders/sagas/event store/dashboard,
  cs.commands + cs.events, :34080), `spire-review-worker` (cs.commands -> adapters -> cs.results,
  own `worker` schema for comment_idempotency, :34082). Wire format = polymorphic JSON
  (type discriminator on the sealed hierarchies); everything keyed by reviewId. The ADR-013
  stale-run guard lives in the orchestrator's ResultSaga (it owns the aggregate); the worker keeps
  the PR-head re-check. Split tests per service run against Testcontainers Kafka + Postgres
  (gateway webhook->topic, orchestrator choreography incl. stale-drop, worker command->result incl.
  idempotent redelivery). Redpanda in docker-compose at :34092.
- **Split code-reviewed (4 agents) and hardened:** poison records never kill consumers
  (never-throw deserializers) and processing failures go to **cs.dlq** (never silently dropped,
  ADR-013); repo/prId derive from the reviewId itself (`ReviewIds.parse` — no in-memory registry,
  nothing lost on restart); ordered per-partition dispatch (no same-review races); the paid LLM
  call has its own idempotency claim (no duplicate spend on redelivery); gateway awaits broker acks
  before the 202 (Bitbucket retries on failure) and holds only the webhook secret + bot account id
  (never the App Password); work queues use `latest` offsets (no side-effect replay for new groups);
  per-service HTTP port vars. Semgrep clean.
- **Encryption at rest delivered:** event-store payloads and provider secrets are Tink
  AES-256-GCM encrypted with AAD binding (stream id / provider id / workspace); the base64
  Tink keyset (`SPIRE_ENCRYPTION_KEYSET`) is the single fail-fast bootstrap secret. Legacy
  plaintext rows (`key_id='none'`) still read back.
- **Provider registry + multi-SCM delivered:** encrypted provider registry with Settings ->
  Providers CRUD (`ProviderResource`; secrets never returned — `hasSecret` only), bot identity
  auto-resolved from the token on save (`IdentitySource` / `ProviderIdentityResolver`),
  `spire-scm-github` adapter (client, diff source, comment sink), review modes
  `SPIRE_REVIEW_MODE=active|observe`, bounded auto-retry per review
  (`SPIRE_REVIEW_MAX_ATTEMPTS`, ADR-016), per-provider PR-author allowlist in the DB.
- **`spire-ui` delivered:** React/Vite dashboard (reviews list/detail with live WebSocket
  updates, Register PR dialog, provider settings) against the orchestrator's REST + WS APIs;
  vitest + `tsc --noEmit` in CI-shape, dev server on `:34000` (`UI_PORT`).
- **Full-project review hardened (2026-07):** 4-agent audit, all findings fixed — SSRF guard
  on provider base URLs (https + public host enforced; `spire.security.allow-insecure-provider-urls`
  relaxes only in `%dev`/`%test`), provider-neutral `ScmApiException` so GitHub 404/5xx/429
  classify like Bitbucket, outbound Kafka publishes await broker acks (failures DLQ or 5xx —
  never silent), `events-in` DLQ'd instead of ignored, LLM idempotency marks-before-emit with
  persisted-result re-emit on redelivery (no duplicate spend, no stall), per-call LLM
  `maxTokens` cap, `review_event.seq` race closed (V6 unique constraint + atomic insert),
  redirect hardening in SCM clients (GET-only, private-IP guard, port-normalized auth pinning),
  structured JSON logging + reviewId MDC (prod profile), UI URL-scheme guard + fetch-race fixes,
  npm audit 0 vulnerabilities.
- **First ContextProvider — Jira delivered (B6, 2026-07-08):** the P1 context stub is now the real
  aggregator. `spire-context-jira` (framework-free, JDK HttpClient + Jackson, SSRF-guarded like the SCM
  adapters) resolves PR-referenced issue keys (`PROJ-123`, parsed from title/branch at diff-fetch) into
  `ContextItem`s via the Jira v2 REST API (baseUrl-driven Cloud + Data Center, basic/bearer). `ContextWorker`
  fans out to the supported providers under a bounded 20s timeout and persists the assembled context
  encrypted (Tink, AAD=reviewId) to a Postgres `BlobStore` (`worker.context_blob`); `ReviewWorker` loads
  it into the untrusted-fenced prompt slot. Credentials live in a new encrypted context-provider registry
  (Settings → Context, `/api/context-providers`, global default) brokered per-command like SCM/LLM (ADR-015).
  Blob deletion is keyed by `review_id` at all three sites (delete, re-run, re-assembly) — no orphans.
  Per-instance **project keys** (`ACME`) narrow candidate keys; a live **connectivity check**
  (`/{id}/check`) and a **preview/test** endpoint (`/{id}/preview` — resolve a ticket number via the
  pattern and show the exact `ContextItem` a review would inject) back the Settings → Context UI.
- **Unified keyed webhook ingress (2026-07-16):** all three SCMs now share the gateway's
  per-repo registry edge `/webhooks/{provider}/{key}` (key resolves the encrypted per-repo
  secret + scope from `webhook_repo`). `GitLabIngress` added (`X-Gitlab-Token` constant-time
  compare — GitLab does not sign the body — + Merge Request / Note translation; `update`⇒UPDATED
  only when `oldrev` is present) with `GitLabWebhookResource`; the shared `RegistryWebhookEdge`
  (resolve→verify→translate→scope→publish) backs the GitHub, GitLab and Bitbucket resources. The
  **legacy single-secret `/webhooks/bitbucket` edge was removed** (`WebhookResource` +
  `GatewayScmProducer` + `SPIRE_SCM_BITBUCKET_WEBHOOK_SECRET`) — Bitbucket now registers like the
  others (`bitbucket-cloud` provider). Dev webhook exposure: opt-in Cloudflare quick-tunnel
  service (`--profile tunnel`) forwarding to `gateway:39281`.
- **Re-review reconciliation delivered (ADR-019, 2026-07-18):** a follow-up commit now reconciles
  instead of blindly re-reviewing — the orchestrator command-carries the last posted run's snapshot
  (`PriorRun`, from `review_status.posted_findings_json`, commit-guarded) into `GenerateReview`; the
  worker runs a claim-guarded reconcile call (prior findings + threads + incremental diff via
  `DiffSource.fetchCompareDiff`, full-diff fallback on force-push) for per-finding verdicts, then the
  review call with an exclusion list. `PostComments` resolves-then-replies closing verdicts (Bitbucket
  degrades to reply-only), always replies `STILL_OPEN`, updates the summary in place. V19; UI card.
- **GitHub integration finalized (2026-07-21..22):** a live-use audit's 12 findings are closed —
  403/GraphQL `RATE_LIMITED` now classify as retryable (was 429-only), backed by a throttled,
  Retry-After-aware inline-posting backoff; `/review` PR comments force a re-run; draft PRs skip
  until `ready_for_review` (`SPIRE_REVIEW_DRAFT_PRS` restores always-review); OLD-side/multi-line
  anchors and honest 406/pagination failures; plain PR comments now get conversational answers in
  the summary thread. GitLab/Bitbucket parity tracked in ROADMAP.md item 13.
- **PR-12 review-fix batch (2026-07-22..23):** reviews-list rows now show reconciled open-finding
  counts and cumulative LLM cost instead of overwritten last-run columns; `STILL_OPEN` downgrades
  to `UNCHANGED` at hunk (not file) granularity; the Findings card shows an in-progress state
  instead of a false clean; a transient `answering` flag (V21) drives a responding indicator.
- **PR-state badge (2026-07-23):** a distinct `pr_state` (OPEN/MERGED/CLOSED, V22) on the review
  read-model, set from the open/close webhook events across all three SCMs, shown as its own
  badge separate from the review status; cancel-on-close is unchanged.
- **GitLab + Bitbucket full-flow parity (2026-07-23, ROADMAP item 13):** both adapters brought to
  the finalized GitHub adapter's feature set so the full loop (webhook → review → conversation →
  reconciliation) can be manually tested on each. `GitLabCommentSink`/`BitbucketCloudCommentSink`
  now implement `ThreadSource` (the shared `FollowUpWorker`/`ConversationSaga` were untouched — the
  conversation loop lights up purely via the `instanceof ThreadSource` gate); GitLab uses a
  discussion-vs-plain-note 404 fallback for summary-thread reads/replies, Bitbucket rebuilds the
  comment subtree by `parent.id`. GitLab ingress now emits `AuthorReplied` for non-command MR notes
  (threaded→`topLevel=false` keyed to `discussion_id`, individual→`topLevel=true`); Bitbucket sets
  `topLevel=true` for plain PR comments. Draft/WIP skip now covers all three SCMs (reuses
  `spire.review.draft-prs`; GitLab handles the draft→ready flip, Bitbucket the non-draft
  `pullrequest:updated`). GitLab/Bitbucket API exceptions now carry `retryAfterSeconds`
  (`Retry-After`, plus GitLab `RateLimit-Reset` epoch fallback). GitLab posts NEW-side multi-line
  findings as a `position.line_range`. Bitbucket inline stays single-anchor (API constraint). New
  runbook Mode F (GitLab webhook) + conversation/reconciliation steps for GitLab/Bitbucket.
  WireMock-tested per adapter; live testing is the operator's runbook pass. (Two claims here were
  superseded on 2026-07-25 — see the next entry: Bitbucket reconciliation is no longer reply-only,
  and the compare-direction gate is settled live.)
- **Parity verified live on two SCMs + 10 fixes (2026-07-25):** the full loop was run end-to-end on a
  real GitHub PR and a real Bitbucket PR with identical file content — 11/11 scenarios on both
  (runbook **Mode G**, `docs/SMOKE-TEST.md`). Everything the run exposed is fixed, each with tests:
  - **Cross-provider resolution.** A workspace name registered on two SCMs (a GitHub org and a
    Bitbucket workspace both `artyomsv`) resolved to the *oldest* provider, cross-wiring SCMs. The
    conversation saga, the self-loop guard and the thread-refetch endpoint now share
    `ReviewProviderResolver`, which disambiguates by the review's stored `provider_type` — the way the
    credential path already did.
  - **Conversation is keyed to its root thread (V24 `review_thread.root_ref`).** Bitbucket threads by
    *immediate parent*, so a reply to the bot's own answer carried that answer's id: multi-turn died
    after one exchange, the turn counter never accumulated (the cap could not fire), later turns were
    stored under a non-finding ref, and `fetchThread` saw only the last branch. Answers are now linked
    to their conversation root, and turn counting / ownership / the command's `threadRef` / event
    attribution all normalize to it — the single stable id GitHub's `in_reply_to_id` already gave us.
  - **Bitbucket thread resolve** (`POST .../comments/{id}/resolve`) — a fixed finding now shows
    **Resolved**, not just the SCM's auto-*Outdated* badge. **GitHub `resolveThread`** no longer
    reports `ALREADY_RESOLVED` when it matched *nothing* (a fake `resolved:true` that silently skipped
    both the resolve and the reply); it degrades honestly to reply-only.
  - **Re-posted findings reconcile against their current thread.** Several `review_thread` rows can
    share one anchor across rounds; the loc→thread index kept an arbitrary older id and a stale carried
    ref won over the live one, so verdicts targeted an already-resolved thread. Newest id per loc wins.
  - **Bitbucket `@{account_id}` mentions** are recognized (it renders no `@login` in raw text), so an
    @-mention on an unflagged line engages the bot as on GitHub.
  - **Follow-up replies must fence code** — the locked FOLLOWUP contract said *"no markdown fences"*,
    so the model indented code and Bitbucket rendered it as prose.
  - **UI:** general-discussion threads re-fetch their full text (they showed only the ≤160-char
    preview) with the opening message always visible and replies behind the toggle; a finding's
    conversation no longer also appears under General discussion (both cards now derive from one row
    set); a reconciliation reply is reachable from the findings card ("View thread"); the
    `responding…` pill wraps instead of overflowing the reviews table.
  - **Diagnosability:** `ConversationSaga` now logs every decision with its factors
    (`level/authorAllowed/threadIsOurs/mentioned/priorTurns`) — the skips used to reach only the
    dashboard, which is why several of these bugs were invisible.
- **Provider-neutrality enforced by the build (ADR-020, 2026-07-26):** new `spire-arch` module fails
  the build when a core module (`spire-contract`, `spire-orchestrator`, `spire-review-worker`) names
  an SCM provider outside an explicit, reasoned allowlist (the composition roots `ProviderClients` /
  `WorkerScmClients` / `PrUrlParsers`, plus `ScmType` and the dev `StubScm`). It scans **source
  text**, not bytecode, because the leaks that caused real bugs were string literals; comments are
  exempt. Guards against a silent pass: the comment stripper is unit-tested, the scan asserts it
  reached every core module, a stale allowlist entry fails, and the scanned tree is a declared Gradle
  input (otherwise the check reports a cached pass after the very change it should catch). The three
  leaks it found are fixed: `ManualRegisterResource` classified only Bitbucket errors, so a
  GitHub/GitLab 404 escaped as a **500** instead of 404 (real defect, now on the neutral
  `ScmApiException`); `ProviderIdentityResolver`'s `"bitbucket-cloud"` branch became the defaulted SPI
  method `IdentitySource.whoamiOrValidate(workspace)`, with the account-less-token fallback moved into
  `BitbucketCloudDiffSource` (`ProviderClients.assertWorkspaceAccess` deleted); `ProviderResource`'s
  duplicate type list is now `ProviderClients.SUPPORTED_TYPES`.
- **Semantic leaks swept (2026-07-26):** a 4-lens audit hunted the leaks that carry NO provider name,
  which the build check cannot see. Six fixed:
  - **A live GitLab defect.** `ReviewProjection.newerThreadRef` picked the current thread for a
    re-posted finding by comparing thread refs as `BigInteger` ("ids are monotonic"). GitLab's thread
    ref is an opaque discussion id, so every compare threw and fell back to "first seen" — and rows
    were read `ORDER BY thread_ref`, i.e. the lexicographically smallest. The ADR-019 reconciliation
    fix was **inert on GitLab**, able to target an already-resolved thread. Recency is now insertion
    order (`V26 review_thread.seq`, newest row per loc wins); no id arithmetic anywhere.
  - **`DiffRefs` deleted.** The `(baseSha, startSha, headSha)` triple was GitLab's `position`, non-null
    at exactly one construction site repo-wide. `Diff`/`PullRequest`/`PullRequestEventReceived` and
    `CommentSink.postInline` now carry a single `headCommit`; `GitLabCommentSink` reads `diff_refs`
    from the MR itself, cached per MR (one extra GET per review, not per finding).
  - **Mention syntax left the core.** Each ingress extracts `@`-mentions in its own syntax into
    `AuthorReplied.mentions` (Bitbucket's braced `@{account_id}` included); `ConversationSaga`
    is now a membership test with no regex.
  - **406 → `ScmApiException.isDiffTooLarge()`** (defaulted false, GitHub overrides) — core no longer
    interprets one provider's status code, and GitLab already reported the same condition as data.
  - **Summary thread ref.** `CommentSink.updateComment` takes a `ThreadRef`, so one opaque ref both
    locates the conversation and names the comment to rewrite; the worker records
    `summary.thread().value()` and core stops casting a comment id to a `ThreadRef`
    (`CommentsPosted.summaryThreadRef`). The persisted `ReviewCompleted.summaryCommentId` keeps its
    name — renaming it would break event-store replay.
  - **`spire-gateway` added to the scanned modules**, its shared registry edge no longer holding a
    provider-name list (`WebhookProviders.SUPPORTED_TYPES`, composed from each endpoint's own constant).
  740 tests green across 97 suites. Allowlist: 9 entries, all composition roots or `ScmType`.
- **Context axis brought under the same rule (2026-07-26):** the check now also fails on `jira` /
  `confluence` in core, and the pipeline no longer parses either. New credential-free SPI
  `ContextReferenceSource` (`referencesIn` + `normalize`) — separate from `ContextProvider` because
  extraction runs at diff-fetch, *before* context credentials are brokered, so there is no configured
  provider to ask. `JiraReferenceSource` / `ConfluenceReferenceSource` implement it; the
  `WorkerContextReferences` composition root lists them and does the cross-round dedup in each
  extractor's own normalized form. `ticketKeys` + `links` collapse to one neutral `references` set on
  `DiffFetched` / `GatherContext` / `ContextRequest`, which each provider narrows to what it
  recognises (`JiraContextProvider` to key-shaped entries + project keys, `ConfluenceContextProvider`
  to page ids on its host). `DiffWorker` and `ContextWorker` are now free of any source's syntax;
  `WorkerContextClients`, `WorkerContextReferences`, `ContextProviderResource` and
  `ContextKeyValidator` are the context composition roots and are allowlisted. **747 tests green
  across 98 suites**; allowlist 13 entries, every one a composition root or `ScmType`.
- **Three-provider parity pass + 3 fixes (2026-07-26, runbook Mode G):** S1–S11 run end to end on a
  real GitHub PR, GitLab MR and Bitbucket PR. 11/11 behaviourally on all three; every reconcile
  verdict except `ACKNOWLEDGED` exercised (`SUPERSEDED` correctly never fired), 14 thread resolves
  across three different resolve mechanisms, a finding born mid-reconciliation closed two rounds
  later, and a 100%-similarity rename that did **not** churn finding identity. What the run exposed
  is fixed, each with tests:
  - **The turn cap was silent.** Reaching it recorded a dashboard note and posted nothing, so the bot
    just stopped replying — indistinguishable from a lost webhook (a dead tunnel produced the exact
    same symptom mid-run). New `NotifyTurnCap` command → fixed-text notice, no LLM credential, one
    claim per **thread** so later replies don't repeat it; result event is `TurnCapNotified` not
    `FollowUpPosted` (the latter bumps the turn count — the notice must not consume a turn). An
    explicit @-mention now overrides the cap, and cap-vs-decline log differently.
  - **GitLab's compare diff parsed to ZERO files.** `fetchCompareDiff` emitted only `---`/`+++`;
    `UnifiedDiffParser` keys on `diff --git`. Read as text (the reconcile prompt) it worked, so the
    notes were right; parsed (`changedOldSideRanges`) it was empty, so `downgradeUntouched` rewrote
    **every** `STILL_OPEN` to `UNCHANGED` on GitLab alone — an author who partly fixed a finding was
    told nothing. Now calls `synthesizeUnifiedDiff`, which existed for exactly this. The old test
    asserted the text contained `---`/`+++`/`@@` — all true of a string that parses to nothing; it
    now asserts the diff *parses*.
  - **Follow-up replies overreached.** `FOLLOWUP` had no "already reported" block (the `REVIEW`
    prompt always had one) and `AnswerFollowUp` no field to build it from, so a narrow question got a
    survey of every defect in the file. The command now carries the findings owned by *other* threads
    (reused from the ADR-019 posted-run snapshot; this thread's own filtered out), the prompt lists
    them as off-limits, an anchored thread sees only **its own file**, and replies open by naming the
    asker in plain text (never an @-mention — syntax is per-provider). Both review and reconcile
    personas now lead with the fix the code's expressed intent points to rather than the smallest
    edit that compiles; A/B'd against recorded controls on identical input, finding count and
    severities unchanged. **763 tests green across 97 suites.**
- **Split licensing (ADR-021, 2026-07-26):** the repo is **source-available, not open source**, and
  licensed per module — Apache-2.0 for the plugin SPI, libraries and reference adapters
  (`spire-contract`, `spire-diff`, `spire-encryption`, `spire-scm-*`, `spire-context-*`, `spire-llm`,
  `spire-arch`), FSL-1.1-ALv2 for the four deployables (`spire-gateway`, `spire-orchestrator`,
  `spire-review-worker`, `spire-ui`). Each module carries its own `LICENSE`; the map and reasoning
  are in `LICENSING.md`. **Invariant: no Apache-2.0 module may depend on a service module** —
  permissive flows into restrictive, never the reverse. FSL permits self-hosting, internal commercial
  use, forking, teaching and consulting; it forbids reselling as a competing product or hosted
  service, and each version converts to Apache-2.0 two years after release. Versions published before
  this stay Apache-2.0 (`v0.1.0-apache` tags the boundary). Contributions require DCO sign-off plus a
  relicensing grant (`CONTRIBUTING.md`), without which the split cannot be maintained. The same pass
  corrected the PR-Agent provenance language across the docs: it was **read as prior art, no upstream
  code was used** (the old "ported the IP" / "port ~1,500 lines of prompt templates" wording described
  a plan that was never executed) — credit now lives in `NOTICE`. The shipped code was then compared
  against PR-Agent v0.38.0's source and the result recorded in **`docs/RESEARCH.md` §4**: the two
  share exactly the `__new hunk__`/`__old hunk__` prompt markers and the `0.9` clip safety factor, and
  differ everywhere else (typed `FilePatch`/`Hunk`/`DiffLine` model vs upstream's string-to-string
  patch rewriting; chars-per-token heuristic vs a real tokenizer; JSON/Jackson vs YAML/`try_fix_yaml`;
  own prompts with injection fencing upstream lacks, plus `RECONCILE`/`FOLLOWUP` kinds a single-shot
  reviewer has no counterpart for; and no architectural correspondence at all). Cite §4 rather than
  re-deriving it. Open item: the name is not trademarked, and no licence stops a fork from using it.
- **Operator attention panel (2026-07-27):** a topbar bell in `spire-ui` whose every row is a
  condition true *right now*, derived on demand — nothing stored, nothing to dismiss, so fixing
  the cause removes the row. Two same-shape feeds (`AttentionView` in `spire-contract`) merged
  client-side: `GET /api/attention` (orchestrator — no usable default LLM provider, no SCM
  provider, unresolved bot identity, rejected credential, stuck/failed reviews, pending DLQ) and
  `GET /api/webhook-repos/attention` (gateway — registrations with no secret or refusing
  deliveries). **No new topic and no non-`reviewId` message class:** most of the catalog is
  *state*, not events, so each service answers for its own schema over the HTTP surface it
  already has. Credential health rides on work already happening, but not identically per
  registry: the SCM and context registries record a check both on save and on their own Check
  button, plus (SCM only) from a real review's 401 (new neutral `ScmApiException.isUnauthorized()`,
  **401-only** because one provider overloads 403 for rate limiting; carried by
  `ReviewFailed.credentialRejected`). `spire-llm` wraps LangChain4j's untyped runtime exceptions,
  not `ScmApiException`, so a review's LLM 401 can never mark its provider that way, and its save
  path validates synchronously (rejects a bad key up front) without persisting a check record —
  the new `llm_provider` Check endpoint is the *only* path that ever records a verified LLM
  credential. Gateway rejections are state on `webhook_repo` (V2) that a verified delivery clears,
  so the row self-clears when the secret is rotated. V28 adds three-valued `last_check_ok` (NULL
  never checked / TRUE passed / FALSE rejected) to all three registries. Deliberately excluded:
  `CREDENTIAL_UNVERIFIED` as a row (wallpaper — it lives inline on the settings pages), per-review
  facts like the turn cap, and dead-tunnel detection (absence of traffic is indistinguishable from
  a quiet afternoon; `REVIEW_STUCK` is the honest proxy).
- **GitHub Issues + GitLab Issues context providers (2026-07-30):** the ContextProvider SPI's
  Jira/Confluence precedent proven a second time over — two new Apache-2.0 modules,
  `spire-context-github` and `spire-context-gitlab`, resolve a PR/MR's referenced issues, pull/merge
  requests and (GitLab) epics into `ContextItem`s. Closes ROADMAP item 14. Each platform gets its own
  reference grammar (`GitHubIssueRefs`/`GitLabIssueRefs`, the counterpart to `JiraTicketKeys`): GitHub's
  bare `#123`, qualified `owner/repo#123`, and issue/pull-request URLs; GitLab's three sigils (`#123`
  issue, `!123` merge request, `&123` epic), its multi-segment qualified `group/subgroup/project#123`
  (namespaces nest, unlike GitHub's flat `owner/repo`), and their URL forms including a group-scoped
  epic URL. A bare reference is **repository-relative**, and that is only safe to resolve with a new
  `ScmType` carried onto `GatherContext`/`ContextRequest`: the same `workspace/slug` routinely exists on
  two platforms, so a bare `#123` on a GitLab MR must not silently resolve against a same-named GitHub
  repository just because both are registered. The gate is per-*reference*, not per-provider — a
  qualified reference or a URL names its own repository and needs no platform match, only a bare one
  does (both providers' tests assert this distinction directly). `ContextItem` gained three neutral
  kinds, `ISSUE` / `PULL_REQUEST` / `EPIC` (GitLab's own term "merge request" stays out of core's
  vocabulary, matching the house style already set by `RULE`/`CODE_SNIPPET`). GitLab epics are a
  Premium-tier feature; on a free-tier instance the fetch tries the nearest ancestor group outward and
  a 403/404 skips just that one reference, not the whole contribution — an operator without epic
  access still gets their issue and MR context. Both providers reuse the registry's generic
  `projectKeys` column as an owner/repo or group/project allow-list (no migration), reject `basic` auth
  **on save** (`BEARER_ONLY_TYPES` — both APIs are bearer-token-only), Check against `/user` and
  `/api/v4/user`, and Preview rejects a bare reference with actionable guidance instead of a silent
  empty result. Ahead of the two adapters, the pinned-redirect SSRF-guarded HTTP client that Jira and
  Confluence each carried its own copy of was extracted into a new Apache-2.0 module, `spire-http`
  (`PinnedJsonClient`) — one of three Apache-2.0 modules this branch adds (with
  `spire-context-github`/`spire-context-gitlab`), bringing the total to thirteen per `LICENSING.md` —
  so the guard has one home for the context adapters instead of four near-identical copies once these
  two providers landed; Jira and Confluence were migrated onto it in the same pass. The three SCM
  clients (Bitbucket/GitHub/GitLab) still carry their own unguarded copy of the redirect-resolve —
  tracked as tech debt (`techdebt/global/`), not silently left undocumented. `spire-arch`'s
  provider-neutrality allowlist needed **no new entries** — the existing composition-root exemptions
  already covered the new types. The live-verification runbook's original plan called for opening
  "the review's LLM call record" to confirm a context item reached the model — no such record
  exists (`review_llm_call` stores only token counts/cost; the rendered prompt is never logged or
  persisted anywhere). The permanent replacement is a worker-level seam test,
  `ReviewWorkerTest.assembledContextReachesThePromptSentToTheModel`, which fakes a `BlobStore`
  holding an `AssembledContext` and asserts the captured `Prompt` handed to the LLM client contains
  the context item's title and body; confirmed to discriminate (fails when `contextRef` is null).
  The gap itself was tracked as tech debt rather than worked around, and has **since been closed** by
  `PromptLog` (opt-in, off by default — the rendered prompt quotes source code and retrieved ticket
  text, so it is an operator's explicit choice, not a default). 948 Java tests
  green across 114 suites; 152 `spire-ui` vitest tests across 26 files; `tsc --noEmit` silent.
  Runbook: SMOKE-TEST.md **Mode I**.
- **Repo rules — the `.codespire` file (2026-08-01, Phase 2's last unbuilt item):** a repository states
  its own conventions in a `.codespire` file at its root, contributed as
  `ContextContributed{source=RULES}` / `ContextItem{kind=RULE}` by `RulesContextProvider` — a
  credential-free provider, because the rules ride in on `DiffFetched.repoRules` rather than being
  fetched by the aggregator. Read from the PR's **target branch, never the reviewed commit**: the head
  is written by the change under review, so rules taken from it would let a PR rewrite the reviewer's
  instructions in the same PR. Prompt fencing cannot cover that — rules are *meant* to steer the
  review, so the defence has to be *where they are read from*, not how they are quoted. New SPI method
  `DiffSource.fetchTextFileOnBranch` on all three adapters (absent file ⇒ empty, not an error). Format
  and guidance in `docs/REPO-RULES.md`.
- **Debt-and-guard wave (2026-08-02):** ten commits, no roadmap advance — three user-visible defects
  and four *guards*, i.e. build checks that fail on a debt's **reintroduction**, not merely its
  removal. Defects: `spire-diff` silently parsed a headerless diff to **zero files** (it keys on
  `diff --git`; now falls back to a `---`/`+++`/`@@` detector and warns when a non-empty diff yields
  no patches); the Context card never live-updated within a run (its only key was the commit, which
  does not move mid-run — now also keyed to the Context stage completing, since the assembled context
  lives in the *worker* while the socket carries only *orchestrator* state); the real adapters'
  `apiHost()` was covered by fakes alone. Guards: `PureModulesAreFrameworkFreeTest` enforces the
  framework-free boundary `spire-contract`/`spire-diff` claim, with **`jackson-annotations` as one
  documented, allowlisted exception** (see Conventions); `RedirectHandlingHasOneHomeTest` fails a
  *fourth* hand-rolled redirect loop (the three SCM clients' existing copies stay allowlisted and
  tracked); `ContractSchemaSnapshotTest` had a **vacuity hole** — it iterated event types and
  `continue`d on an empty list, so zero types read as zero failures — now asserted non-empty. Plus a
  per-host **circuit breaker** (`ProviderCircuits`: 5 failures ⇒ 30s open, CAS-guarded single probe)
  wrapping the whole SCM retry ladder, keyed by a new no-default `DiffSource.apiHost()` — deliberately
  not a `default` method, since the obvious `type().name()` would collapse every instance of a
  platform onto one key and let one self-hosted GitLab open the circuit for all of them. `spire-ui`
  on React 19 + react-router 8 (`npm audit` 0). **1027 Java tests across 124 suites; 192 `spire-ui`
  vitest tests across 31 files; `tsc --noEmit` silent.**
- **Debt wave 2 (2026-08-03):** three tracked items closed, debt 6 → 4 with **nothing above Low**.
  - **The two largest forms and the route shell are covered** (`SettingsProviders` 585 lines,
    `RegisterPrDialog`, `App`) — validation, the secret-blank-on-edit rule (sending `secret: ''`
    would wipe a stored token), bearer-only coercion, base-URL preservation, and both halves of the
    cross-provider `providerType` carry. `App.routes.test.tsx` asserts each route mounts a screen via
    `main .content`, **not** just the topbar title: the title is derived from the pathname, so a
    deleted `<Route>` would leave it and the nav highlight looking right. A mutation also exposed
    that `vi.spyOn` re-wraps the *same* module function, so call history leaked between tests in a
    file and `not.toHaveBeenCalled()` was passing on test ordering — fixed centrally with
    `vi.restoreAllMocks()` in `vitest.setup.ts`.
  - **The circuit breaker now covers the LLM path** (`CircuitBreakingLlmProvider`, wrapping
    `WorkerLlmProvider.clientFor` so review *and* follow-up are covered by one wrap). Health is
    `LlmFailures.isProviderUnwell` — LangChain4j's `RetriableException` hierarchy plus I/O and
    timeouts; a rejected key is an **answer** and never opens the circuit. Two traps: the provider
    reports failure as a *failed future* rather than throwing (a naive wrap records every outage as
    a success and never opens, while looking installed), and `FollowUpWorker.isTransient` recognised
    neither `CircuitOpenException` nor LLM retriables, so an open circuit would have sent every
    follow-up straight to `cs.dlq`. The debt entry's own suggestion — reuse the breaker *inside*
    `spire-llm` — is not implementable: that module is Apache-2.0 and the breaker is worker-owned,
    which ADR-021 forbids. Comment posting stays unguarded, by design (see `techdebt/global/`).
  - **The reviews-list findings count is split into new vs carried-over** (`OpenCounts.carriedOver`
    → `ReviewSummary.carriedOverFindings` → `findCell`), so a total moving 1 → 2 between rounds no
    longer reads as "the fix made it worse". The halves always sum because the same anchor is counted
    once, attributed to this run. Rendered only when something *is* carried over.
  - **1039 Java tests across 125 suites; 228 `spire-ui` vitest tests across 35 files.** Every guard
    added here was mutation-verified — break the production line, confirm exactly one test fails.
- **Operator authentication delivered (D10, ADR-022, 2026-08-03):** the dashboard and every
  REST/WebSocket endpoint now require an operator identity — the gate that blocked any deployment
  beyond one machine. **Hybrid OIDC**, not the bearer design `SECURITY.md` originally specified: a
  browser cannot set an `Authorization` header on a WebSocket handshake, and four live surfaces are
  sockets, so the browser gets a cookie session while `curl`/CI keep bearer. Each service is its own
  OIDC client with its own cookie name and `cookie-path`, and owns one URL prefix — orchestrator
  `/api` (sockets moved to `/api/ws/*`), gateway `/gw`, worker `/wk`. **The prefixes are the security
  mechanism, not tidying:** cookies scope by host+path, not by backend, so while the gateway sat at
  `/api/webhook-repos` the browser sent the *orchestrator's* cookie to it; per-service encryption
  secrets don't help because the encrypted cookie **is** the credential. Policies are deny-by-default
  with `/webhooks/*` (an SCM has only an HMAC signature), `/q/health*` and `/api/me` explicitly
  public. Two roles decided by **three** rules: *can it spend money* (register, re-run, DLQ replay),
  *what's in the payload* — why `GET /api/dlq` is admin despite changing nothing, since a dead-letter
  row carries the raw wire record — and *is it configuration*, which makes **every registry admin-only
  including its reads** (SCM/LLM/context providers, models, prompts, webhook registrations, global
  settings). That third rule replaced an earlier call that the registries were viewer-readable because
  no secret is in the payload: true, but the wrong test — a listing is an inventory of every repo,
  endpoint and model the deployment reaches. A viewer sees reviews (list, detail, timeline, threads,
  context) and the attention panel; the dashboard hides the whole Configure section and bounces its
  routes, but `@RolesAllowed` is the control and hiding is only a courtesy.
  **A session is per prefix, and each must be established:** every service exposes
  `GET <prefix>/auth/login` (both roles, 303 to `/`) and the dashboard probes the siblings once signed
  in, navigating to any that refuse (silent SSO, `sessionStorage`-guarded against looping). Without
  this the gateway/worker screens were unreachable — neither `fetch` nor a WS handshake can follow the
  cross-origin redirect a missing session produces, so Webhooks reported "failed to fetch", a review's
  Context card failed alone, and the attention panel called a healthy gateway unreachable. All UI calls
  go through `apiFetch`, which carries the script marker and sends a refusal to the login of *the
  service that refused*. The UI **grants nothing by default** — `hasRole(null, …)` is false and guarded
  routes have an explicit unknown state, since defaulting to permitted flashed the full admin surface
  at a viewer for ~200ms; only `authEnabled:false` (dev) grants without a role.
  The UI knows its own session (`/api/me`), hides what
  a viewer may not do, and asks *why* a socket closed before reconnecting: the old blind 1.5s retry
  hammered the IdP on every routine 5-minute expiry while the attention panel reported it as a
  gateway outage. `%dev` runs unauthenticated (both gates open together — opening one leaves REST
  403ing while sockets still connect) and refuses to start that way outside dev/test. Realm +
  opt-in Keycloak in `docker-compose.idp.yml` (a separate file, not a profile: compose interpolates
  every service's vars regardless of profile, so a required credential would break a plain `up`).
  Preceded by a spike that overturned two of the plan's own predictions — `tenant-enabled=false` does
  not suffice (build-time `enabled=false` does), and `roles.source=accesstoken` is mandatory or login
  succeeds with **zero** roles and denies every operator. **1066 Java tests; 243 vitest.** Runbook:
  SMOKE-TEST **Mode J**. Open by design: TLS ships with the production edge.
- **CI/CD + packaging delivered (2026-08-05):** nine GitHub Actions workflows, four production images on
  GHCR, and a `deploy/` tree covering Compose, Helm and kustomize from one source of truth (chart →
  kustomize inflation → rendered YAML in `deploy/k8s/`, drift-checked by `render-manifests.sh --check`).
  The two things a future reader most needs, because both are invisible until they break:
  - **The `spire-ui` image is a reverse proxy, not a static server**, and its nginx config
    (`spire-ui/nginx/default.conf.template`) is a **security control**. ADR-022 scopes each service's
    session cookie to its own URL path, cookies scope by host *and* path, so the isolation only exists
    while all four services answer on **one origin** — which in dev is the Vite proxy and in a packaged
    run is that file. Three rules in it are load-bearing: `/webhooks` must route to the gateway and
    precede the SPA fallback (missing, every SCM delivery fails with 405 and no review starts);
    `X-Forwarded-Proto` must pass an upstream value through rather than derive from `$scheme` (deriving
    it breaks login *only* behind a TLS Ingress, where a plaintext check passes clean); and upstreams
    resolve at request time via a variable in `proxy_pass`, because a literal hostname makes nginx
    refuse to start when a sibling is not up yet.
  - **The chart never generates a secret**, and that is a refusal rather than an omission. Helm's
    `randAlphaNum` idiom would rotate `SPIRE_ENCRYPTION_KEYSET` on `helm upgrade` and make every
    encrypted event payload, provider secret and context blob permanently unreadable. Secret generation
    is safe for shared state and catastrophic for keys to existing data.

  `deploy/helm/spire/tests/render.sh` asserts eight invariants across **two** sources — rendered
  manifests for what the chart decides, in-repo config for what is baked into an image — and
  `--self-test` proves each catches its own break, because four are *negative* ("this value must be
  absent") and those pass trivially when a key is renamed. `deploy/e2e.sh` runs 21 checks against a
  running stack, covering what dev cannot: WebSocket upgrade through nginx, a token minted for one
  service refused by another, and the gateway's role denied on the `orchestrator` schema by Postgres
  itself. Two gates were weaker than they looked and were measured rather than trusted: **`helm lint`
  exits 0 with every required value missing** (so assertion 8 requires `helm template` to fail instead),
  and **the repo is not Semgrep-clean** under `p/default + p/secrets` — 45 of 54 findings are
  action-SHA-pinning advice, filed as `techdebt/global/4-2`, so Semgrep reports rather than blocks.
  A `%prod` lesson worth generalising: **`${VAR}` with no default does not enforce presence when the
  target is `Optional`** — `trusted-proxies` was silently empty with `proxy-address-forwarding` on, so
  the pairing is now a startup refusal. **1074 Java tests / 130 suites; 265 vitest / 37 files.**
- **Code-scanning backlog cleared (2026-08-05):** the Security tab's 120 open alerts are closed at
  source, in seven classes. **Every action reference is now a commit SHA** with the version in a
  trailing comment — the comment is not decoration, it is what Dependabot's `github-actions`
  ecosystem parses, and without it a pin is a permanently unpatched action. Tags were dereferenced
  through `repos/{owner}/{repo}/commits/{tag}`, not read off the ref: an annotated tag's ref points at
  the *tag object*, and pinning that SHA fails at runtime. Every dependabot entry gained a `cooldown`
  — the complementary defence, since pinning stops a tag being repointed while cooldown stops a
  freshly published version being proposed before anyone has looked at it, and it delays nothing that
  matters because GitHub exempts security updates from it. `AttentionQueries` no longer concatenates a
  table name into its SQL: a table name cannot be a bind parameter, so the old form could only assert
  its safety in a comment, and three whole-literal queries on a private enum make it checkable
  instead. All three `detect-insecure-websocket` hits were the scheme literal in **prose**, so they
  are written out rather than suppressed.
  **The Trivy half split by who can fix it.** The 39 OS-package alerts (libexpat, p11-kit) are
  inherited from `eclipse-temurin:25-jre-alpine`, which retags more slowly than Alpine's package index
  moves; `apk --no-cache upgrade` in the runtime stage closes them at build time, trading
  build-to-build reproducibility that the deployed digest still pins. `spire-ui` needs none of it —
  same scan job, clean base. The 24 Java-dependency alerts were mostly closed by the **platform**,
  3.37.1 → 3.38.1: netty, PostgreSQL and OpenTelemetry each ship as a stack whose modules must move
  together, and Quarkus imports its BOM with `enforcedPlatform`, so a `constraints` block loses every
  conflict and hand-forcing means overriding thirty-odd coordinates and hoping the combination was
  tested — upstream already did that and published it. Only jackson-core/databind (2.22.1) and
  lz4-java (1.11.1) have no platform release yet; they are forced in the root build, each with the CVE
  it closes and the warning that **a force does not go quiet when it stops being necessary — it starts
  pinning the version DOWN**. `techdebt/global/4-2` is deleted (debt 9 → 8). `semgrep.yml` still
  reports rather than blocks, now for the one honest remaining reason: `p/default` resolves from the
  Semgrep registry at run time, so a blocking gate would let a rule added upstream redden an untouched
  branch. Blocking wants a pinned ruleset first.
- **Still pending from P1 scope:** nothing. Call-level resilience shipped as a hand-rolled retry
  ladder + circuit breaker, **not** SmallRye Fault Tolerance — ADR-016 rejected per-call `@Retry` for
  the review budget, and the same reasoning held for the call level. Model pricing is delivered and
  deliberately operator-entered (ADR-018): a hardcoded cost table would silently mis-price every
  review as prices drift, which is the no-fabricated-data rule applied to money. Conversation-derived
  findings (a discussion that surfaces a real defect doesn't register one) remain open in
  `techdebt/global/`.

## Build & run

JDK 25 (SDKMAN `25.0.3-tem`) + Docker required.

```bash
cp .env.example .env                      # set POSTGRES_PASSWORD (dev-only)
docker compose up -d                      # Postgres :34432 + Redpanda :34092
./gradlew build                           # unit + per-service split tests (Testcontainers: Kafka + Postgres)
./gradlew :spire-orchestrator:quarkusDev  # dashboard at http://localhost:34080
./gradlew :spire-gateway:quarkusDev       # webhook edge :34081
./gradlew :spire-review-worker:quarkusDev # worker :34082
cd spire-ui && npm install && npm run dev # React dashboard :34000 (UI_PORT)
```

**Fast local verification** — the same two tiers CI runs, so this is the pre-commit loop:

```bash
./gradlew testFast                        # 13 Docker-free modules, ~25s
./gradlew testServices                    # the 3 deployables (Dev Services: Postgres + Kafka)
```

**The packaged stack** (`deploy/`, host ports 347xx — distinct from dev's 34xxx and 392xx):

```bash
cp deploy/.env.example deploy/.env        # every value required, no defaults
docker compose -f deploy/compose.yml --env-file deploy/.env up -d --build   # built here
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d      # from GHCR
./deploy/e2e.sh http://localhost:34700 http://localhost:34767               # 21 checks
./deploy/helm/spire/tests/render.sh --self-test                             # chart invariants
./deploy/render-manifests.sh --check                                        # rendered-manifest drift
```

## Conventions (enforced by design docs — do not regress)

- **Everything between components is an async event/command** — the only sync edge is webhook
  ingress returning 202. New capabilities subscribe to events; the core is never edited for them.
- **Domain events are appended ONLY by the aggregate** (single writer, ADR-010); workers emit
  integration events; sagas translate. All messages keyed by `reviewId`.
- **No hard-coded LLM/SCM provider** — config-selected, fail-fast when unset. No defaults for
  credentials anywhere; `.env.example` is the contract.
- **Diffs are never persisted** (ADR-011) — re-fetched by commit. Findings ride inline in events.
- **Sensitive fields (findings/context — may quote source) are Tink-encrypted at rest** in
  app-managed stores; the Kafka bus is covered by short retention + broker disk encryption
  (ADR-014), not app-layer crypto.
- **Money in millicents.** Host-exposed dev ports in the **34xxx** range.
- **Author identity** is data (stable `providerUserId`), never a gate; `email` never logged/persisted.
- Java 25 / Quarkus 3.38.1 / Gradle Kotlin DSL; **pure domain code stays free of framework imports** —
  build-enforced for `spire-contract` and `spire-diff` by `PureModulesAreFrameworkFreeTest`
  (`spire-arch`), which permits only the JDK, those modules themselves, and one documented
  exception: **`jackson-annotations`** (annotations only, no databind) on the sealed
  `IntegrationEvent` / `ActionCommand` hierarchies, because those types *are* the Kafka wire
  contract and their discriminators belong with them. Per-service mix-ins were considered and
  rejected: they spread one registry across every `ObjectMapper` in three services, where a missed
  site is a runtime wire break rather than a compile error. Adding a second exception means
  amending that allowlist, on purpose.
