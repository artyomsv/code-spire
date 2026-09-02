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
| `docs/TLS.md` | The five requirements a TLS terminator must satisfy, the identity-provider leg included, three worked topologies, and a symptom table. Code Spire terminates no TLS by design |
| `docs/REPO-RULES.md` | The `.codespire` file: format, the target-branch rule and why, writing effective rules |
| `docs/DECISIONS.md` | ADR-001..020 — every locked decision with its why |
| `docs/RESEARCH.md` | Market landscape + the PR-Agent code evaluation that justified greenfield |
| `docs/ROADMAP.md` | Phases P0–P4 with exit criteria |
| `docs/factory/` | **M0 delivered (2026-09-02), M1–M6 designed.** The software factory: work item → spec → plan → sandboxed agent runs → branch → PR reviewed by the existing reviewer. PRD (FR-F1..F32), architecture, module reference, execution layer (harness terms quoted with retrieval dates), run topology, autonomy model, product packaging, prior art, M0–M6 build order. Decisions are ADR-029..ADR-039. ROADMAP's M0 section records what the build taught that the design had wrong |
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
  SMOKE-TEST **Mode J**. TLS is the operator's edge by design (2026-08-23) — Code Spire terminates none; `docs/TLS.md` states the five requirements a terminator must satisfy.
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
- **LLM cost accounting rebuilt as a priced charge-line ledger (ADR-023, 2026-08-07):** the fleet
  cost/abuse caps item (ROADMAP "Explicitly deferred") turned out to need this first — reading what it
  would build on found four separate, individually-defensible places where *unknown* became *zero*
  (a blank UI field defaulting to `0`, REST accepting `0` as valid, a registry `null → 0L` coercion,
  and a `SQLException` answering `0L`), so a spend cap built on the old numbers would install cleanly
  and never fire for exactly the calls it exists to stop — the same failure shape as the LLM circuit
  breaker once recording a failed future as a success. `llm_charge` (migration `V30`) replaces
  `review_llm_call` and the four `review_status` rollup columns entirely: one row per token type per
  call, priced at the rate **in force when the call happened** and snapshotted onto the row rather than
  re-derived from a mutable catalog (a temporal price catalog was considered and rejected — every read
  becomes an interval join, and it doesn't even solve the case it exists for, since an operator entering
  a price today still has no recorded price for yesterday). `llm_model.pricing_mode`
  (`METERED`/`UNMETERED`; `UNKNOWN` is a ledger-only runtime outcome, never an operator's choice) makes
  zero a category instead of a number, because no amount of tightening a numeric check distinguishes
  "this model is free" from "nobody told us the price" when both used to arrive as the same `0`.
  `spire-llm`'s `TokenUsageMapper` partitions each vendor's usage onto the neutral `TokenType`
  (`INPUT`/`CACHED_INPUT`/`CACHE_WRITE`/`OUTPUT`/`REASONING`/`TOTAL`) and cross-checks
  `Σ(per-type tokens)` against the vendor's own `totalTokenCount()` — **per vendor, not uniformly**,
  because Anthropic's total is derived by LangChain4j as `input + output` and excludes both its cache
  buckets entirely; a uniform check (an earlier draft's mistake, caught before it shipped) would have
  made every *cached* Anthropic call fail reconciliation and degrade to a single unpriceable `TOTAL`
  line — the cheap calls being the only ones that couldn't be priced. `LlmModelPricer` never returns a
  zero for a price it could not find; a lookup fault resolves to `pricing_mode='UNKNOWN'` plus an
  attention row, not a coerced `0`. The priceable-model rule is enforced **twice, deliberately**: at
  `LlmProviderRegistry.create`/`update` (added after a review proved live, via a choreography test that
  registered a provider through the registry directly, that the rule was enforced only at
  `LlmProviderResource` — its one existing REST caller — and not at the invariant's own boundary) and
  again pre-spend in `ResultSaga` immediately before `GenerateReview`, because pricing itself is
  post-hoc and that saga check is the last point an unpriceable review can still be **refused** rather
  than merely reported. `LlmModelRegistry.update` now also refuses to **rename** a catalogued model
  still referenced by a provider, mirroring the pre-existing delete guard — a rename orphaned every
  referencing provider identically to a delete and was the one path left that could defeat the
  config-time guard after it had passed; caught because the **conversation path keeps no pre-spend
  check of its own** (a follow-up answers a human already waiting, and the project already learned from
  the silent turn cap that an unexplained non-response reads as a lost webhook, so `AnswerFollowUp`
  records cost honestly instead of declining to answer), which makes the registry-side guard the only
  thing standing between a follow-up and an unpriceable call. `UNIQUE (call_ref, token_type)` closes a
  real double-charge window: the write this replaced was an unguarded `INSERT` protected only by
  `ResultSaga.ifCurrentRun`'s staleness check, so a redelivered result between `ReviewGenerated` and
  `ReviewCompleted` — still "reviewing" at the same commit — charged the same call twice. **One
  documented gap, not glossed over:** the ADR-013 contract-compat snapshot gate stayed green through
  `ModelUsage` losing its cost field and gaining the token-count list, but it did not catch that change
  and could not have — `ContractSchemaSnapshotTest` renders a nested record component as `name:
  TypeName` and never recurses into it, so the golden file never described `ModelUsage`'s own shape in
  the first place. The break is safe because `DomainEvent` carries no usage field (verified directly)
  and Kafka retention is short (ADR-014), not because any check approved it — filed as
  `techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`, since the
  same blind spot covers every other nested wire type. One operator-facing consequence of the migration:
  a catalog model previously saved with a zero rate cannot be migrated honestly (a rate `> 0` is the
  only unambiguous signal that it was operator-entered) and is left without rates, so it must be given
  real rates or marked `UNMETERED` before it will run another review — the guard working as specified.
  SECURITY.md's cost-controls section and ROADMAP's deferred fleet-caps note both now carry the
  consequence forward: a money-denominated cap will be inert by design on an `UNMETERED` deployment, so
  the eventual cap needs a token- or call-count axis regardless of pricing mode. **1138 Java tests
  across 142 suites (`testFast` 497/61 + `testServices` — gateway 63/9, worker 153/17, orchestrator
  425/55); 290 `spire-ui` vitest tests across 40 files; `tsc --noEmit` silent.**
- **The cost ledger reviewed on four lenses, every finding closed (2026-08-08):** the ADR-023 work above
  had already had twelve task reviews and a whole-branch pass, all aimed at the *cost invariant*. A
  security / code-quality / rules / QA sweep then found **two more money-losing defects**, both invisible
  to a green suite and both about the **lifetime of a charge's identity** rather than the correctness of
  its arithmetic — which is why the earlier passes could not see them.
  - **A re-run's charges were silently discarded, keeping only run 1's spend.** `CallRefs` documented its
    own premise — for a review or reconcile call "the key is a constant, so the commit in the slot
    position carries the identity by itself" — and that holds only while the worker's idempotency claim
    is never cleared. `ReviewRerunService` clears it **on purpose**, so the LLM genuinely runs again;
    `call_ref` then reproduced the first run's key and `ON CONFLICT … DO NOTHING` dropped every line,
    with no row, no log and no attention row. Re-run ten times, the cost card still showed run 1. Both
    entry points reached it (the Re-run button and a `/review` PR comment). **It was also a regression:**
    the `review_llm_call` write this replaced used a random UUID primary key, so it recorded every re-run
    correctly — V30's `UNIQUE (call_ref, token_type)` closed a real double-charge on redelivery, but the
    key it chose could not tell a redelivery from a second genuine call. Fixed with `ReviewRuns`, which
    counts `ReviewRequested` events in the review's own stream. **Deliberately not
    `review_status.attempt`**, which is the obvious existing column and is wrong twice over: nothing
    bumps it on a re-run (only `retryPipeline`/`scheduleRetry` write it), so the fix would have been
    *inert*; and it **does** bump on auto-retry, which must **share** the charge identity because
    `onReviewFailed` leaves the claims so the worker re-emits its persisted result — reusing it would
    have charged one paid call two or three times, turning silently-lost money into silently-inflated
    money, which is strictly worse for the cap this ledger exists to enable. Re-run and auto-retry need
    opposite treatment of the same key, so no single column can serve both. Derived rather than stored
    because `deleteReview` clears `event_log` alongside the ledger, so the count and the charges cannot
    drift apart.
  - **`deleteReview` cleared four tables and the worker claims but not `llm_charge`** — and its own
    comment explained why the claims must go ("a delete-then-re-register is that same key … so delete is
    a true clean slate"). `review_id` is `ReviewIds.reviewId(repo, pr)`, stable per PR, with no FK and so
    no cascade. Re-registering the PR therefore **inherited the deleted run's money and model**, and the
    new run's own charge was then discarded by the collision above. The branch got this "all sites"
    discipline right for `context_blob`; the ledger did not inherit it.
  - **`/review` spent money with no author allowlist check.** The `ManualCommandReceived` branch gated
    only on the self-loop guard, while the PR-open path a hundred lines away did check, under the comment
    "unlisted authors never get touched". So anyone who could comment on a PR could force unlimited paid
    calls — each of which was then also uncharged, per the first finding. The gate now sits **ahead of
    the command switch**, so a future command cannot arrive ungated, and refusal is timeline-only: a
    reply would confirm to a prober that the command is wired and would cost an API call per probe.
  - **A negative vendor token count permanently dead-lettered a paid review.** `remainder()` floored and
    said why, but the vendor's own reported total passed through unchecked and `nonEmpty()`'s `> 0` filter
    never saw it, so a `"total_tokens": -1` from a buggy OpenAI-compatible proxy violated the
    `tokens >= 0` CHECK *inside* the `ReviewGenerated` handler — **before** the `PostComments` emit. Paid,
    findings computed, nothing posted, and permanent on every replay. The instructive part: refusing
    negatives in `TokenCount`'s constructor — the right place — would have **relocated** the outage
    rather than removed it, because `zeroIfNull` only handled null, so a negative would then have thrown
    out of `map()` instead. A guard at the correct boundary is only a fix once the callers can no longer
    produce the bad value.
  - **An implausibly large rate wrote a negative cost.** `(long) tokens * rate` had no overflow check and
    every bound was one-sided (the validator, the `llm_model_rate` CHECK and the UI all bounded the rate
    only *below*), so an admin typo silently **subtracted** from both a review's total and the
    deployment-wide sum. `Math.multiplyExact` alone would only have converted that into a dead-lettered
    review after the money was spent, so the fix that matters is the **upper bound at save**; `V31` adds
    the `cost_millicents >= 0` CHECK behind it.
  - **The two cost attention rows could never be cleared**, which would have made them the first
    permanently-lit rows in a panel whose whole contract is "fixing the cause removes the row" — and
    V30 *guarantees* that state on any upgraded deployment, since every legacy zero-priced model is left
    rateless. They now count from an acknowledgement watermark (`CostAttentionRow`). A plain time window
    was the simpler option and was rejected: it silently forgets a real backlog nobody acted on.
  - **Per-token rates were readable by a viewer** on `ReviewDetail`, while every registry read is
    admin-only *because* rates are configuration (ADR-022's third rule). Field dropped — the UI renders a
    cost without needing the rate that produced it.
  - **Two follow-up spend paths were unguarded**, and the ADR-023 claim that the conversation path is
    safe by construction was **falsified**: V30 creates rateless models directly in SQL, so it reaches
    the unpriceable state without passing the registry guard. Both `DECISIONS.md` and `ROADMAP.md` stated
    that rationale and both are corrected — the ROADMAP copy was the worse of the two, presenting the
    absence of a check as a deliberate design decision with a justification.
  - **What the passes could and could not see, worth knowing before commissioning another:** the two
    Criticals were found by reading *paths* rather than files, the negative-token and overflow defects by
    asking which direction each bound was missing, and two coverage gaps by asking which half of a
    two-sided property went unasserted (`ResultSagaRetryTest` proved retry does not *dispatch* twice but
    never that it does not *charge* twice — every fake overrode `recordCharges` to a no-op). One security
    finding's *proposed fix* was wrong even though its diagnosis was right, so a review's remedy needs
    verifying as independently as its claim. **1179 Java tests across 147 suites; 295 `spire-ui` vitest
    tests across 40 files; `tsc --noEmit` silent.** Two debt entries added
    (`techdebt/spire-ui/4-3-…` — the first UI entry; `techdebt/spire-orchestrator/3-3-the-charge-ledger-…`
    — the ledger keys on a `reviewId` carrying no provider, so one workspace name registered on two SCMs
    sums two unrelated PRs, which a per-repo spend cap would inherit).
- **Deleting a review now archives it (ADR-024, 2026-08-09):** the hard delete destroyed the review's
  charge ledger, so real paid usage vanished with a row removed for being clutter — the very history
  ADR-023 snapshotted rates to protect from a *price edit* stayed erasable by a button whose whole
  purpose is tidying the list. `review_status.archived_at` (**V32**, `NULL` = live) marks the review and
  **nothing is deleted**: not the scoped timeline, not `event_log`, not the worker's claims or context
  blob, and above all not `llm_charge`. `DELETE /api/reviews/{ws}/{slug}/{pr}` became `POST …/archive`
  plus `POST …/unarchive`, because a `DELETE` verb that destroys nothing misdescribes the operation to
  every future reader. This **reverses the `llm_charge` deletion added by ADR-023's own review round**,
  and safely: that deletion closed a real defect (a re-registered PR inheriting an orphaned run's money
  and colliding with its `call_ref`), but every step of that hazard needs the review row *gone* so the
  PR can be registered afresh — archiving keeps the row and retires the PR, so no second review exists
  to inherit anything. Archival is a **third dimension** beside `status` and `pr_state`, never a value
  in either: overwriting `status` would destroy whether the run completed or failed, which is the
  statistic the data is retained for. `llm_charge.archived_at` exists and ten ledger reads filter it,
  but **archiving never writes it** — only a future purge will. Stamping at archive was self-defeating,
  since the per-review cost reads key on `review_id` alone and are the same reads serving the archived
  review's *own* detail page, so it would have shown zero cost and no model.
  - **Six paths enforce retirement, because no one choke point sees them all** — four integration
    events in `IntegrationSaga` (`AuthorReplied`, `ManualCommandReceived`, `PullRequestEventReceived`,
    `PullRequestClosed`) plus `ReviewRerunService` and `ManualRegisterResource`, which are REST and
    never reach the saga. The re-run's first act is `clearWorkerIdempotency`, which drops *every* claim
    for the review including the once-ever notice's, so an ungated re-run both resurrected the review
    and re-armed the notice; manual register answered 200 with a reviewId while the saga silently
    dropped the event. Both now 409. **Retirement is a spend boundary, not what makes retention safe** —
    with nothing deleted a resurrected PR's old charges are genuinely its own and `ReviewRuns` stays
    correct; the real reason is that an author's push must not silently re-bill an operator who archived
    to be done.
  - **The notice fires on three of the four events, once per review.** `NotifyArchived` →
    `ArchivedNotified` posts fixed text with no LLM credential (retiring a PR costs no tokens), in the
    thread a reply arrived in or else the top-level PR comment. `PullRequestClosed` gates **without**
    spending it: a close is not a human asking a question, and the notice fires once *ever*, so
    spending it there leaves whoever later asks a real question with silence. `noticeTriggerOf` is an
    allowlist rather than a "not a close" test, so no event added later inherits the notice by default.
    Three further silences, each with its own reason: the bot's own notice echoes back as
    `AuthorReplied` (without the self-loop check it re-emits forever), an author outside the allowlist
    is refused exactly as `/review` refuses them, and with no resolvable provider nothing is emitted at
    all — a credential-less command reaches the worker's stub sink, which would consume the once-ever
    claim while posting nothing real. Unarchive clears `archived_at` and releases the notice claim, so
    a later re-archive announces itself again.
  - **The two findings most expensive to rediscover.** `ReviewThreadView.rootOf` binds its `ThreadRef`
    into a statement immediately, so a null throws an **NPE inside a `try` whose `catch (SQLException)`
    cannot see it** — and `ArchivedNotified.threadRef` is null for the common case (the `/review` and
    PR-update paths post top-level), so `ResultSaga` must null-guard before calling it rather than copy
    the `TurnCapNotified` handler, whose ref is never null. And the notice is claimed on a **constant**
    slot (`ArchivedNotice.SLOT`, shared in `spire-contract` because the worker *takes* the claim and the
    orchestrator *releases* it) rather than on a thread ref — that constant in the slot position is the
    entire mechanism making it once-per-**review** instead of once-per-thread, which is how
    `NotifyTurnCap` deliberately behaves.
  - Archiving **refuses while the review is running** (`ResultSaga.ifCurrentRun` guards on commit alone,
    so an in-flight result would write to a row promised frozen and leave a charge no purge stamps), and
    clears `retry_at` (the 5s sweep would resurrect it) and `answering` (no permanent responding pill).
    `archiveReview` returns a four-valued `ArchiveOutcome`, not a boolean: the `UPDATE`'s `WHERE`
    matches zero rows for all three failure cases, so 404 / 409-already / 409-still-running are
    indistinguishable otherwise. Archive broadcasts a **removal**, not a row update — the row leaves the
    live list, an archived review is frozen, and the socket's `onOpen` snapshot *replaces* the client
    list, so pushing archived rows through it would drop them on every routine 5-minute reconnect;
    Show archived is a plain REST fetch (`?includeArchived=true`). **1219 Java tests across 157 suites**
    (`testFast` 505/63 + `testServices` — gateway 63/9, orchestrator 493/67, worker 158/18); **312
    `spire-ui` vitest tests across 43 files**; `tsc --noEmit` silent. Runbook: SMOKE-TEST **Mode L**.
- **Fleet spend caps and the `refused` lifecycle (ADR-025, 2026-08-09):** the ledger ADR-023 built so a
  cap could exist is finally read back. **Three gates, no new storage**, each where its inputs already
  are and all speaking one refusal vocabulary (`CapRefusal` — a reason plus a timeline `detail()` and an
  operator `note()`, modelled on `DefaultLlm` and deliberately not folded into `DefaultLlm.Refusal`,
  which answers a credential question rather than a budget one). **Diff size** on `DiffFetched`, because
  `changedFiles`/`sizeBytes`/`truncated` exist on that event and nowhere afterwards — and because
  checking later would first run the context fan-out (per-issue API calls, a bounded 20s wait, an
  encrypted blob write) only to discard it. **Pre-spend** in `ResultSaga` beside the priceability check,
  so every reason a paid call was refused reads in one place. **Conversation** in
  `ConversationSaga.planFollowUp` — the genuinely unbounded path, and the codebase already said so:
  `CallRefs` states that the turn cap is per *thread* and *"an @-mention removes the cap entirely, so the
  loss was unbounded"*, and the comment above the `isSpendable` guard records that this same path was
  assumed safe once and was not. The gate therefore sits **after** the mention override (which must keep
  bypassing the *turn* cap without also bypassing the *spend* cap) and after the free `NotifyTurnCap`.
  Refusing a follow-up records only a timeline line — the review may have completed, and declining one
  reply must not retract that, so this is the one gate that does not call `refuse(...)`.
  **A refused review is terminal and archivable.** The refusal this copied (`skipUnspendable`) wrote a
  note and nothing else, so the review sat in `reviewing` until `REVIEW_STUCK` fired blaming *"a webhook
  delivery path or a worker"*, and after ADR-024 `archiveRow` refuses a `reviewing` row — it could not
  even be cleared. `refuse(...)` mirrors `onReviewFailed`'s terminal shape (clear retry, timeline, status,
  note, `RecordFailure(retryable=false)`; `setError` deliberately not called, since there is no
  infrastructure fault to show). Status is **`refused`, not `failed`**, because the archive guard, the
  attention queries and the reviews-list filters all key on status — the same split that took `pr_state`
  out of `status`. **Both axes always**: `SUM(cost_millicents)` *and* `COUNT(DISTINCT call_ref)` over a
  rolling window, since a money cap is inert by design on an `UNMETERED` deployment where every charge is
  an asserted zero, and an `UNKNOWN`-priced row's NULL cost is skipped by `SUM` and caught by the count —
  the ADR-023 hole exactly. The window rolls rather than bucketing, so the instant capacity returns is
  computable and the `CAP_REACHED` attention row names it — via `SpendWindow.oldestChargeAt(Instant)`,
  added beyond the planned interface and put there rather than as a third ad-hoc `llm_charge` query in
  `AttentionQueries`, since one place reading that ledger is the point (and it carries no `archived_at`
  filter, for the same reason `since` does not). The row is **`BLOCKING`, not `WARNING`**: severity
  describes impact, not fault, and while the cap holds every paid call is refused, which is the same
  "nothing will run" shape as `LLM_DEFAULT_MISSING` — the usual nagging objection does not apply because
  it self-clears. It carries no acknowledgement watermark because it describes current state. One
  `SpendGate` serves both enforcement sites and the attention row — two copies of a money comparison are
  free to drift, and drift in a money gate is invisible until it fails to fire. **Every limit is optional
  and unset means unlimited** (an unparseable stored value too — fail open); the window alone has an
  effective default of a day. The cap is **soft**, overshoot bounded by in-flight reviews × per-review
  cost, because charges land after a call completes — documented rather than papered over.
  The two findings most expensive to rediscover: **the terminal status was being overwritten by an event
  projection** — `refuse` routes through `RecordFailure` → `ReviewFailedTerminally`, which `DomainEventSink`
  projected as `status = 'failed'` unconditionally, relabelling the refusal one Kafka round trip later
  while the note stayed correct; the aggregate keeps one terminal-failure event on purpose, so `refused`
  is a read-model *refinement* that `projectTerminalFailure` declines to coarsen, and no saga-level test
  can see the break because only a read *after* the round trip observes it. And **the spend read must not
  filter `archived_at`** while the ten ledger reads beside it must: those answer "what does this review's
  page show", this one answers "what has already been spent", and a copied filter would make archiving a
  way to hand budget back. A third, found while writing the runbook: **a new backend status is invisible
  to the UI's type system.** `ReviewStatus` in `api.ts` is a compile-time union and the status arrives as
  runtime JSON, so nothing carried `refused` across — `STATUS_LABEL` (a `Record<ReviewStatus, string>`)
  answered `undefined` for a blank badge, `miniPipeline` fell through to the terminal branch and drew a
  refused review as **five green segments under "done"**, and `matchesChip` put it in no chip at all.
  `tsc` had nothing to check and every suite stayed green — and the default branch it fell into was the
  *success* branch, which is what makes this class expensive: a refusal shown as a completed review is
  worse than silence, because silence at least looks like nothing happened. Fixed with the union member,
  a `Refused` label, its own `miniPipeline` branch, a `--warn` pill (not `--crit`, which would read as an
  outage, nor `--muted`, which would read as nothing to do), and the **Needs attention** chip. Closed was
  the first choice and was wrong: the deciding fact is that `CAP_REACHED` comes from `SpendGate.decide()`
  and so covers the spend and call caps only — **a diff-size refusal raises no attention row at all**, so
  under Closed it would have had no surface anywhere. The three places that say "needs attention" (chip
  filter, chip count, summary tile) now share one `needsAttention` predicate, for the reason `SpendGate`
  exists. The class is tracked in `techdebt/spire-ui/3-3-…`; the two vocabularies already differ, since
  `ReviewState.Status` holds the aggregate's five values while the read model writes lower-case strings
  and has grown `superseded`, `observed` and `refused` on top. **1256 Java tests across 166 suites**
  (`testFast` 505/63 +
  `testServices` — gateway 63/9, orchestrator 530/76, worker 158/18); **323 `spire-ui` vitest tests
  across 45 files**; `tsc --noEmit` silent. Spec B — the per-repo admission rate limit — is deliberately
  **not** built (it is the only part needing new storage); what it must carry is recorded in the design.
  Runbook: SMOKE-TEST **Mode M**.
- **Per-repository prompts and conversation-derived findings delivered (ROADMAP E16/E17, 2026-08-24):**
  the two items the prompt-management shipment deferred, both closed.
  - **Prompt scope.** `prompt_template` re-keyed `(scope, kind)` (V34, on top of V33's per-customization
    ancestor tracking); `PromptRegistry.effective` resolves **repository → global → built-in default**,
    most-specific-wins with no per-field merge (a repo row replaces both `system` and `body` together,
    never mixes one field from the repo row with the other from global); the orchestrator resolves each
    `GenerateReview`/reconcile/follow-up command's prompt against its own repository before dispatch; the
    whole `/api/prompts` surface (including the new `/api/prompts/scopes` — repositories this deployment
    has actually reviewed, sourced from the orchestrator's own review rows, not the gateway's
    `webhook_repo`) takes `?scope=`. `PromptView` gained `scope` and `inheritedFrom` (`repo`/`global`/
    `default` — which row actually supplied the text, not what scope was requested), and the dashboard
    finally has a UI for it: `PromptScopePicker` (a native `<select>`, not the project's usual custom
    combobox — its correctness depends on the browser's own display-value semantics) holds the selection
    in the URL query string on both `PromptsSettings` and `PromptDetail`, so a reload or a shared link
    lands back on the same repository. Provenance is unmissable rather than a subtle hint: `PromptDetail`
    shows one of **Overridden for this repository** / **Inherited from global** / **Built-in default**
    (scope-aware — a global-scope customization reads "applies to every repository," never "inherited,"
    since there is nothing above it to inherit from), and `PromptsSettings` tags every kind's row the
    same way at the current scope, so a repository showing global's text can never look identical to one
    with its own override. Also delivered in the run-up to this: sample-review preview
    (`PromptSamplePicker`) and the default-drift banner (`PromptDriftBanner`, V33) — the other two
    follow-ups the original shipment deferred.
  - **Conversation-derived findings.** A `/finding` command (`ConfirmFinding` → `ConversationFindingRaised`
    — anchor and severity only, no message text, so a quoted snippet never enters the replayable event
    log per DATA-MODEL.md §5) lets an allowed author file the thread they're in as a first-class finding
    instead of leaving it as prose the reviewer never revisits. Idempotent on redelivery
    (`raisedFindingComments`, same shape as `reviewedCommits`), refused on an unregistered PR or a
    disallowed author the same way `/review` is, and confirmed back into the thread it was run in. The
    finding then behaves exactly like a review-discovered one: it counts toward findings/blocker totals,
    carries an `origin: 'conversation'` tag the UI renders as "from discussion," and survives
    reconciliation on the next round via `PriorFinding` like any other prior finding. Closes
    `techdebt/global/4-4-conversation-derived-findings.md` (deleted). `/finding` inherits the same
    gap `/review` already had — neither checks `policy.observeOnly()` — widened from one path to
    three rather than fixed here, since whether commands should work at all in observe mode is a
    product decision; filed as `techdebt/global/3-2-slash-finding-bypasses-observe-mode.md`.
    Runbook: SMOKE-TEST **Mode N**.

  `docs/REPO-RULES.md` now draws the line this raised: a per-repo prompt is an **operator-owned**
  change to the reviewer's *instructions* (structure, persona, which variables even appear), while
  `.codespire` stays **contributor-owned** *data* that can only add text into the same fenced,
  untrusted-data slot as a Jira ticket — different owner, different trust level, different power, and
  the two compose rather than compete.

  Measured, not estimated: **1399 Java tests across 181 suites** (`testFast` 520/64 + `testServices` —
  gateway 68/11, orchestrator 644/86, worker 167/20); **368 `spire-ui` vitest tests across 51 files**;
  `tsc --noEmit` silent.
- **Repository knowledge base rung 1 delivered (ADR-026, 2026-08-26):** `spire-context-code` (new
  Apache-2.0 module) resolves the identifiers a diff's changed lines introduce against the changed
  file's own import block, read at the review commit, and contributes them as
  `ContextItem{kind=CODE_SNIPPET}` through the existing `ContextProvider` SPI — no crawl, no
  embeddings, no vector store, no `PushReceived` consumer. `LanguageSupport` covers Java and
  TypeScript at launch; a further language is a bean, not a core edit. Snippets render into their own
  `{{code_context}}` prompt slot (its own token budget, mirroring `prior_findings`) so a chatty ticket
  in `{{context}}` can never evict retrieved code by sharing one budget — proven by a seam test
  (`ReviewWorkerTest.aCodeSnippetReachesThePromptSentToTheModel`) that fakes the assembled context and
  asserts the snippet body reaches the `Prompt` object actually sent to the model, confirmed to
  discriminate (fails when `ReviewPromptBuilder` is made to render `code_context` empty).
  **Rung 2 (`worker.code_symbol`) was never started, and P3 closed at rung 1 on 2026-08-29** — then
  reopened and delivered the same day on an operator override; the rung 2 entry near the end of this
  file is the current state, and what follows is the gate that preceded it, kept because the sequence
  is the point. P3 closed at rung 1 when the
  §9 evidence measurement returned a null: 10 findings shared between the arms, 7 only with code
  context, 8 only without — against a noise floor, measured by running the *identical* arm twice, of
  five differing findings on a pull request where nothing changed at all. The toggle moved findings no
  more than rerunning the same configuration did.

  **The null is corpus-limited, and the distinction is the whole point of recording it.** The runs
  produced **3 code findings against 15 documentation findings** — this repository's large pull
  requests are majority ADRs, runbooks and plans, and code context can only ever change a *code*
  finding. There were three, in both arms. So the gate established that this corpus cannot measure the
  feature, NOT that retrieved definitions fail to help; anyone reopening rung 2 needs a majority-code
  corpus with cross-file dependencies. The harness is committed at `docs/superpowers/gates/`, with the
  three per-run controls it enforces (both arms are first reviews, the arms genuinely differed, each
  run returned something parseable) — each added because its absence had already produced a wrong
  answer. The 2026-08-28 attempt measured a token cap and would have reported it as a null about the
  feature.

  Measured, not estimated: **1504 Java tests across 197 suites** (`testFast` 581/75 + `testServices` — gateway 68/11, orchestrator 669/87,
  worker 186/24); **375 `spire-ui` vitest tests across 51 files**; `tsc --noEmit` silent.
- **Browser login to the packaged stack fixed, and the guard that missed it rewritten (2026-08-27):**
  nobody could sign in to a packaged deployment on any port but 80, and three separate causes had to
  be found before one login worked. **nginx inherits `proxy_set_header` from an outer level only when
  the inner level defines none of its own** — so the two WebSocket locations, which set
  `Upgrade`/`Connection`, silently discarded all four forwarded headers from the server block; `Host`
  fell back to `$proxy_host`, the upstream NAME, and OIDC built `redirect_uri` as
  `http://orchestrator:8080/...`, which no realm can match. `$host` then dropped the port, and
  Quarkus was not honouring `X-Forwarded-Host` at all (`enable-forwarded-host: true`, which
  `proxy-address-forwarding` does **not** imply). The callback that finally reached the service
  answered a bare **502**: an operator session is several large `Set-Cookie` headers and nginx's
  4k/8k default is smaller, so the service logged nothing because it had answered correctly — and the
  same session returns on ONE `Cookie:` request header, which needed `large_client_header_buffers`
  raised with it or the fix held in one direction only.
  - **The template no longer re-states the headers per location; no location sets any header at all.**
    `Connection` comes from a `map $http_upgrade` (a literal `upgrade` on the server block would be
    sent to every `/webhooks` and `/wk` request), so all six headers can live on the server block and
    the inheritance trap cannot fire. Re-stating the list per location also works and is what shipped
    first — it leaves the trap armed for the next location someone adds.
  - **The invariant that was supposed to catch this passed the regression it was written for.**
    Neither half was scope-anchored: a file-wide grep for `$http_host` was satisfied by a location,
    and an awk block-scanner asked only whether a location *mentioned* `Host`, not its value — so
    deleting `X-Forwarded-Proto` from `location /api` left it green, and that regression breaks
    **only** behind a TLS-terminating Ingress where a plaintext compose run stays green. It is now
    two assertions with no brace counting: the server block must carry all six headers **with their
    exact values**, and nothing from the first `location` onwards may carry a `proxy_set_header` at
    all. Both are mutation-verified by `--self-test` breaks 4 and 5, whose absence is why the
    rewritten check shipped unverified by the very mechanism `render.sh` exists to provide.
  - **`enable-forwarded-host` made the client's `Host` reach `redirect_uri` for the first time**, and
    the proxy is the default server, so it forwards any `Host` sent. The realm's registered-URI list
    was the only thing rejecting a spoofed one — load-bearing, exact-match in the shipped realm, and
    documented nowhere. `SPIRE_PUBLIC_HOST` now pins it. **Pinning by rewrite, not by rejection:** a
    444 on mismatch would take out the kubelet probe (which addresses the pod IP), the container's own
    health check and any operator reaching the stack by another name. Matching is an exact,
    case-insensitive map key rather than a regex — a regex needs its dots escaped to pin anything, and
    the escaped pattern is then what gets forwarded on the mismatch path, which is not a hostname.
    Empty (the default, and what every deploy artifact renders) forwards the request's own `Host`, so
    the chart's rendered manifests are byte-identical with the value unset.
  - **`isForwardingSafe` checked presence, not width** — `SPIRE_TRUSTED_PROXIES=0.0.0.0/0` started
    cleanly and re-opened everything the trust gate protects, which matters more now that a forged
    `Host` is one of the things that gate stops. Refused by prefix length, because `10.0.0.0/0` is
    just as wide as the two well-known spellings.
  - `deploy/e2e.sh`'s new redirect check prefix-matched the origin, so `http://localhost:34700.evil.example/...`
    passed — the same "does the expected string appear" shape as the nginx guard it was written
    alongside. It now compares the whole callback URL, anchors the extraction to `[?&]redirect_uri=`
    (`post_logout_redirect_uri` was also matching) and decodes lower-case percent escapes.
  - **The proxy buffer sizes are asserted by nothing, deliberately and now on the record** —
    reproducing them needs a real chunked session from a live IdP, which neither script has;
    `techdebt/global/4-3-proxy-buffer-sizing-is-unverified-by-any-check.md`.
- **The review output budget, and the ack threshold coupled to it (2026-08-28):** an attempt to run
  ADR-026 §9's evidence measurement — re-review real PRs with and without code context — found that
  the deployment could not review a real pull request at all, so the gate is **still unrun**. Four
  defects, each verified live before being fixed:
  - **A reasoning model spent its whole output budget thinking and returned nothing.**
    `DEFAULT_MAX_OUTPUT_TOKENS` was 4096 and bounds *thinking plus reply together*, so on a
    17k-input-token diff `claude-opus-5` and `claude-sonnet-5` each emitted exactly 4096 tokens and
    produced no parseable review — charged 18.6¢ and 11.4¢ respectively. A 1.9k-token diff used 2106
    by comparison, so the new 16384 is what a large diff needs rather than a guess; an operator who
    wants another number still sets `max_tokens` on their provider.
  - **Raising the cap only moved the failure onto a hardcoded 60s timeout** (`LangChain4jLlmProvider`,
    three call sites, not configurable). It is now `spire.llm.timeout-seconds`, default 180, carried
    on `LlmConfig` — the one field there that defaults, because it is an operational bound rather
    than something only the operator can know.
  - **That timeout equalled SmallRye's ack threshold, so a slow review killed the worker.** The
    `commands-in` channel fails when one record goes unacknowledged for longer than
    `throttled.unprocessed-record-max-age.ms`, whose default is *also* 60000: the slow call the LLM
    budget explicitly permitted was the call that stalled the consumer, and because the record was
    never acked it was **redelivered on every restart and stalled it again** — a poison pill that
    survived restarts and needed a manual `rpk group seek` to clear, while the worker logged nothing
    and the review sat in `reviewing`. The threshold is now 900000ms, and `LlmTimeoutBudget` **refuses
    to start** when it does not exceed what one command may spend on its model calls. ADR-019 makes
    that **two** calls per `GenerateReview` (reconcile then review), so a budget sized for one looks
    generous and still stalls. The check declares the SmallRye default itself, so deleting the line
    from `application.yml` is a refusal rather than a silent regression.
  - **A review that produced nothing was indistinguishable from a clean one.** Zero findings is what
    both write. `ReviewResult.degraded` (set by `FindingsParser` for an empty *or* unparseable
    response) now rides to the orchestrator, which notes it and persists `review_status.degraded`
    (**V35**) for a new `REVIEW_DEGRADED` attention row. Written on *every* outcome, not only when
    true, so a later good run clears it — the panel's contract is that fixing the cause removes the
    row, and a flag only ever set would have been its first permanently-lit one.

  Two things worth carrying forward. **Adding a component to a wire record silently drops it at every
  rebuild site**: both `ReviewWorker` sites re-listed components and still compiled, because the
  shorter convenience constructors stayed valid — hence `withTruncated`/`withFindings` withers, which
  enumerate the components once, next to the record. And **the contract snapshot did not notice the
  change at all**, exactly as `techdebt/spire-contract/3-2-…` predicts: `ReviewResult` is nested
  inside `ReviewGenerated`, and the golden never described its shape. Safe here (Jackson defaults a
  missing boolean to false, and Kafka retention is short per ADR-014), but approved by nothing.
  All six guards mutation-verified — break the production line, confirm exactly one test fails; the
  first attempt at the reconcile-path guard **passed against its own mutation** because
  `dropAnchorCollisions` returns early when no verdict is still open, so the test never reached the
  rebuild it was written to protect.

  **A four-lens review round then found five more defects in the fix itself**, each reproduced
  before being changed:
  - **The ack guard measured a quantity SmallRye does not measure.** A record's age is stamped when
    it is **polled**, not when processing starts, and the connector prefetches (`max.poll.records`
    500 over a queue factor of 2) — so a burst ages out however generous the threshold looks. The
    channel now pins both to 1 (the dispatcher is ordered and blocking, so prefetch only ever built
    a backlog) and the check *reads* them rather than assuming them. Its non-LLM allowance also had
    to rise 120s → 300s: the posting path is already permitted to sleep 180s backing off a
    rate-limited SCM, so the smaller allowance called a pairing safe that one throttled posting run
    outran unaided.
  - **The direct "the model hit its cap" signal was being thrown away.** `ChatResponse.finishReason()`
    returns `LENGTH` for exactly this condition, and the parser inferred it instead from a *total*
    parse failure. A response cut off **after** some complete findings still parses — so it reported
    a partial finding set and looked finished. Raising the output cap does not remove that case; it
    makes it the likely one, because a model with room to start answering is cut off part-way rather
    than before it begins. `Completion.outputCapped` carries it as a neutral boolean, since
    `spire-contract` is framework-free and every provider spells the fact differently.
  - **The degraded note never cleared.** The flag was written on every outcome so the attention row
    could clear; the note was not, so a clean round 2 left round 1's "this run reviewed nothing" on a
    row whose flag was now false and whose findings were populated — the two halves of one fact
    disagreeing, and the note is the half an operator reads.
  - **`REVIEW_DEGRADED` had no `status` predicate**, so it fired about a review being re-reviewed
    right now, doubled up with `REVIEW_FAILED` carrying stale advice, and told a `refused` run — which
    was never charged — that it had been.
  - **The reviews list still could not tell it from a clean pass**, which is the surface the symptom
    was observed on: the fix had reached the bell and the detail note only. `ReviewSummary.degraded`
    now drives a *no output* pill in `findCell` and joins the `needsAttention` predicate. Same class
    as the ADR-025 `refused` incident, minus its UI-union half — attention rows render generically
    from `code: string`, so the panel needed nothing.

  Two traps worth keeping. The timeout-less `clientFor` overload was invisible **because**
  `LlmConfig.DEFAULT_TIMEOUT` equals the shipped `spire.llm.timeout-seconds` default: a call site
  using the wrong one behaves identically on every deployment except the one that raised the timeout,
  which is the deployment that raised it because it needed to. It is deleted, and both `forCommand`
  paths are now tested against a budget that matches nothing else. And making the note always write
  turned an un-overridden `setNote` on a saga test fake into a live `DataSource` call — the exact
  shape that fake's own `recordCharges` comment already warned about.


  **Then run against real GitHub before merge, which found three more — every one of them in the UI,
  and none reachable from a test suite.** The headline fix was confirmed first: the same pull request
  that twice returned nothing now reviews at `out=5201` for two real findings, so the old 4096 cap was
  cutting it off about 1100 tokens short of an answer. The consumer also stayed Stable with lag 0 and
  an empty DLQ across several calls far longer than the old 60s ceiling — the workload that used to
  kill the channel. Forcing a real cut-off (a low `max_tokens` against a live model) then lit the whole
  chain: `finish_reason` → `outputCapped` → `degraded` → `V35` → note → attention row → list.
  - **The outcome badge still said "Passed", in green, beside "no output".** `outcomeBadge` keys on
    `findings === 0`, which is what a clean pass writes too. The most prominent cell on the row
    asserted a successful outcome for a review that never happened. The findings cell now renders
    `—` and the badge says *No result*, matching how `failed` / `cancelled` / `refused` already
    split that job between the two columns.
  - **The chip counts stopped adding up** — `Completed 32 + Needs attention 2` against 33 rows. Each
    count was its own predicate rather than `matchesChip`, so they drifted the moment a status
    stopped mapping to one chip. All five now derive from the filter itself. The invariant test
    written alongside caught a second, subtler one: `needsAttention` was ungated, so a review being
    re-reviewed *right now* was claimed by both Reviewing and Needs attention. It is gated on
    `completed`, mirroring what the `REVIEW_DEGRADED` query already had to do for the same reason.
  - **The detail page said "✓ clean — No issues found in this diff."** This is the `refused` incident
    exactly, recurring for its structural cause: `STATUS_EXPLANATIONS` is keyed by status, so it
    cannot see a condition that is not one — and the note explaining the run is rendered ONLY inside
    the branch that lookup selects, so it was written, stored, sent over the wire and shown nowhere.
    What made it invisible to `tsc` is worth keeping: the dashboard's `ReviewDetail` type DERIVES
    from its `ReviewSummary` type, while the Java side is two independent records — so adding the
    field to only one type-checks on both sides and arrives `undefined` at runtime, defaulting into
    the reassuring branch. **A type system asserting a relationship the wire does not have.**

  Also worth keeping for the runbook: the dev images BAKE their source, so `up -d` without `--build`
  silently runs the old tree — the first startup check passed against code that did not contain the
  guard being tested. And a hash-route navigation does not reload an SPA, so a screenshot after a
  redeploy can be reporting the previous bundle.

  Measured, not estimated: **1608 Java tests across 202 suites** (`testFast` 622/77 + `testServices`
  — gateway 73/11, orchestrator 705/89, worker 208/25); **396 `spire-ui` vitest tests across 52
  files**; `tsc --noEmit` silent.
- **Repository knowledge base rung 2 delivered (ADR-026 §7, 2026-08-29):** `worker.code_symbol` (V5)
  answers the question rung 1 structurally cannot — **what depends on this diff**, not only what it
  needs. Imports point one way, so call-site impact needs memory, and memory means a table. Every file
  a review reads has its declared and referenced identifiers recorded, so the index grows toward the
  actively-changing part of the repository and never crawls.

  **Built on operator judgement, with the §9 gate not cleared** — recorded in ADR-026 rather than left
  as a contradiction between doc and code. The reasoning holds better than the gate's framing allowed:
  rung 1's value needed an operator to judge whether findings improved, which model variance swamped,
  while rung 2's core claim is a fact. `callersOf` either names files that really reference a symbol or
  it does not. What stays unproven is the downstream claim that a cited caller makes a review better.

  **Verified deterministically against this repository, no LLM involved.** One real review of PR #38
  populated 679 rows over 10 files (122 declared symbols, 319 referenced). Asked for the callers of
  `authEnabled`, the index named six files; every one genuinely contains it — **precision 6/6, zero
  false positives**. The repository actually holds thirteen, so **recall was 46% after a single**
  **review**, which is the design behaving as specified rather than a defect: the index only knows what
  reviews have read, and recall grows with traffic. It is also why every citation is framed as *"a known
  caller … others may exist"* — seven real callers were genuinely absent, so an item reading as *"the*
  *callers"* would have been a fabrication about completeness.

  **The index is a hint, never an answer**, and that is what removes staleness as a category rather
  than managing it: `callersOf` returns candidates, each is re-fetched at the review commit and the
  reference confirmed before anything is quoted. There is no invalidation pass, and `last_seen_commit`
  is compared against nothing — a read that filtered on it would have reintroduced exactly what the
  design avoids. Structure only (identifiers and paths, never a line of source), so ADR-011 needs no
  carve-out and the table stays unencrypted and therefore queryable — the same split `review_finding`
  already makes between its location columns and its message. A null index is rung 1 exactly.

  **Three things live scanning exposed that diff-line scanning never had to face.** A caller snippet
  cannot reuse `SnippetExtractor`, which finds a *declaration* — a caller by definition only *uses* the
  symbol, so every caller would have been silently dropped as unconfirmed. Whole files need block
  comments stripped, or every javadoc sentence enters the index as a reference. And the TypeScript
  keyword set was too small: a first live run put `string`, `void` and `as` among the most-referenced
  "symbols", which is expensive noise because a symbol referenced everywhere fills the candidate cap and
  crowds out the domain names the index exists for.

  **A mutation caught a vacuous test written minutes earlier.** "Does not cite a candidate whose
  reference is gone" passed with the confirmation check *deleted*, because when the symbol is absent
  entirely the snippet extractor fails too — two guards covering one case, so neither was proven. The
  discriminating case is a symbol still present but no longer a reference (mentioned only in a comment),
  which confirmation rejects and text-matching does not.

  **Reviewed on four lenses and hardened (2026-08-29).** Two defects were live, and both were the
  same shape — a scan written for *diff lines* meeting a *whole file*:
  - **The index recorded almost no callers.** `JavaLanguageSupport` excluded imported names from a
    file's references, reasoning that an import says what a file *could* use. To call
    `Pricer.chargeFor()` a file must import `Pricer` — so the filter removed the name precisely from
    the files that are callers, and `callersOf("Pricer")` returned nothing. The whole feature was
    inert while every test stayed green, because no test crossed the seam from *scanning* to
    *retrieving*. `SymbolIndexSeamTest` is that test, and it fails without the fix.
  - **Two regexes were quadratic on a file a pull request author chooses.** A reluctant
    `/\*.*?\*/` over a file with no closing delimiter measured **21.6s at 96 KB**, and a
    member-declaration pattern whose reluctant span overlapped its own capture class measured
    **28.2s at 32 KB** — on the four-thread fan-out pool whose 20s timeout does not interrupt, so
    one pull request stalled the context stage for every review. Replaced by `SourceText`: index
    scans, no backtracking, plus a 256 KB skip.

  Everything else the review surfaced is a bound that did not exist. Each costs recall only, and the
  three worth knowing are the ones no obvious reading catches: **confirmation fetches** (20/review)
  bound the work *between* an index read and a citation, which neither the read cap nor the citation
  cap touches — a common identifier otherwise spent one content GET per candidate against the SCM
  rate limit every adapter shares; **files recorded** (100/review) is what makes the per-file row cap
  mean anything, since a thousand-file pull request wrote that cap a thousand times in one pass; and
  **caller snippets are trimmed against the same `MAX_SNIPPETS` total as definitions**, because that
  number is derived from the `{{code_context}}` slot's token budget, so appending to a full list
  overflows the very slot the cap protects — silently, by tail-clipping, dropping the callers just
  appended. Also: the per-file row budget is now **per role**, since spending it DEFINES-first
  starved the only role `callersOf` reads (a file declaring 400 symbols wrote zero reference rows and
  could never be a candidate — and large files are the likeliest callers); a **confirmed caller is
  re-recorded**, because the write phase runs before the caller phase and so could only ever record
  files fetched *before* confirmation, leaving the rows a review just proved correct the first the
  retention sweep deleted; the index key carries the **platform** (`scmType:workspace/slug`), the
  collision this project has already been bitten by twice; and `SPIRE_SYMBOL_INDEX_ENABLED=false`
  degrades to rung 1 exactly, which a feature shipping on an override with its first persistent store
  ought to have.

  **Two lessons about the tests themselves, both found by mutation rather than by reading.**
  `symbolsIn` subtracts a file's own declarations from its references, so a file can never confirm as
  a caller of a symbol it declares — which made the self-caller test vacuous in its obvious
  one-file form: it held with the skip set deleted. Only a *second* changed file that genuinely calls
  the symbol exercises it. And the dedup test needed a file that is really both — resolved through an
  import (rung 1) *and* named by the index (rung 2) — since a fixture with no imports resolves no
  definitions, so there was nothing for a caller to duplicate. Every guard added here was
  mutation-verified: break the production line, confirm exactly the intended test fails.

  Measured, not estimated: **1658 Java tests across 207 suites** (`testFast` 657/80 + `testServices`
  — gateway 73/11, orchestrator 705/89, worker 223/27); `spire-ui` untouched.
- **P4 delivered — learned memory and review analytics (ADR-027, 2026-08-29):** the roadmap scheduled
  P4 on the assumption that a corpus of accepted and rejected findings existed to learn from.
  **It did not, in two layers.** `DATA-MODEL.md` had specified `review_finding` since the beginning —
  *"persisted for dashboard / analytics / memory"* — and no migration ever created it; ADR-026 and
  `V5__code_symbol.sql` both cited it as an existing precedent for the coordinates-clear/
  content-encrypted split. And the findings themselves were being **discarded continuously**: the
  persisted domain stream carries `ReviewOutcomeRecorded(commit, findingsCount, summaryDigest)` — a
  count and a digest — while the findings ride inline on integration events under ADR-014's short bus
  retention, and `review_status.posted_findings_json` holds one overwritten round. That is ADR-011
  working exactly as designed; nobody had drawn the consequence. (A third, smaller find:
  `projection_checkpoint` is declared in V1 and referenced by zero Java, so the read model's
  "rebuildable" is an intention rather than a mechanism. Recorded, not built — a replay could not
  have recovered historical findings anyway, since the log never carried them.)

  **`review_finding` (V36) is the durable record**, one row per finding per round, with no backfill —
  the corpus accrues from here, the same honest shape as the symbol index, and a salvage from
  `posted_findings_json` would have produced exactly one unrepresentative round per review with no
  verdicts. `Finding` gains a **nullable, closed `category`**: closed because free-text categories
  from a model produce a long tail of near-duplicate labels that group nothing, nullable because a
  customized `REVIEW` template (E16) never asks for it. An unrecognised label parses to **null, not
  `OTHER`** — `OTHER` is an answer the model gave, an unparseable label is unknown.

  **Three write rules are not the obvious ones, and each obvious version fails silently.**
  - **Verdicts do not judge the previous round.** `priorRun` is built from the carried-forward OPEN
    set (V20), spanning every earlier round, so a finding raised in round 1 and fixed in round 4 still
    has its row at round 1. A `round - 1` rule updates round 3, matches nothing, and throws nothing —
    a missed `UPDATE` affects zero rows. "Median rounds to resolved" and every dismissal rate would
    have been quietly, systematically wrong. Matching is the newest **not-yet-judged** row for the
    location across all rounds, preferring the verdict's own thread ref.
  - **Thread-ref attachment is not scoped to a round**, because a push between generation and posting
    appends a new `ReviewRequested` and the current round has already moved on.
  - **Idempotency is delete-then-insert, not a unique key.** A redelivered `ReviewGenerated` re-runs
    the handler inside the `isReviewing` window — the one the V30 double-charge lived in — and a
    unique key cannot help: `category` is nullable and Postgres treats NULLs as distinct, so it would
    fail on exactly the uncategorized rows while also dropping two legitimate findings of one category
    on one line. (Verified against the deployment's own Postgres 18.4 before the design changed.)

  **Analytics (FR-11) ships with the projection, not after it** — reading a projection back is the
  only way to tell a correct one from a wrong one, which is ADR-023's sequence exactly. A dismissal
  rate is **null rather than 0** until something has been judged: zero asserts "this team dismisses
  nothing", which is a claim about them. **Per-author is self-visible** and its authorization is
  row-level, so it lives in code — `@RolesAllowed` cannot express "a viewer may read their own row" —
  keyed on `(provider_type, author_id)` because the same id on two SCMs is two unrelated people.
  `operator_identity` (V37) is **admin-managed and never inferred**: matching an OIDC username against
  an SCM handle would, on a coincidental match, show one person another person's performance data with
  nothing on screen looking wrong. `/api/me` gained a `subject` so an admin has a value to link.

  **Learned memory (FR-10) filters after generation and counts what it removed** (`learned_preference`,
  V38). Prompt injection was rejected: it might produce better findings rather than merely fewer, but
  nothing can tell whether the model honoured the instruction, and a finding it silently skipped leaves
  no trace — the shape of both the circuit breaker recording a failed future as a success and ADR-023's
  `0` that meant *unknown*. The count appears on the pull request and the dashboard, suppressed rows
  stay in `review_finding` naming the preference that hid them, and revoking restores them next review.
  `PathGlobs` is a **fixed ladder** rather than judgement, because "a rejected proposal is not
  regenerated" depends on group identity being recognisable tomorrow.

  **Two lessons from the round itself.** An adversarial spec review (fable-5) checked seven claims the
  spec made about the codebase — all seven held — and then found the spec's claims about what the
  *pipeline produces* were wrong: three exit criteria asserted the impossible, since observe mode never
  starts the pipeline, a refused review stops before `GenerateReview`, and anchor collisions are dropped
  in the worker before the event is emitted. And **the contract snapshot demonstrated both halves of
  `techdebt/spire-contract/3-2` in one milestone**: it caught `PostComments.suppressedCount` (a
  top-level component) and passed `Finding.category` in silence (nested inside `ReviewResult`). The
  spec had predicted the opposite for the first of those and is corrected.

  Three tests were found vacuous by mutation and rewritten: a verdict-redelivery case that held with
  its guard deleted because the newest row was also the unjudged one, and two preference-state cases
  that could not fail because the upsert never writes `state` — the guard actually protects the
  *evidence* on a decided row, which is what the replacement asserts.

  **Reviewed on four lenses, and the two worst defects were in the new code (2026-08-29).** Both
  were silent, and both were about ORDER rather than logic — the class this feature was designed to
  avoid, arriving in its own implementation.
  - **A hidden finding was hidden forever.** The suppression filter ran eleven lines after
    `recordOpenFindings`, so a suppressed finding entered the carry-forward, became the next round's
    `PriorRun`, and the worker turned it into the review prompt's EXCLUSION list — telling the model
    never to raise it again. Revoking the preference could not restore it, because the filter had
    nothing left to un-hide. That is exactly the property ADR-027 names as the reason a counted
    filter beats prompt injection, and four places promised it in prose while the code did the
    opposite. `LearnedMemoryTest.revokingStopsTheHidingOnTheNextReview` passed throughout, because a
    unit test of the filter cannot see the seam; the fix is a saga-level test.
  - **A read failure would have deleted history.** `ReviewRuns.currentRun` answers `FIRST_RUN` when
    it cannot read — right for the ledger, where a charge under run 1 is harmless and refusing loses
    money. Wrong for a round-KEYED write: `recordGenerated` replaces every row for
    `(review_id, round)`, so a transient fault during round 5 resolved to 1, deleted round 1's real
    rows, and filed round 5's findings there. The `round <= 0` guard was unreachable from production,
    so the test covering it tested nothing. `roundOrUnknown` returns a sentinel instead.

  Eight more, each verified before being changed: a preference could hide a **SECURITY** finding and
  the evidence for it is **manufacturable** (an `ACKNOWLEDGED` verdict comes from the model reading
  the author's own reply, so ten "won't fix" answers on one PR qualified a group — now a
  never-suppressed floor enforced at both ends, plus a two-review minimum shown on the card); the
  Memory screen showed **"threshold: 0 findings, 0% dismissed"** because the thresholds were read as
  FIELDS off an `@ApplicationScoped` bean and a CDI proxy delegates methods, not fields; **"median
  rounds to fix" was the median round RAISED** (`ORDER BY round`), so it read 1.0 forever on a
  healthy repository — V39 records `verdict_round`; `markSuppressed` **stamped every row on the**
  **line**; `/finding` findings **never entered the corpus** (`recordConversationFinding` had zero
  callers, so the `origin='conversation'` V36 documents described rows that could not exist); the PR
  comment **pointed at a page that does not exist**; and identity resolution leaned on an **internal**
  **Quarkus class**, whose silent failure would key analytics on a reassignable username.

  **One trap recurred four times in this milestone**, which is worth more than any single fix: an
  un-overridden method on a saga test fake opens a real database connection from a plain unit test.
  `roundOrUnknown`, `markSuppressed`, `recordVerdicts` and `recordConversationFinding` each hit it.
  The lesson was already recorded for `setNote` and `recordCharges`; the fakes now override every
  method the new paths reach, deliberately and with a comment saying why.

  **Every finding was closed in-round, including the ones a first pass had deferred.** Two of those
  were the sharpest remaining edges, and both only reachable on a REDELIVERY — which is why an
  ordinary run never showed them:
  - **A verdict could land on the finding its own event had just inserted.** The thread rule could not
    tell "no such thread" from "that thread is already judged" — an `UPDATE` touching no rows cannot
    say why — so a redelivered batch fell past a settled thread into the location rule and stamped the
    current round's fresh finding with an old verdict. A stray `ACKNOWLEDGED` then counts as a
    dismissal in the proposal scan, the number deciding whether the reviewer starts hiding findings.
    Verdicts are now bounded to earlier rounds, and the thread path probes and reads the verdict.
  - **A redelivered `CommentsPosted` stamped the wrong row.** That handler has no idempotency guard,
    and "newest row still awaiting a ref" is not stable across two deliveries: once the posted row is
    stamped it stops being a candidate, so the second delivery walked down onto an earlier round's
    never-posted finding — falsifying that fact AND handing the verdict rule a ref pointing at the
    wrong finding. A row already carrying the ref now wins over the newest unattached one.

  **Both needed their tests sharpened twice**, and the reason generalises: a first version of each
  passed with the guard deleted. The verdict test used round 2, where the round bound alone already
  excluded the row, so it proved the bound rather than the probe — round 3 separates them. The
  thread-ref test had the posted row as the newest one, where `ORDER BY id DESC` picks it anyway;
  the discriminating case needs the EARLIER round to be the posted one.

  Also closed rather than carried: a database outage reported itself as "your identity is not
  linked" (sending an operator to request a mapping they already had — authorization still fails
  closed, but the READ now reports the fault); the JWT identity branch was untested because
  `@TestSecurity` yields a `QuarkusPrincipal`, so only the fallback ever ran; `/rescan` was an
  unbounded aggregate; the analytics arithmetic had no test of its own; and the size and parameter
  rules — verdict logic to `FindingVerdicts`, suppression to `FindingSuppressions`, plumbing to
  `FindingRows`, two parameter objects, SQL lifted to constants.

  **Checking the shipped code against the spec's own exit criteria then found three gaps — and one
  of them was a defect that made the feature inert.** `PreferenceProposals.scan()` was package-private
  with a javadoc saying "so a test can drive it", and nothing did: everything deciding WHICH groups
  become proposals lives in its SQL, and none of it was exercised. Driving it end to end showed the
  distinct-review floor counted `count(DISTINCT review_id)` per PATH and then took a `Math.max` across
  the paths a glob covers. A glob usually covers many paths that each appear in one pull request, so
  the answer was 1, the `reviews >= 2` floor never held, and **no proposal would ever have been
  generated on a real corpus.** Learned memory would have looked installed and quietly done nothing —
  the exact failure shape this feature was designed to avoid, one level up. The union of the actual
  ids is the only number the floor is about.

  The other two were coverage, not behaviour: "a rejected proposal does not reappear" was asserted
  against the upsert rather than **across two consecutive scans**, which is where the guarantee
  actually rests (it depends on `path_glob` being derived identically each night); and the dashboard's
  suppression tile was unasserted while the summary comment's count was.

  Measured, not estimated: **1732 Java tests across 217 suites** (`testFast` 666/81 + `testServices` —
  gateway 73/11, orchestrator 767/98, worker 226/27); **415 `spire-ui` vitest tests across 55 files**;
  `tsc --noEmit` silent.
- **The Mode G runbook is now a job (`spire-e2e`, 2026-08-30):** the S1–S11 parity script runs
  unattended against a **real containerised GitLab**, closing the gap that every automated test below
  the SCM boundary ran against WireMock — which is *our belief about the API*, authored by whoever
  held the wrong model of it. GitLab is the only one of the three providers this is possible for, and
  the reason is worth recording so nobody retries it: GitHub Enterprise Server is a licensed appliance
  VM, and the self-hostable Bitbucket is **Data Center**, whose `/rest/api/1.0` is a *different API
  family* from the Cloud `/2.0` our adapter targets — self-hosting it would exercise an adapter we do
  not ship. Gitea/Forgejo are a trap for the same reason one level down: GitHub-shaped, and divergent
  in exactly the places this project has been bitten. GitHub and Bitbucket stay on the manual runbook.
  New third CI tier `testE2e` (nightly, never the PR path) beside `testFast`/`testServices`; the split
  is by what a module's tests **own**, since a service test boots what it talks to while an e2e test is
  handed a running stack. `deploy/compose.e2e.yml` adds `gitlab-ce` + a WireMock LLM to the packaged
  stack under **its own compose project**, because compose treats a same-named project as the same
  stack and would otherwise reconfigure a developer's running deployment underneath them.
  - **Everything is on one Docker network, which is what removes the tunnel** — GitLab POSTs straight
    at `ui:8080/webhooks/gitlab/{key}`. Inbound reach to an ephemeral runner is the single reason the
    other two tiers cannot be automated this way.
  - **The mock is steered by the fixture repository, not by reconfiguration.** It tells the three call
    kinds apart by `PromptCatalog.lockedSystemSuffix` — chosen because it is *locked*, so a per-repo
    prompt override (a supported feature) cannot break the suite — and a defect marker counts only on
    an **added** line. Two shapes matter and differ: the review prompt renders `<lineNumber> +content`
    (`DiffRenderer`), while the reconcile prompt carries a **raw** unified diff, because
    `ReviewWorker.reconcile` passes the incremental compare through unrendered. A pattern written for
    one matches nothing in the other, and the mock then answers with a fallback that reads exactly
    like "nothing was fixed". The fallback verdict is `UNCHANGED`, never `RESOLVED`: a `/review` re-run
    happens on the *same commit*, and while it said resolved it closed every finding and posted
    "Fixed in `<sha>`" against untouched code.
  - **S9b is the load-bearing assertion.** Asserting that *untouched* findings stay `UNCHANGED` proves
    nothing — when the incremental diff parses to zero files every file reads as untouched, so they
    still read `UNCHANGED`. Only a **touched-but-unfixed** finding surviving as `STILL_OPEN` can fail
    under that regression, which is the one that made ADR-019 inert on GitLab alone. Mutation-verified,
    as were the added-lines-only rule and the tier guard.
  - **The rename question is settled.** `SMOKE-TEST.md` called finding-identity churn a known
    limitation and cited a `techdebt/` entry that does not exist, while `CLAUDE.md` recorded a pass
    where a 100%-similarity rename did *not* churn. `RenameTest` decides it against a real GitLab —
    findings follow the file, nothing reports `SUPERSEDED`, nothing returns as new — and the runbook
    is corrected in place.
  - **Six defects the existing suite could not see**, each fixed: adding a module to
    `settings.gradle.kts` broke **every production image build** (the `Dockerfile` names each module by
    hand for its dependency layer; now guarded both ways by `ImageBuildSeesEveryModuleTest`); Java's
    `HttpClient` opens a plaintext origin with an h2c upgrade that nginx does not answer, so every
    proxied call hung its full 60s while curl returned in 60ms; `grafana['enable']` was dropped from
    Omnibus in 16.3 and an unknown key *aborts* reconfigure, restart-looping the container with no
    symptom outside its own logs; GitLab's health endpoints are restricted to `monitoring_whitelist`
    so `/-/readiness` answers 404 from the host whether it is up or not; GitLab's root seeding did not
    run and left an instance with **zero users** that serves every page normally; and a duplicate
    registry name reaches the client as a bare **500** rather than a 409 (the class already tracked in
    `techdebt/spire-orchestrator/3-3-…`).
  - **One scenario is disabled with an UNEXPLAINED failure, and the first explanation was wrong.** The
    code-context probes fail against the containerised GitLab: the provider runs, extracts identifiers,
    and resolves none (`Context resolution for CODE: extracted=17 resolved=0`), leaving
    `worker.context_blob` empty. Nothing throws, because context providers fail soft — so a failing
    provider and a pull request with genuinely no context are indistinguishable, which is the
    operator-facing risk that outlives the specific bug. Filed as
    `techdebt/global/3-3-code-context-resolves-nothing-in-the-e2e-stack.md` with the reproduction.
    The first diagnosis — that `PinnedJsonClient`'s SSRF guard refuses site-local addresses on every
    request — is **false**: `isPrivateAddress` is reachable only from `requireSafeRedirectTarget`,
    which runs only on a 3xx and exempts same-host targets, and its javadoc says outright that dev/test
    run against localhost. It reached five documents before anyone read the guard, and a four-lens
    review did not uniformly catch it (the security lens called the diagnosis accurate; the QA lens
    read the code and found it unreachable) — **agents agreeing is not evidence**. Separately, an
    earlier version of those probes **passed for the wrong reason**: the definition sat inside the
    merge request's own diff, so its body reached the model whether or not anything retrieved it.
    Moving it to the target branch is what made the assertion real.

  Measured, not estimated: **1735 Java tests across 218 suites** on the PR path (`testFast` 669/82 +
  `testServices` — gateway 73/11, orchestrator 767/98, worker 226/27), plus the new nightly tier
  **`testE2e` — 44 tests across 9 suites, 2 skipped**, which is the whole S1–S11 chain, the rename
  scenario, the mock's own contract and the harness's self-tests. Verified on a GitHub runner as well
  as locally (run 33341378597): identical counts, 16m41s including a cold GitLab boot, so nothing was
  passing by accident on one machine. `spire-ui` untouched.
- **Software factory M0 — the walking skeleton — delivered (ADR-029..ADR-039, 2026-09-02, PR #95):**
  `POST /api/runs` → `cs.run-commands` → `spire-run-worker` → a three-container run unit on Docker
  (init clones with the read credential, the agent runs the harness with the model key and no git
  token, the publisher holds the write token and never the workspace) → bundles on `/handoff` →
  the push gate → a branch on the real remote authored by the machine account; a run touching a
  CI file is refused at the gate and raises `RUN_PUSH_GATE_REFUSED`. Seven new modules
  (`spire-harness`, `spire-harness-codex`, `spire-workspace`, `spire-runtime`,
  `spire-runtime-docker` Apache; `spire-publisher`, `spire-run-worker` FSL), migrations V40–V43
  (`llm_charge` neutral subject, `factory_run`, `scm_provider.role`, the attention acknowledgement),
  the FACTORY/REVIEWER split on every provider lookup, both credentials Tink-wrapped on the bus
  with AAD bound to run and slot, the neutrality scan widened to harness and runtime names, and
  the two images (`deploy/agent/codex`, `spire-publisher/Dockerfile`). **Both exit criteria are
  proven by `M0WalkingSkeletonTest`** against real containers — the publisher image built from
  this repository, the reference agent entrypoint with a shell script standing in for the model,
  and a self-built smart-HTTP git origin — and by runbook **Mode Q** against a real forge with
  Codex. `docs/factory/ROADMAP.md` records what the build taught that the design had wrong; the
  three most expensive to rediscover: the runtime never fed the prompt on stdin (Codex declares
  stdin delivery and would have started on an empty prompt — a script-harness test can never
  notice, so the entrypoint hands `SPIRE_PROMPT` over from a file outside the tree), the publisher
  was SIGTERMed the instant the agent exited (`docker stop`'s grace period read as a drain window,
  so every runtime-driven run reported nothing while the hand-driven unit pushed — exit code 143 on
  the preserved unit was the tell), and a 12-argument convenience constructor stored every FACTORY
  registration made through REST as REVIEWER, which handed the review pipeline the push token and
  made the endpoint answer 409 forever. Each task was four-lens reviewed and mutation-verified;
  the round-1 findings not closed in-round are filed under `techdebt/spire-run-worker/`,
  `techdebt/spire-publisher/` and `techdebt/spire-orchestrator/` (watchdog, cancel, refusal
  stopping the agent, run charges — the M1 items by the plan). A second four-lens round over the
  fix batch found the round's own regressions — a `PUT` without a `role` demoted a FACTORY provider
  back to reviewer (the dashboard's edit form sends none), the wall-clock path SIGKILLed the
  publisher one line before the drain window the first round had just given it, and a
  `DISPATCH_FAILED` row could never be corrected by the real result — all closed with
  discriminating tests, and the review-state file `.claude/reviews/global/software-factory.md`
  records every finding's disposition. A forced third round, under the HIGH-or-concrete-bug rule,
  found the reader "cancel" after a throwing salvage did not cancel — `CompletableFuture.cancel`
  documents its flag as having no effect, so each reader kept blocking on a preserved container's
  follow stream, a virtual thread and a daemon connection each, until process exit (now
  `ExecutorService` futures whose `cancel(true)` interrupts, and a runtime that closes the log
  callback on interrupt); a never-acknowledged dispatch re-armed with the RETRY's parameters while
  the first command may be the one running (identical request only, a differing retry is a 409);
  and the ack budget missing the publisher drain the previous commit had raised 30s → 300s. All
  closed in-round. Measured, not estimated: **2069 Java tests across 257
  suites** (`testFast` 857/100 + `testServices` — gateway 73/11, orchestrator 818/105,
  review-worker 226/27, run-worker 80/12 incl. the M0 walking skeleton, runtime-docker 15/2);
  `spire-ui` untouched. The two images are not on GHCR and `spire-run-worker` is not in
  `deploy/` yet — packaging follows M1, and the runbook builds both locally.
- **Still pending from P1 scope:** nothing. Call-level resilience shipped as a hand-rolled retry
  ladder + circuit breaker, **not** SmallRye Fault Tolerance — ADR-016 rejected per-call `@Retry` for
  the review budget, and the same reasoning held for the call level. Model pricing is delivered and
  deliberately operator-entered (ADR-018): a hardcoded cost table would silently mis-price every
  review as prices drift, which is the no-fabricated-data rule applied to money.

## Build & run

JDK 25 (SDKMAN `25.0.3-tem`) + Docker required.

```bash
cp .env.example .env                      # set POSTGRES_PASSWORD (dev-only)
docker compose up -d                      # Postgres :34432 + Redpanda :34092
./gradlew build                           # unit + per-service split tests (Testcontainers: Kafka + Postgres)
./gradlew :spire-orchestrator:quarkusDev  # dashboard at http://localhost:34080
./gradlew :spire-gateway:quarkusDev       # webhook edge :34081
./gradlew :spire-review-worker:quarkusDev # worker :34082
./gradlew :spire-run-worker:quarkusDev    # factory run worker :34083 (drives the local Docker daemon)
cd spire-ui && npm install && npm run dev # React dashboard :34000 (UI_PORT)
```

**The factory's two images** are not on GHCR yet and are built locally (SMOKE-TEST Mode Q):

```bash
docker build -f deploy/agent/codex/Dockerfile -t spire-agent-codex:latest deploy/agent
./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
```

`M0WalkingSkeletonTest` (`spire-run-worker`, `testServices`) builds the publisher image itself, plus
a test agent image and a smart-HTTP git origin, so it needs Docker and nothing else; leftover units
from a crashed run are `docker ps -a --filter label=dev.codespire.runId`.

**Fast local verification** — the same two tiers CI runs, so this is the pre-commit loop:

```bash
./gradlew testFast                        # 19 Docker-free modules, ~1 min
./gradlew testServices                    # 5 service modules: the 4 deployables on Dev Services (Postgres +
                                          # Kafka) plus spire-runtime-docker; it and spire-run-worker
                                          # also drive a real Docker daemon
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

**The GitLab end-to-end suite** (`spire-e2e`, host ports 348xx — its own compose project, so it can
run alongside a packaged stack):

```bash
# Bring it up ONCE. GitLab needs ~6 minutes before it answers; poll rather than guess:
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env up -d --build
until curl -fsS http://localhost:34880/users/sign_in >/dev/null 2>&1; do sleep 15; done

set -a; . deploy/.env; set +a     # the suite reads POSTGRES_*, SPIRE_OIDC_*, DEV_OPERATOR_PASSWORD
./gradlew testE2e                 # re-runnable in seconds against the warm stack

# Tear it down when you are finished — this does NOT happen automatically.
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env down -v
```

**The stack deliberately outlives the run.** Nothing in `testE2e` starts or stops a container: GitLab
is a six-minute boot, so tearing down per run would make the iteration loop unusable and every local
failure expensive to reproduce. CI does the opposite — `.github/workflows/e2e.yml` ends with
`down -v` under `if: always()`, because a runner is thrown away and a leaked volume is nobody's
convenience. If you are wondering why `docker ps` still shows a GitLab: that is why, and the line
above is the cure.

Results land in `spire-e2e/build/reports/tests/test/index.html` locally; the nightly job uploads the
same report plus the JUnit XML as an artifact, and writes a per-suite pass/fail table into the run
summary. On a red run it additionally uploads `deploy/e2e-diagnostics.sh`'s capture — service logs,
the LLM mock's request journal, and GitLab's own webhook-delivery history.

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
- Java 25 / Quarkus 3.38.3 / Gradle Kotlin DSL; **pure domain code stays free of framework imports** —
  build-enforced for `spire-contract` and `spire-diff` by `PureModulesAreFrameworkFreeTest`
  (`spire-arch`), which permits only the JDK, those modules themselves, and one documented
  exception: **`jackson-annotations`** (annotations only, no databind) on the sealed
  `IntegrationEvent` / `ActionCommand` hierarchies, because those types *are* the Kafka wire
  contract and their discriminators belong with them. Per-service mix-ins were considered and
  rejected: they spread one registry across every `ObjectMapper` in three services, where a missed
  site is a runtime wire break rather than a compile error. Adding a second exception means
  amending that allowlist, on purpose.
