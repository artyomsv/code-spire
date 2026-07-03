# Domain Contract (`spire-contract`)

> The shared kernel every service depends on: identifiers, the event envelope, the event & command
> catalog, the `ReviewLifecycle` decider, the SPI ports, the context-aggregation policy, topics, and
> the Bitbucket **Cloud** mapping. Companion to [EVENT-MODEL.md](EVENT-MODEL.md) (the narrative slices)
> and [ARCHITECTURE.md](ARCHITECTURE.md). Status: design — pre-implementation.

## 1. Conventions

- **Two event kinds.** *Integration events* cross a system boundary (ingress from SCM, results from
  workers, egress). *Domain events* are appended by an **aggregate** and are the source of truth for
  its state. Only the aggregate writes its own domain-event stream (single-writer → clean optimistic
  concurrency); workers never write aggregate streams directly.
- **Naming.** Events = past tense (`ReviewRequested`). Commands = imperative (`RequestReview`).
- **Everything is keyed by `reviewId`** for per-PR ordering (partition key on every topic).
- **Idempotent by `eventId`** at every consumer; re-delivery is safe.
- **Additive evolution only** — new optional fields; breaking changes get a new `eventVersion` + an upcaster. Published events are never mutated.

## 2. Identifiers

| Id | Shape | Notes |
|---|---|---|
| `RepoRef` | `{workspace}/{repoSlug}` | Bitbucket Cloud workspace + repo |
| `prId` | `long` | Bitbucket PR number, unique within a repo |
| `reviewId` | `review::{workspace}/{repoSlug}#{prId}` | **aggregate stream id** — one `ReviewLifecycle` per PR |
| `commit` | provider head identifier **as delivered** (12-char short hash on Bitbucket Cloud) | the PR **head** commit a run targets; the idempotency/supersede key. Expanded to the 40-char SHA only in the worker where an outbound API requires it (ADR-013) |
| `threadRef` | opaque thread handle (`ThreadRef`) | conversation anchor — comment id on BB/GH/DC, discussion_id on GitLab (SCM-MAPPING §6) |
| `eventId` | UUID | globally unique; dedup key |
| `correlationId` | = `reviewId` | threads the whole flow across services |
| `causationId` | UUID | the command/event that caused this one |

## 3. Event envelope

Every event (integration or domain) is wrapped:

```
{
  eventId: UUID,          // dedup key
  eventType: string,      // "ReviewRequested"
  eventVersion: int,      // schema version, starts at 1
  streamId: string,       // aggregate id (domain) or source id (integration)
  sequence: long,         // per-stream monotonic (domain events only; optimistic concurrency)
  occurredAt: Instant,    // producer clock
  correlationId: string,  // = reviewId
  causationId: UUID,      // parent event/command id
  actor: string,          // "system" | "bot:<name>" | "webhook" | "operator:<sub>"
  payload: { … }          // type-specific, below
}
```
`globalPosition` (a monotonic long) is assigned by the event store on append and used by the dispatcher; it is store metadata, not part of the published payload.

## 4. Event catalog

### Integration events (ingress / worker results / egress)

| Event | Producer | Payload (beyond envelope) |
|---|---|---|
| `PullRequestEventReceived` | `spire-gateway` | repo, prId, action(`OPENED`/`UPDATED`), title, description, sourceBranch, targetBranch, **diffRefs{baseSha,startSha,headSha}** (populate what the provider gives), author{**providerUserId,username,displayName**}, htmlUrl |
| `AuthorReplied` | `spire-gateway` | repo, prId, reviewId, **threadRef**, commentId, text, author{providerUserId,username} |
| `PullRequestClosed` | `spire-gateway` | repo, prId, reason(`MERGED`/`DECLINED`) — triggers the cancel saga (ADR-013) |
| `ManualCommandReceived` | `spire-gateway` | repo, prId, command(`review`/…), args, author{providerUserId,username} — parsed from a `/command` PR comment; the saga maps `review` → `RequestReview{force=true}` |
| `PushReceived` *(P3)* | `spire-gateway` | repo, ref, commits[] |
| `DiffFetched` | `spire-review-worker` (via `DiffSource`) | reviewId, prId, commit, changedFiles, languages[], sizeBytes, truncated — **metadata only; no diff content** (deliberate two-fetch: content is re-fetched by commit at generate time; a 404 on re-fetch means the commit was force-pushed away → treat as superseded) |
| `ContextRequested` | `spire-context-worker` | reviewId, repo, prId, commit, hints{ticketKeys,links}, **expectedSources[]** — fan-out signal each `ContextProvider` subscribes to (§8) |
| `ContextContributed` | each `ContextProvider` | reviewId, source(`JIRA`/`CONFLUENCE`/`RULES`/`RAG`/`MEMORY`), status(`OK`/`EMPTY`/`ERROR`), items[], latencyMs |
| `ContextAssembled` | aggregator | reviewId, prId, commit, contextRef, contributingSources[], missingSources[] |
| `ReviewGenerated` | `spire-review-worker` (via `LlmProvider`) | reviewId, prId, commit, findings[] (inline `ReviewResult`, small), summary, model, tokensIn, tokensOut, costMillicents |
| `ReviewFailed` | any worker | reviewId, commit, phase, error, retryable, attempt |
| `CommentsPosted` | `spire-review-worker` (via `CommentSink`) | reviewId, prId, commit, summaryCommentId, inline[]{commentId,path,line} |
| `FollowUpGenerated` | `spire-review-worker` | reviewId, threadRef, answerText |
| `FollowUpPosted` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef, commentId |

> Only the **assembled context** is offloaded to the object store (encrypted, referenced by `contextRef`)
> so events stay small. **Diffs are never stored** (re-fetched by commit); **findings** ride inline in
> `ReviewGenerated` (small) and are projected to the `review_finding` read model. See DATA-MODEL.md.

### Domain events (appended by the `ReviewLifecycle` aggregate)

| Event | Meaning |
|---|---|
| `ReviewRequested` | a run started for `commit` (trigger OPENED/UPDATED) |
| `ReviewSuperseded` | a newer commit arrived mid-run; the old run is abandoned |
| `ReviewOutcomeRecorded` | the aggregate acknowledges a produced review for `commit` |
| `ReviewCompleted` | comments posted; run done; `commit` added to reviewed set (carries `summaryCommentId` so `evolve` can fold it into state) |
| `ReviewFailedTerminally` | non-retryable failure; run ended |
| `ReviewCancelled` | the PR was closed/merged/declined mid-run (or an operator cancelled); run abandoned |
| `ThreadOpened` | a conversational thread was started |
| `FollowUpRecorded` | a follow-up answer was posted in a thread |

## 5. Command catalog

**Action commands** (to workers/adapters — cause side effects, produce *integration* result events):

| Command | Handler | Payload |
|---|---|---|
| `FetchDiff` | `spire-review-worker` | reviewId, repo, prId, commit |
| `GatherContext` | `spire-context-worker` (fan-out) | reviewId, repo, prId, commit, hints{ticketKeys,links} |
| `GenerateReview` | `spire-review-worker` | reviewId, prId, commit, contextRef, attempt, providerOverride? (set by the fallback saga on retry; worker re-fetches the diff by commit) |
| `PostComments` | `spire-review-worker` | reviewId, repo, prId, findings[] (inline — same `ReviewResult` as `ReviewGenerated`; findings are not stored as blobs, ADR-011) |
| `AnswerFollowUp` | `spire-review-worker` | reviewId, repo, prId, threadRef, question — the worker fetches the thread history from the SCM on demand (no blob; same re-fetch philosophy as diffs) |

**Record commands** (to the `ReviewLifecycle` decider — append *domain* events):

| Command | Guard → Emits |
|---|---|
| `RequestReview{commit,trigger,force}` | new commit → `ReviewRequested`; if newer run active → also `ReviewSuperseded`; `force=true` bypasses the reviewed-commit idempotency (FR-12) |
| `CancelReview{reason}` | REVIEWING → `ReviewCancelled` (from `PullRequestClosed` or an operator action) |
| `RecordReviewOutcome{commit,findingsCount,summaryDigest}` | commit==current → `ReviewOutcomeRecorded` (aggregate keeps only a digest, not the findings) |
| `RecordCommentsPosted{commit,summaryCommentId,count}` | commit==current → `ReviewCompleted` |
| `RecordFailure{commit,phase,retryable}` | commit==current AND !retryable → `ReviewFailedTerminally` (stale failure from a superseded run → no-op) |
| `OpenThread{threadRef,parentCommentId}` | → `ThreadOpened` |
| `RecordFollowUp{threadRef,commentId}` | → `FollowUpRecorded` |

Sagas translate integration result events → the next Action command *and* the matching Record command
(e.g. on `CommentsPosted` → `RecordCommentsPosted`; on `PullRequestClosed` → `CancelReview`; on
`ManualCommandReceived{review}` → `RequestReview{force=true}`). Fine-grained progress (diff fetched,
context assembled) lives in the **read model**, not the aggregate — the aggregate holds only
decision-relevant state.

## 6. `ReviewLifecycle` decider

**State**
```
reviewId, repo, prId
status: IDLE | REVIEWING | COMPLETED | FAILED | CANCELLED
currentCommit: sha | null
reviewedCommits: Set<sha>          // idempotency across redeliveries
summaryCommentId: string | null
threads: Map<threadRef, {status, lastCommentId}>
```

**decide(command, state) → events**

| In state | Command | Guard | Emits | Next state |
|---|---|---|---|---|
| any | `RequestReview{c, force=false}` | `c ∈ reviewedCommits` or `c == currentCommit` | `[]` (idempotent no-op) | — |
| any | `RequestReview{c, force=true}` | — (bypasses reviewed-commit idempotency, FR-12) | (`ReviewSuperseded{currentCommit}` if a run is active) + `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| | | | *note: forcing while `c == currentCommit` is mid-run deliberately yields `ReviewSuperseded{c}` + `ReviewRequested{c}` — a commit superseding itself, modeled as **restart the run*** | |
| `REVIEWING` | `RequestReview{c}` | `c != currentCommit` | `ReviewSuperseded{currentCommit}` + `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| `IDLE/COMPLETED/FAILED/CANCELLED` | `RequestReview{c}` | new commit | `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| `REVIEWING` | `RecordReviewOutcome{c}` | `c == currentCommit` | `ReviewOutcomeRecorded` | REVIEWING |
| `REVIEWING` | `RecordCommentsPosted{c}` | `c == currentCommit` | `ReviewCompleted{c}` | COMPLETED, +reviewedCommits, summaryCommentId set |
| `REVIEWING` | `RecordFailure{c,retryable=false}` | **`c == currentCommit`** (stale failure from a superseded run → no-op) | `ReviewFailedTerminally` | FAILED |
| `REVIEWING` | `CancelReview{reason}` | — | `ReviewCancelled{reason}` | CANCELLED |
| `IDLE/COMPLETED/FAILED/CANCELLED` | `CancelReview` | — | `[]` (nothing in flight — no-op) | — |
| any | `OpenThread` / `RecordFollowUp` | — | `ThreadOpened` / `FollowUpRecorded` | threads updated |

**Invariants**
- One active run per PR; a newer commit supersedes an in-flight run (latest-commit-wins).
- A commit is reviewed at most once (`reviewedCommits`) unless re-review is forced (`force=true`).
- Stale worker results **and stale failures** (`commit != currentCommit`) are ignored — they belong to
  a superseded run and must never flip the state of the current run.
- A closed/merged/declined PR cancels the in-flight run (CANCELLED); a reopened PR starts fresh via
  `RequestReview`.
- Conversational threads never block or change the main `status`.

**Idempotency keys**
- Consumers dedup on `eventId`.
- `RequestReview` idempotent by `(reviewId, commit)` (unless `force`).
- Workers idempotent by `causationId` (never re-fetch/re-generate for the same command).
- Comment posting idempotent by `(reviewId, commit, anchorKey)` persisted **before** the external call,
  with reconcile-on-retry (ADR-013; table in DATA-MODEL §5).

## 7. SPI ports (hand-rolled interfaces)

Core abstractions (in `spire-contract`):
```java
interface Decider<C, S, E> { S initialState(); List<E> decide(C c, S s); S evolve(S s, E e); }
interface View<S, E>       { S initialState(); S evolve(S s, E e); }
interface Saga<E, C>       { List<C> react(E e); }
```

Plugin ports:
```java
interface ScmIngress {                                  // gateway
  boolean verifySignature(RawWebhook raw);
  List<IntegrationEvent> translate(RawWebhook raw);     // → PullRequestEventReceived / PullRequestClosed / ManualCommandReceived / AuthorReplied / PushReceived
}
interface DiffSource {                                  // scm adapter
  ScmType type();
  PullRequest fetchPullRequest(RepoRef repo, long prId);
  Diff fetchDiff(RepoRef repo, long prId, String commit);   // canonical FilePatch list
}
interface CommentSink {                                 // scm adapter
  ScmType type();
  CommentRef postSummary(RepoRef repo, long prId, String bodyMd);
  CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd);
  CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd);  // ThreadRef, not bare id
  Author     getPullRequestAuthor(RepoRef repo, long prId);
}   // DiffRefs feeds GitLab/GitHub anchoring; ThreadRef = comment id (BB/GH/DC) or discussion_id (GitLab). See SCM-MAPPING.md
interface ContextProvider {                             // jira / confluence / rules / rag / memory
  String source();
  boolean supports(ContextRequest req);
  Uni<ContextContribution> contribute(ContextRequest req);   // async; may return EMPTY/ERROR
}
interface LlmProvider {                                 // langchain4j default impl
  String id();
  Uni<Completion> complete(Prompt prompt, ModelParams params);
}
interface Capability {                                  // review (v1), describe, changelog…
  String name();
  Set<String> commands();          // e.g. {"/review"}
  CapabilityConfig defaultConfig();
  Uni<Void> run(CapabilityContext ctx);   // ctx exposes diff, assembled context, ports, resolved config
}
```
Discovery: each is a CDI bean; the core injects `@All List<ContextProvider>` etc. Config selects the
active `LlmProvider`/`DiffSource`. Adding a plugin = new bean, no core edit.

## 8. Context-aggregation policy

1. On `GatherContext`, the context-worker computes `expectedSources` = every enabled `ContextProvider`
   where `supports(req)` is true, and emits `ContextRequested{expectedSources}`.
2. Each provider emits `ContextContributed{status}` (`OK`/`EMPTY`/`ERROR`).
3. A stateful aggregator (a `View` + a per-review timer) tracks arrivals. It emits `ContextAssembled`
   when **received ⊇ expected** OR a **timeout `T` (default 20s)** elapses, recording
   `contributingSources` and `missingSources`.
4. Guarantees the pipeline never blocks on a slow/broken provider; missing sources degrade gracefully.

## 9. Kafka topics (keyed by `reviewId`)

| Topic | Carries |
|---|---|
| `cs.integration` | ingress events (`PullRequestEventReceived`, `PullRequestClosed`, `ManualCommandReceived`, `AuthorReplied`, `PushReceived`) |
| `cs.commands` | action + record commands |
| `cs.events` | aggregate domain events |
| `cs.results` | worker-produced integration events (`DiffFetched`, `ContextRequested`, `ContextContributed`, `ContextAssembled`, `ReviewGenerated`, `ReviewFailed`, `CommentsPosted`, follow-ups). **Short retention** — carries source-quoting payloads without app-layer encryption (ADR-014) |
| `cs.dlq` | dead-letters (after retry budget); surfaced on the dashboard with a replay action (FR-8) |

All keyed by `reviewId` so a PR's messages are strictly ordered within a partition. (Topic split is a
starting point; can be refined — the keying discipline is the important invariant.)

## 10. Bitbucket **Cloud** mapping

Webhook subscriptions on the bot's repo/workspace hook: `pullrequest:created`, `pullrequest:updated`,
`pullrequest:comment_created`, **`pullrequest:fulfilled`** (merged), **`pullrequest:rejected`**
(declined) — the close events feed `PullRequestClosed` → the cancel saga (+ `repo:push` for P3).
Verify `X-Hub-Signature` (HMAC) with the hook secret.

| Contract field | Bitbucket Cloud webhook path |
|---|---|
| repo | `repository.workspace.slug` + `/` + `repository.name` |
| prId | `pullrequest.id` |
| action | event key: `created`→OPENED, `updated`→UPDATED, `fulfilled`→CLOSED(MERGED), `rejected`→CLOSED(DECLINED) |
| title / description | `pullrequest.title` / `pullrequest.rendered.description.raw` |
| headCommit | `pullrequest.source.commit.hash` |
| sourceBranch / targetBranch | `pullrequest.source.branch.name` / `pullrequest.destination.branch.name` |
| author | `pullrequest.author.{account_id, nickname, display_name}` |
| htmlUrl | `pullrequest.links.html.href` |
| (comment) threadRef/parent | `comment.id` / `comment.parent.id` |
| (comment command) | `ScmIngress` parses the comment body: if it starts with a registered `/command` (e.g. `/review`, from `Capability.commands()`), emit `ManualCommandReceived{command,args}` instead of `AuthorReplied`. Bot-authored comments are dropped before either (ADR-013) |

REST (`api.bitbucket.org/2.0`), auth = bot **App Password** (Basic) or OAuth, scopes `pullrequest:write`,
`repository:read`:
- diff: `GET /repositories/{ws}/{repo}/pullrequests/{id}/diff`
- summary comment: `POST …/pullrequests/{id}/comments` `{content:{raw}}`
- inline comment: same + `{inline:{path, to}}`
- thread reply: same + `{parent:{id}}`
- author: from the PR resource `GET …/pullrequests/{id}`

## 11. Versioning
`eventVersion` starts at 1 per type. Additive fields don't bump it; breaking changes bump it and ship an
upcaster (`vN → vN+1`) in `spire-contract`. Consumers tolerate unknown fields. Published events are immutable.
