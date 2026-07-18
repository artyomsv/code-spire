# Decisions

Architecture decision records for Code Spire. Newest first.

---

## ADR-019 — Re-reviews post deltas, not the full finding set

**Context.** Before this decision, a follow-up commit to an already-reviewed PR triggered the exact
same pipeline as the first review: fetch the full diff, run one review call, post a fresh inline
comment for every finding. Every prior finding got re-raised verbatim (duplicate noise on threads the
author was already discussing), nothing closed automatically when a finding was actually fixed, and
the summary comment piled up as a new post each time instead of reflecting current state.

**Decision.** On a follow-up commit to a PR with a posted prior run, the worker runs **two
claim-guarded LLM calls** instead of one. First, a **reconcile call** (`LLM:reconcile` idempotency
claim) judges each prior finding — prior findings + their best-effort thread transcripts + the
incremental diff since the prior run (new SPI `DiffSource.fetchCompareDiff`, falling back to the full
PR diff when the provider can't compare, e.g. after a force-push) — producing one
`FindingVerdict{RESOLVED|STILL_OPEN|ACKNOWLEDGED|SUPERSEDED}` per finding. Second, the **standard
review call** (`LLM` claim, unchanged prompt) runs with an added "already reported — do not re-report"
exclusion section built from the same prior findings, then a deterministic filter drops any new
finding whose anchor collides with a `STILL_OPEN` verdict (it's already tracked in its own thread).
`PostComments` then acts per verdict: closing verdicts (`RESOLVED`/`ACKNOWLEDGED`/`SUPERSEDED`) try
`CommentSink.resolveThread` first — a human who beat the bot to it (`ALREADY_RESOLVED`) means the
reply is skipped entirely; otherwise (resolved-by-us or `UNSUPPORTED`) a reply always follows.
`STILL_OPEN` never resolves and always replies. Genuinely new findings post fresh inline comments, and
the summary is rewritten **in place** (`CommentSink.updateComment`, falling back to a fresh post if the
old comment vanished or was edited away). Every reply/resolve holds its own `comment_idempotency` claim
(`reply:<threadRef>`, `resolve:<threadRef>` — value `bot`/`human`/`unsupported` — so redelivery repeats
zero external calls).

Prior-run state is **command-carried**, not worker-owned: the orchestrator packs `PriorRun{headCommit,
summaryCommentId, findings}` onto `GenerateReview` from `review_status.posted_findings_json`, a
snapshot stamped at the last `CommentsPosted` behind a **commit-match guard** — the snapshot UPDATE
only applies when the posted run's commit still matches the review's current `commit_sha`, so a stale
or superseded run's `CommentsPosted` (reachable only through the worker's head-re-check race) can't
overwrite a consistent snapshot with mismatched findings. This keeps the single-writer aggregate side
(the orchestrator, which already owns `review_status`) as the sole owner of "what was actually posted,"
and the worker stateless across runs — exactly the shape ADR-015 established for brokered credentials.

**Alternatives rejected:**
- **Worker-local snapshot.** Having the worker persist its own "last posted" table would duplicate the
  orchestrator's read model, invite drift between "what the worker thinks it posted" and what actually
  reached the SCM, and hand the worker write ownership of state that belongs to the read-model owner —
  a schema-purpose violation (ADR-011 schema-per-service is about *ownership*, not just tables).
- **Single combined LLM call.** Asking one call to both reconcile prior findings and generate a fresh
  review multiplexes two different tasks into one prompt: it would require rewriting the
  already-proven review prompt (ported from PR-Agent, ADR-002) to also emit verdicts, and one
  malformed section of the response would corrupt both outputs instead of failing independently.
- **Deterministic anchor-only dedup (no LLM).** Comparing old and new anchors can suppress a duplicate
  at the *same* position, but cannot tell a fixed issue from one that merely moved or was reworded —
  no real fix detection, and the heuristic would be throwaway work once genuine reconciliation is built.

**Consequences.**
- Every follow-up review now pays for **two LLM claims** (`LLM:reconcile` + `LLM`) instead of one; both
  are claim-guarded so a redelivered `GenerateReview` never re-spends. A first review (no prior posted
  run — `priorRun` null) is untouched: the exclusion/verdict machinery never engages, and every
  extended wire type defaults empty/null via old-arity convenience constructors.
- Bitbucket Cloud has no thread-resolve API for PR comments — `resolveThread`'s default `UNSUPPORTED`
  degrades it to reply-only, so a closing verdict there gets a reply but the thread stays visibly
  "open" in Bitbucket's UI. GitHub (GraphQL `resolveReviewThread`) and GitLab (discussion `PUT`) get
  real resolution.
- Findings untouched by the follow-up (verdict `UNCHANGED`, with a deterministic path-based downgrade
  of `STILL_OPEN` when the incremental diff is available) stay silent on the SCM — the reviewer only
  speaks in threads the author's changes actually affect.

---

## ADR-018 — LLM provider registry: in-app, encrypted, brokered per command

**Context.** The LLM was selected at worker boot from env (`SPIRE_LLM_PROVIDER` + `SPIRE_LLM_BASE_URL`/
`API_KEY`/`MODEL`) — one provider per deployment, key on disk, no way to change model without a
restart. The SCM side had already solved the same shape: a DB registry (`scm_provider`) with
Tink-encrypted secrets, resolved at review time and brokered encrypted to the worker per command
(ADR-009 + ADR-015). LLM config should follow it, not diverge.

**Decision.** LLM providers are registered in the app (Settings → LLM), stored in `llm_provider`
(Tink-encrypted `api_key`, AAD bound to the row id), never returned by the API. One row is the global
default (partial unique index). At `GenerateReview` time the orchestrator resolves the default,
packs its config as an `LlmCredential`, encrypts it (AAD `worker-llm-cred:<workspace>` — a distinct
prefix from the SCM cred so ciphertexts can't be swapped), and rides it on the command. The worker
decrypts it and builds the model per command (`WorkerLlmProvider`, mirroring `WorkerScmClients`).
The key is validated on save with a cheap authenticated call to the provider's models list,
SSRF-guarded by the shared `PublicHttpsGuard` (the same guard the SCM whoami uses).

`SPIRE_LLM_*` credential env vars are gone. `spire.llm.provider` survives only as a `stub|registry`
mode flag (dev/test stub), like the SCM `spire.scm.stub` toggle. If no default LLM provider is
registered, `GenerateReview` is skipped with a visible note rather than emitted uncredentialed.

**Providers.** Phase 1 supports OpenAI (via LangChain4j `langchain4j-open-ai`). Anthropic and Gemini
are phase 2 — the credential's `type` selects the builder, so they slot in without a wire change. A
per-SCM-provider LLM override (`scm_provider.llm_provider_id`) is phase 3. Subscription-license
backends (ChatGPT/Codex, Claude Code, Copilot seats) are explicitly out: they are not embeddable APIs
and repurposing them violates ToS — use the native provider APIs, which serve the same models.

**Model catalog + cost (roadmap 11).** A separate `llm_model` catalog holds the selectable models and
their token pricing — millicents (1/100,000 dollar) per 1M tokens, integers as providers quote them.
Prices are operator-entered, never hardcoded: model prices drift, and a stale number would make every
cost estimate silently wrong (same reasoning as the no-fabricated-data rule). A provider's model is
picked from this catalog. When a review completes, the orchestrator prices its real token usage
against the catalog (`cost = (tokensIn·inputPrice + tokensOut·outputPrice) / 1M`) and stores
`review_status.cost_millicents` — the field was collected since S1 but always 0. Cost is computed in
the orchestrator (the registry owner), not brokered to the worker, so pricing stays in one place.

**Per-model parameter profile.** Different models accept different request parameters: classic Chat
Completions models take `max_tokens` + a custom `temperature`, while OpenAI reasoning models (o1/o3/
gpt-5) reject `max_tokens` (they require `max_completion_tokens`) and reject a non-default temperature.
Rather than sniff model names in the worker, each catalog model carries a **profile** the operator
declares (`output_token_param` = MAX_TOKENS | MAX_COMPLETION_TOKENS | NONE, `supports_temperature`,
`reasoning_effort`, and a free-form `extra_params` JSON passed through as OpenAI `customParameters` —
the escape hatch for any future param, so a new knob never needs a code change). The profile lives on
the model (it is intrinsic to the model, not the deployment), defaults to the classic dialect so
existing models are unchanged, and is brokered to the worker on the `LlmCredential` (`ModelParamProfile`)
keyed by model name. The worker builds `OpenAiChatRequestParameters` from it — no dialect is hardcoded.
Correctness note: the params ride via the request; the real `OpenAiChatModel` keeps its defaults as the
OpenAI subtype so the merge preserves these fields on the wire (a bare mock `ChatModel` would drop them,
so the mapping is unit-tested directly).

---

## ADR-017 — Self-loop guard in the orchestrator; bot account id lives only in the registry

**Context.** The bot account id exists for exactly one purpose: the self-loop guard (ADR-013) — don't
re-act on comment events the bot itself authored. It used to be threaded everywhere as config: an env
var (`SPIRE_SCM_BITBUCKET_BOT_ACCOUNT_ID`) read by the gateway, and a `botAccountId` field on
`BitbucketCloudConfig`, `GitHubConfig`, and the brokered `ScmCredential` — fed placeholders (`"unset"`,
`"unused-by-worker"`, `"resolving"`) on every path except the gateway ingress. Once provider registration
learned to resolve the id from the token via `whoami()` (the `IdentitySource` port), there were two
sources of the same fact, and the gateway still needed a hand-set env var because — being internet-facing
and least-privilege (it holds no SCM token) — it cannot call `whoami()` itself.

**Decision.** Make the bot account id a **registry-only** fact and run the self-loop guard where the
registry is readable: the **orchestrator**. `IntegrationSaga` drops bot-authored `ManualCommandReceived` /
`AuthorReplied` events by comparing the event's author (which the ingress already carries) against the
workspace provider's `botAccountId` from the registry (whoami-resolved). The gateway ingress stops
dropping and just forwards, holding no identity. `botAccountId` is removed from `BitbucketCloudConfig`,
`GitHubConfig`, and `ScmCredential`, and `SPIRE_SCM_BITBUCKET_BOT_ACCOUNT_ID` is retired.

**Why here, not the gateway.** The gateway can't `whoami` (no token, by design — the internet-facing
service must stay credential-light). The alternatives were worse: give the gateway the App Password
(breaks least-privilege) or have it fetch the id from the orchestrator at boot (a startup coupling for one
string). The orchestrator already resolves the provider per workspace, so the guard costs it nothing new.

**Consequence.**
- One source of truth: the registry. No env var, no placeholder `botAccountId` scattered across configs.
- The guard now runs downstream, so a bot-authored comment event briefly rides `cs.integration` before
  being dropped (vs. dropped at the edge). It surfaces on the timeline as `SelfLoopDropped` — more
  visible, not less. `/command` + replies are P2, so there is no live behavioural change today.
- The gateway holds only the webhook secret (SECURITY.md updated). When a GitHub ingress lands, its
  self-loop guard is already implemented — the same orchestrator check, no new config.

---

## ADR-016 — Bounded auto-retry as a saga-owned budget, not per-call fault tolerance

**Context.** A retryable failure (transient 5xx / I/O / timeout from the SCM or LLM) left a review
**stalled forever**: workers catch the error and emit `ReviewFailed{retryable=true}` instead of
throwing, and the decider's `RecordFailure` branch only produces `ReviewFailedTerminally` when
`!retryable` — so a retryable failure emitted no domain event, issued no next command, and the aggregate
sat in `REVIEWING` with nothing to advance it. Recovery meant a manual re-push. The roadmap framed the
fix as "SmallRye Fault Tolerance retry budgets" (per a `DiffWorker` TODO).

**Decision.** Implement the budget in the **orchestrator's `ResultSaga`, event-driven and persisted**,
rather than as per-call `@Retry` inside the workers. On a retryable `ReviewFailed` with budget left, the
saga bumps a persisted `attempt` counter on the read-model row and **re-emits `FetchDiff`** — restarting
the whole pipeline with a freshly-brokered credential (ADR-015), exactly what a manual re-push does, but
automatic and capped by `spire.review.max-attempts` (default 3). When the budget is spent, the provider
is gone, or the failure is permanent, the saga records `RecordFailure{retryable=false}` so the aggregate
yields `ReviewFailedTerminally` and leaves `REVIEWING`.

**Why saga-level over per-call `@Retry`:**
- **Removes the stall at the actual cause.** The stall is a missing state transition in the orchestrator,
  not a missing wrap around one HTTP call — fixing it where the aggregate lives is the direct fix.
- **Restart-from-`FetchDiff` needs no payload threading.** The failed phase's inputs (`contextRef` for
  generate, the `ReviewResult` for post-comments) are NOT carried on `ReviewFailed`; retrying the exact
  phase would require bloating the failure event or a contract change. Restarting from the diff is
  reconstructable from `reviewId + commit` alone, and the LLM/comment idempotency stores (finding H4)
  make the re-run free of double-charges or duplicate comments.
- **Persisted & crash-safe.** The counter lives in the read model, so the budget survives a worker
  restart; an in-memory `@Retry` loop would reset on every redeploy and couldn't span the pipeline.
- **One retry layer, not two.** Per-call FT nested under a pipeline restart would multiply attempts and
  obscure the true count. A single budget keeps the semantics legible.

**Consequence.**
- No contract change: no new events/commands, `ReviewFailed.attempt` is left informational (the saga
  trusts the persisted counter). New `review_status.attempt` column (V5); `Attempt` on the detail page is
  now live instead of hardcoded `1`. The timeline shows `retry:<phase>`; a transient blip auto-recovers
  without ever showing `failed`.
- The budget is a read-model counter, not a domain fact — a full projection rebuild resets `attempt` to
  1 (a rebuilt-then-failing review simply gets a fresh budget). Accepted: retry budgeting is operational
  metadata, not an invariant of the write model.
- Per-call `@Retry`/`@Timeout` inside a phase (to smooth a single blip without a full pipeline restart)
  remains a possible future refinement layered *under* this budget — not needed to close the stall.

---

## ADR-015 — Active-mode worker credentials: KEK to the worker, credentials brokered on the bus

**Context.** In active mode the review worker performs the credential-bearing work — `FetchDiff`,
`GenerateReview` (re-fetch PR + diff), `PostComments` (fetch + post). Until now it read ONE global
SCM credential from `.env` (`WorkerScmProducer`, a startup singleton keyed to nothing). Phase 2 moved
credentials into the encrypted `scm_provider` registry, but only the **orchestrator** (which holds the
Tink KEK, `SPIRE_ENCRYPTION_KEYSET`) can decrypt it. `SECURITY.md`/ADR-013 deliberately kept the KEK
to exactly two holders (orchestrator + UI), with workers "plaintext-only, no KEK". So there was no path
for the worker to obtain a per-workspace credential from the DB registry. Every `ActionCommand` already
carries `RepoRef repo` (hence `workspace`), but no provider identity or secret.

**Decision.** (1) **The worker joins the KEK holder set.** It reads `SPIRE_ENCRYPTION_KEYSET` and gains
an `EncryptionService`. (2) **Credentials are brokered by the orchestrator over the bus, not resolved by the
worker from the DB.** The orchestrator (sole owner of the provider registry) resolves the provider for a
command's workspace, packs a minimal `ScmCredential` (base URL, auth kind, username, secret, bot account
id), encrypts it with the **master KEK** (AAD bound to the workspace), and stamps the opaque base64
ciphertext onto the three credential-bearing commands. The worker decrypts it and builds a per-command
`DiffSource`/`CommentSink`. A command with no credential (stub/observe/dev) falls back to the stub SCM.

To share the cipher, `EncryptionService` moves into a new **`spire-encryption`** module depended on by
orchestrator + worker (Tink stays encapsulated there).

**Why this mechanic (bus-brokered) over the two alternatives the worker-holds-KEK choice allowed:**
- **vs. worker reads `orchestrator.scm_provider` directly:** rejected — a cross-schema read violates
  ADR-011 (schema-per-service) and ADR-008 (microservices), couples the worker to the orchestrator's
  DB, and duplicates the AAD scheme. Bus-brokering keeps the registry single-owned and the worker
  deployment-independent (works if the two ever split to separate databases).
- **vs. plaintext credential on the bus (ADR-014 infra mitigation):** rejected — a live, directly-
  abusable SCM token is materially more dangerous than the source-quote findings ADR-014 accepted on
  disk. Encrypting with the KEK (which the worker now holds anyway) removes cleartext-at-rest for
  credentials at no extra bootstrap secret.
- **vs. a dedicated worker-only key (envelope distinct from the master KEK):** rejected for v1 as
  over-engineering — it would have kept the master-KEK blast radius unchanged, but the operator chose
  to accept the wider radius in exchange for one keyset and simpler key management. Noted as the
  escalation path if the worker's blast radius ever needs narrowing.

**Consequence.**
- **KEK blast radius widens** from two holders to three (orchestrator, UI, worker). A compromised worker
  now holds the master key that also protects the event log + findings at rest. Accepted by the operator
  for v1; `SECURITY.md` updated to state the worker holds the KEK in active mode and why. The narrowing
  path (dedicated worker key) is recorded above.
- The wire contract gains an opaque `scmCredential` field on `FetchDiff`, `GenerateReview`,
  `PostComments`. `ResultSaga` (which emits the latter two) gains provider resolution; if the provider is
  disabled/removed mid-review the command is skipped with a logged note.
- `.env` SCM credentials are retired for the worker's active path; the worker now needs
  `SPIRE_ENCRYPTION_KEYSET` (the gateway still holds only the webhook secret + bot account id).
- The credential rides `cs.commands` encrypted; short retention (ADR-014) still applies.

---

## ADR-014 — Kafka at-rest posture: no app-layer Tink on the bus (v1)

**Context.** Source-quoting review output (`ReviewGenerated.findings[].message/suggestion`,
`FollowUpGenerated.answerText`) rides **inline** on `cs.results` (ADR-011), and Kafka/Redpanda brokers
persist topic messages to disk for the retention window — so this data rests on the broker in a form
the app does not encrypt. Three stated goals collide: (1) ADR-011 findings-inline; (2) the "source
never rests in cleartext" bar; (3) the KEK blast radius (workers are plaintext-only, no KEK). Only two
can hold as written.

**Decision (v1): accept + infrastructure-encrypt.** App-layer Tink is **not** applied to bus payloads.
The broker boundary is covered instead by:
- **Short retention on `cs.results`** (hours, not days — it is a work queue, not the source of truth;
  the durable copies are the encrypted event log, the encrypted `review_finding` table, and the PR
  itself).
- **Broker disk/volume encryption** (LUKS/cloud-disk encryption on Redpanda/Kafka data dirs) — a
  deployment requirement documented for self-hosters.
- Transport stays SASL/mTLS (SECURITY).

This keeps findings small on the bus (ADR-011 intact) and keeps the KEK held by exactly two services
(ADR-013/SECURITY intact), at the cost of scoping the "never rests in cleartext" guarantee to
**application-managed stores** (Postgres, MinIO) — now stated honestly in SECURITY.md.

**Escalation path** if a stricter threat model ever demands app-layer encryption everywhere: move
findings off the bus behind an encrypted blob ref (reversing ADR-011's inline choice) — option noted,
not taken for v1.

---

## ADR-013 — Operational & distributed-systems guards (correctness before scaffolding)

Resolves edge cases the async / at-least-once design creates. Decided defaults:

- **Ignore bot-authored events (self-loop).** `ScmIngress` drops any webhook whose actor ==
  the bot's own `providerUserId`. Without this, the bot's follow-up comment fires
  `comment_created` and it answers itself forever.
- **Comment idempotency (non-idempotent side effect).** Posting a comment is not idempotent, and
  `consumed_event` dedups *consumption*, not the external effect. Implemented semantics (P1): the
  worker CLAIMS `(reviewId, commit, anchorKey)` before posting and stores the comment id after; a
  row **with** a comment id is final proof-of-post (skipped forever, id reused to reconstruct
  `CommentsPosted` on redelivery); a row **without** one is a crashed claim and is **reclaimable**
  — the retry re-posts rather than silently losing the comment (one duplicate possible only in the
  narrow posted-but-not-marked crash window; at-least-once preferred over loss). Stronger
  reconcile-by-listing-bot-comments remains the escalation if that window ever matters.
- **Stale-run pre-check (not just discard).** Before the expensive `GenerateReview` LLM call and
  before `PostComments`, the worker checks the aggregate's current commit; if the run's commit is no
  longer current, it abandons — **no LLM spend, no stale comment on an old commit**.
- **Cancellation.** Only **PR close/merge/decline** (and operator actions) emit `CancelReview{reviewId}`;
  in-flight workers check cooperatively at each stage boundary (best-effort cancel of the LLM call).
  **Supersede does NOT emit `CancelReview`** — it is fully handled by the workers' stale-run pre-check.
  (A supersede-triggered cancel would be a bug: by the time it reached the aggregate, `currentCommit`
  is already the new commit, so the unconditional REVIEWING→CANCELLED row would kill the new run.)
- **PR closed/merged/declined.** `ScmIngress` translates close events → `PullRequestClosed`; a saga
  cancels any in-flight review and halts further stages.
- **Forced re-review.** `RequestReview{force}` bypasses the reviewed-commit idempotency; a human
  `/review` command sets `force=true`.
- **Head-SHA identity & 12-char expansion.** The idempotency/supersede key uses the provider's head
  identifier **as delivered** (Bitbucket's 12-char short hash is a stable, repo-unique prefix). The
  40-char SHA is expanded **only in the worker** when an outbound API needs it (GitHub `commit_id`) —
  never in the gateway, preserving the "ingress returns 202, no processing" rule.
- **Aggregator timer ownership.** Context aggregation for a `reviewId` is owned by the single
  consumer of that `reviewId`'s Kafka partition; the completeness timeout is a **DB-backed scheduled
  sweep** keyed by `reviewId` (survives rebalance/restart), not an in-memory timer.
- **Retry / resilience budgets** (SmallRye Fault Tolerance) per external-call class: SCM read/write
  10s timeout, 3 retries w/ exp backoff + jitter, circuit breaker; LLM 60s timeout, 1 retry then the
  provider-fallback saga (cost-aware — no retry storms on a paid call); context providers time-boxed
  by the 20s aggregator. Budget exhausted → `cs.dlq` + `ReviewFailed`, surfaced on the dashboard with
  a replay action (FR-8) — "in the DLQ" never means silently dropped.
- **Truncated-diff behavior — never silent.** If `Diff.truncated`/`FilePatch.tooLarge`, the summary
  comment states which files / how many lines were not fully reviewed.
- **Cross-service schema compatibility.** `spire-contract` events get round-trip + snapshot tests in
  CI; a compat-gate fails the build on a breaking change lacking an `eventVersion` bump + upcaster.
  A schema registry is considered post-v1.
- **Bitbucket description quirk.** The gateway forwards the webhook's raw description; the worker
  (already calling Bitbucket for the diff) fetches the authoritative PR resource when the rendered
  markdown description is needed — no extra call in the gateway.

LLM-specific threats (prompt injection, output sanitization, untrusted retrieved content) and
cost/abuse caps are in SECURITY.md.

---

## ADR-012 — Provider-neutral SCM model, verified against 4 real APIs

**Decision:** the canonical SCM value types are a true common denominator across Bitbucket Cloud, GitHub,
GitLab, and Bitbucket DC — verified against their official API docs, not assumed. Key shapes:
- **`Author{providerUserId, username, displayName, email?}`** — key on the stable `providerUserId`
  (never the mutable username); `email` optional (only Bitbucket DC exposes it), never logged/persisted.
- **`DiffRefs{baseSha, startSha, headSha}`** on the PR — GitLab *requires all three* to anchor an inline
  comment; GitHub uses `headSha` as `commit_id`; Bitbucket needs none. Carry all; populate what's given.
- **`DiffLine{type, oldLine, newLine, content}`** — every diff line carries BOTH line numbers; this is
  what lets one `InlineAnchor` map to every provider's anchoring scheme.
- **`ThreadRef` is opaque** — a comment id for Bitbucket/GitHub/DC, a *discussion_id* for GitLab
  (GitLab threads are discussions, not comment chains).
- **`ScmIngress.verifySignature` is per-provider** — HMAC-SHA256 for GitHub/Bitbucket, a constant-time
  static-token compare for GitLab (`X-Gitlab-Token`, not HMAC).

**Why:** inline-comment anchoring + threading diverge hard across providers; modelling top-down from
Bitbucket would have broken GitLab (mandatory SHAs, discussion threading) and needed rework for
GitHub/DC. Verifying first means the plugin-first "any SCM" promise is real, not aspirational.

Full per-provider field mappings + quirks + sources: **SCM-MAPPING.md**.

---

## ADR-011 — Data model: no diff persistence, S3/MinIO for transient blobs, schema-per-service

**Decisions (reviewed):**
1. **Diffs are never persisted** — re-fetched from Bitbucket by `(repo, commit)`; `DiffFetched` carries
   metadata only. Minimizes stored source (a liability) and keeps replay correct.
2. **Object store = S3-compatible (MinIO self-host)** from v1 — not Postgres blobs — to avoid a later
   migration. Holds only **transient assembled-context**, client-side Tink-encrypted, TTL auto-deleted.
   Behind a `BlobStore` port (swappable to AWS S3 / GCS).
3. **One Postgres, schema-per-service** for v1 (logical ownership, simpler ops).
4. **Snapshots deferred** until streams grow.
5. **Findings** ride inline in `ReviewGenerated` (small) and are projected to a `review_finding`
   read-model table (encrypted message/suggestion) — not stored as blobs.
6. **`spire-ui` owns the dashboard read models** (status/thread/finding/event); the context-aggregation
   view lives in `spire-context-worker`. No dedicated projection service.

See DATA-MODEL.md.

---

## ADR-010 — Contract conventions: single-writer aggregate, integration-vs-domain events, reviewId keying

**Decision:** (1) The `ReviewLifecycle` aggregate is the **sole writer** of its domain-event stream;
workers never append to aggregate streams — they emit *integration* result events that sagas translate
into Record commands. (2) Two explicit event kinds: **integration events** (boundary/ingress/worker
results) vs **domain events** (aggregate-sourced source of truth). (3) Everything is keyed by
`reviewId` (= one aggregate per PR) for strict per-PR ordering. (4) The aggregate holds only
decision-relevant state (idempotency + completion); fine-grained progress lives in read models. (5)
Large blobs (diff, findings, context) are stored encrypted and referenced by id; events stay small.

**Why:** single-writer gives clean optimistic concurrency; the integration/domain split keeps the
aggregate pure while allowing async workers; reviewId keying makes ordering trivial; small events keep
Kafka healthy and avoid putting source code on the bus in cleartext. See CONTRACT.md.

**Confirmed facts:** SCM target = **Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed
webhooks). Local dev = **docker-compose** (Redpanda + Postgres + Keycloak).

---

## ADR-009 — Clean-room, OSS-standard security (no private-code reuse)

**Decision:** Code Spire depends only on public building blocks. The private monorepo
(`encryption-common`, Keycloak config) is **design reference only — zero code copied**.

**Why:** Code Spire is public OSS that strangers self-host; it cannot ship private artifacts (users
don't have them), and copying private code into a public repo relicenses IP and risks leaking secrets
(Keycloak realm exports contain client secrets/redirect URIs). Reuse the *patterns*, not the *code*.

**The stack:**
- **Encryption at rest:** Google Tink (AES-GCM envelope, key ids + rotation), field-level via a JPA
  `AttributeConverter`. **Event payloads are encrypted** because findings/context items may quote source code (diffs themselves are never stored — ADR-011) —
  not just token columns.
- **Human auth:** `quarkus-oidc`, provider-pluggable (Keycloak recommended, not required); auth-code + PKCE.
- **RBAC:** roles `spire-viewer` / `spire-admin` via `@RolesAllowed`.
- **Webhook:** HMAC + source allow-list (machine, no OIDC).
- **Service→service:** OAuth2 client-credentials for REST; Kafka SASL/mTLS for the bus (most traffic).
- **Secrets:** env / K8s Secret / Vault, never in image.

**Later:** if a piece (likely encryption) proves broadly reusable, extract it to its *own* public
library under Apache-2.0 that both the monorepo and Code Spire depend on. See SECURITY.md.

---

## ADR-008 — Microservices (revised; supersedes the earlier modular-monolith call)

**Decision:** Multiple independently-deployable Quarkus services over a **Kafka** event backbone:
`spire-gateway`, `spire-orchestrator`, `spire-review-worker`, `spire-context-worker`,
`spire-indexer` (P3), `spire-ui`, + shared `spire-contract` lib. Kafka is a **v1 dependency**.

**Why (revised):** the earlier modulith call assumed building the platform from scratch. The author
runs a mature Maven microservice platform (Keycloak, gateways, devops) and prefers this topology as
the primary supported deployment — the fixed cost of another service is already paid, and the
event-driven design maps naturally onto per-service choreography.

**Consequences:** a durable broker is required from day one (Kafka/Redpanda; in-memory connector kept
for dev/test). `spire-orchestrator` owns the event store; workers are stateless consumers/producers.
Per-service durability via transactional-outbox → Kafka → idempotent consumers (at-least-once).
Heavier for external adopters — accepted as an explicit choice (ADR-006 is personal/OSS but the author
optimizes for their own topology; simplicity for strangers is secondary).

**Note:** the original modulith rationale (trivial one-container run) still stands as a *possible*
future "all-in-one" packaging if broad adoption ever makes it worthwhile — not pursued now.

**Build sequencing (refinement).** Separate services cannot talk over the SmallRye *in-memory*
connector (it doesn't cross process boundaries), so **Phase 0 runs all modules in one process** (the
in-memory connector as a dev/test harness) to prove the pipeline; **Phase 1+ split into the `spire-*`
deployables over Redpanda/Kafka.** The **target topology stays microservices** — only the build order
is modulith-first, which also de-risks single-process timers and idempotency before Kafka is added.
Modules are written behind the ports from day one so the split is a wiring change, not a rewrite.

---

## ADR-007 — Event store: Postgres for v1; KurrentDB as an optional adapter, not the default

**Decision:** v1 uses an append-only **Postgres** table behind an `EventStore` port. **KurrentDB**
(the rebranded EventStoreDB, first release under the new name = KurrentDB 25.0, 2025) is kept as a
*possible pluggable adapter*, not a hard dependency.

**Why not KurrentDB as the default, despite the best technical fit:** KurrentDB is event-native
(streams, append-with-expected-version, catch-up subscriptions, `$all`, projections) with an official
Java SDK — genuinely the most purpose-built option. **But since v24.10 it is licensed under Event
Store License v2 (ESLv2), a variant of the Elastic License v2 — explicitly NOT OSI open source, i.e.
"source-available."** ESLv2 restricts offering it as a hosted/managed service and gates enterprise
features behind a paid key. For a **public Apache-2.0 project** that others will self-host (and we
might one day host), hard-depending on a source-available, competitor-restricted datastore is a
strategic liability and off-putting to contributors. Self-hosting our own reviews would be fine; a
core dependency is not.

**Consequence:** The `EventStore` port hides the choice. v1 = Postgres append-only (permissive,
zero new moving parts, trivially embeddable). If we outgrow it, evaluate license-clean options
(Postgres + thin event-store layer, Marten-style patterns) before any source-available engine. Anyone
who wants KurrentDB's subscriptions/projections can write the adapter.

Sources: https://github.com/kurrent-io/KurrentDB ·
https://www.kurrent.io/releases/kurrentdb/25-0/ ·
https://www.kurrent.io/blog/introducing-event-store-license-v2-eslv2 ·
https://discuss.kurrent.io/t/important-information-eventstoredb-is-transitioning-to-event-store-license-v2-eslv2-with-the-upcoming-24-10-lts-release/5423

---

## ADR-006 — Personal open-source project

**Decision:** Code Spire is a personal, public open-source project (intended Apache-2.0), built
in private time — not internal tooling.

**Why:** The fillable market gap (plugin-first + self-hosted whole-repo RAG + residency-friendly)
is broadly useful, not employer-specific. Open-sourcing brings more options to a market where the
only mature OSS option (PR-Agent) is Python/single-shot and the good tools are closed SaaS. Keeping
it public also sidesteps any internal-IP entanglement.

**Consequence:** All docs are domain-neutral (no employer references). License decided before first
commit. Maintenance is a real commitment (issues, CVEs, releases) — accepted as the cost of the bet.

---

## ADR-005 — Event Modeling as the design method

**Decision:** Model the domain with Event Modeling; formalize with the Fraktalio
`Decider / View / Saga` triad.

**Why:** The review pipeline is a sequence of state changes reacting to facts — a natural fit.
Event Modeling gives a shared blueprint (slices), and the fmodel formalism maps cleanly to pure,
testable, event-sourced components. See [EVENT-MODEL.md](EVENT-MODEL.md).

---

## ADR-004 — Fully event-driven core, no synchronous processing

**Decision:** Components communicate only via asynchronous events/commands (choreography). The only
synchronous edges are at the system boundary (inbound webhook → 202; outbound SCM/LLM API calls),
isolated inside adapter plugins.

**Why:** (1) It is the structural enabler of the plugin-first goal — a plugin is a component that
subscribes to and emits events, so capabilities attach with zero core change. (2) Replayable,
auditable review history. (3) Natural back-pressure and horizontal scale for PR bursts.

**Consequence:** Requires an event store + messaging backbone (SmallRye Reactive Messaging over the
Kafka protocol — Redpanda/Kafka from v1; in-memory connector for dev/test). Idempotent deciders keyed
by event id. Slightly more upfront machinery than a request/response service — accepted.

---

## ADR-003 — Stack: Quarkus + WebSockets + LangChain4j

**Decision:** Quarkus (Java) reactive core; SmallRye Reactive Messaging as the event bus; Quarkus
WebSockets Next for live read-model/token push; LangChain4j for LLM provider adapters.

**Why:** Matches the author's Java competency (explicit non-preference for Python — the deciding
factor against forking PR-Agent). Quarkus gives reactive messaging, CDI-based plugin discovery,
fast startup, and GKE/container friendliness. WebSockets carries the read side / live dashboard.

---

## ADR-002 — Build (hybrid greenfield), do not fork PR-Agent

**Decision:** Greenfield in Quarkus, but **port PR-Agent's hard-won algorithms + prompts** rather
than reimplement them from scratch.

**Why:** A 5-part code review of `qodo-ai/pr-agent` (22k LOC Python) found: SCM abstraction 3/5
(a 50-method God-object ABC; **thread-reply and PR-author are unimplemented on both Bitbucket
providers** — exactly the two features needed); plugin extensibility **2/5** (hardcoded dispatch
dict, single-shot engine with no tool-use loop, no hook point for RAG); LLM layer 3.5/5 (embedded
LiteLLM; the diff/token/prompt logic is the real IP); RAG/memory **0/5** (diff-only reviews; its one
vector feature indexes *issues, not code*, GitHub-only — the differentiator is greenfield either
way); quality 3/5 (stateless, but 729× global-config coupling).

Cost: extend Python ≈ 5–9 pw · full greenfield ≈ 12–20 pw · **hybrid greenfield ≈ 8–12 pw**.
Extending is ~2× cheaper but saves only ~2k LOC of diff algorithms (which are *portable*) while
leaving the plugin system, RAG/memory, and Bitbucket thread-reply/author to build anyway — on top
of a Python codebase that structurally fights the agentic vision and won't be staffed.

**Port faithfully:** `git_patch_processing.py`, `pr_processing.py`, `token_handler.py`/`clip_tokens`,
YAML-repair, and the ~1,500 lines of prompt templates (→ Qute). **Build clean:** the event-driven
core, the plugin SPI, segregated SCM ports (thread-reply + author first-class), the context-provider
pipeline, injected config.

---

## ADR-001 — Self-hosted, provider-agnostic, one-bot reviewer

**Decision:** A single bot service reviews every PR in a workspace via a workspace/project-level
webhook + one service identity — no per-seat licensing. Source-control platform, LLM provider,
context sources, and storage are all pluggable and chosen at configuration time (no hard defaults;
fail-fast if unset). Code and inference can stay entirely self-hosted.

**Why:** Rules out per-seat SaaS (Rovo Dev is per-user; Qodo Merge/CodeRabbit are per-contributor
and/or SaaS-egress). Greptile — the closest inspiration — does not support Bitbucket at all and is
closed. The one-bot + webhook model is what makes "all PRs, author-agnostic, no seats" true.
Author identity is optional data captured on every event (per-user analytics later), never a gate.

**On LLM routing:** do **not** route inference through GitHub Copilot's backend — it has no official
API; only reverse-engineered OAuth proxies exist (unsupported, ToS-risky). Use direct provider APIs
(Vertex/Anthropic/Azure) or in-cluster models (Ollama) via LangChain4j, selected at config.
