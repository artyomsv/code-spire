# Data Model

> Defines the actual data: (1) the **domain value types** that flow through events & ports, and (2) the
> **persistence model** — the event store (the versioned source of truth), the blob store, and the
> read-model projections, with relationships and encryption. Companion to [CONTRACT.md](CONTRACT.md)
> (which names these types) and [SECURITY.md](SECURITY.md). Status: **for review** — §7 lists the open
> decisions I want your call on.

## 1. Two layers of data

| Layer | What | Where it lives |
|---|---|---|
| **Value types** | Immutable records that are event payloads and port arguments | in-memory / serialized in events & blobs |
| **Event store** | The append-only, versioned log of events — **source of truth** | `spire-orchestrator` Postgres |
| **Object store** | In-flight large payloads (assembled context), **encrypted client-side** | S3-compatible (**MinIO** self-host) via `BlobStore` port |
| **Read models** | Disposable projections rebuilt from events | owning service's Postgres (e.g. `spire-ui`) |
| **Symbol index** *(P3, rung 2)* | Code structure — identifiers and paths, never content | `spire-review-worker` Postgres (ADR-026) |

## 2. Domain value types (records)

Identity & SCM primitives (provider-neutral — mappings verified in [SCM-MAPPING.md](SCM-MAPPING.md)):
```java
record RepoRef(String workspace, String slug)                       // {workspace}/{slug}
record Author(String providerUserId, String username, String displayName, String email)
//   providerUserId = STABLE key (BB account_id / GH id / GL id / DC id); username is mutable;
//   email is OPTIONAL (only Bitbucket DC exposes it) — never logged or persisted.
record DiffRefs(String baseSha, String startSha, String headSha)    // GitLab needs all 3; others populate what they have
enum   Side { OLD, NEW }
record InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine, Side side)
//   derived from a DiffLine; each adapter maps it to its provider's anchor (see SCM-MAPPING §4)
record ThreadRef(String value)                                      // OPAQUE: comment id (BB/GH/DC) or discussion_id (GitLab)
enum   CommentKind { SUMMARY, INLINE, REPLY }
record CommentRef(String commentId, ThreadRef thread, CommentKind kind)
```

Pull request & diff:
```java
record PullRequest(RepoRef repo, long prId, String title, String description,
                   String sourceBranch, String targetBranch, DiffRefs diffRefs,
                   Author author, String htmlUrl)                   // prId = BB id / GH number / GL iid / DC id
enum   ChangeType { ADDED, MODIFIED, DELETED, RENAMED, COPIED }
enum   LineType   { ADDED, REMOVED, CONTEXT }
record DiffLine(LineType type, Integer oldLine, Integer newLine, String content)
//   carries BOTH line numbers -> this is what makes inline anchoring work on every provider
record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines)
record FilePatch(String oldPath, String newPath, ChangeType change, String language,
                 boolean binary, boolean tooLarge, List<Hunk> hunks)
record Diff(DiffRefs refs, List<FilePatch> files, boolean truncated)
```

Review output:
```java
enum   Severity { BLOCKER, MAJOR, MINOR, INFO, NIT }
record Finding(String path, LineRange range, Severity severity, String message,
               String suggestion)                                   // suggestion = proposed replacement, nullable
record ModelUsage(String model, int tokensIn, int tokensOut, long costMillicents)  // millicents (money rule)
record ReviewResult(List<Finding> findings, String summary, ModelUsage usage)   // rides inline in events (ADR-011)
```

Context:
```java
enum   ContribStatus { OK, EMPTY, ERROR }
record ContextItem(String kind, String title, String body, String uri)     // kind: JIRA_TICKET|CONFLUENCE_PAGE|ISSUE|PULL_REQUEST|EPIC|RULE|CODE_SNIPPET|MEMORY_NOTE
record ContextRequest(String reviewId, RepoRef repo, long prId, String commit,
                      Set<String> ticketKeys, List<String> links, Set<String> expectedSources)
record ContextContribution(String source, ContribStatus status, List<ContextItem> items, long latencyMs)
record AssembledContext(String contextId, List<ContextItem> items,
                        Set<String> contributingSources, Set<String> missingSources)
```

LLM:
```java
record Prompt(String system, String user)                           // rendered from Qute
record ModelParams(String model, double temperature, Integer maxTokens)
record Completion(String text, ModelUsage usage)
```

**Event payloads are records too** — the envelope (CONTRACT.md §3) wraps a typed payload. Example:
```java
record ReviewGenerated(String reviewId, long prId, String commit,
                       List<Finding> findings, String summary, ModelUsage usage)  // findings INLINE (small), not a blob — ADR-011
```

## 3. Persistence — the event store (versioned source of truth)

`event_log` — append-only, never updated or deleted:

| Column | Type | Notes |
|---|---|---|
| `global_position` | BIGSERIAL PK | monotonic; the dispatcher reads by this |
| `event_id` | UUID UNIQUE | dedup key |
| `stream_id` | TEXT | aggregate id, e.g. `review::ws/repo#42` |
| `sequence` | BIGINT | per-stream, 0-based |
| `event_type` | TEXT | e.g. `ReviewRequested` |
| `event_version` | INT | schema version |
| `payload` | BYTEA | **Tink-encrypted** JSON |
| `key_id` | TEXT | Tink key id (rotation) |
| `correlation_id` | TEXT | = reviewId |
| `causation_id` | UUID | parent id |
| `actor` | TEXT | system/bot/webhook/operator |
| `occurred_at` | TIMESTAMPTZ | producer clock |
| `recorded_at` | TIMESTAMPTZ | append time |

- `UNIQUE(stream_id, sequence)` → **optimistic concurrency**: appending with an already-taken sequence
  fails; the writer reloads and retries.
- Indexes: `(stream_id, sequence)` for aggregate load, `(global_position)` for the dispatcher.
- **Aggregate load** = `SELECT … WHERE stream_id = ? ORDER BY sequence` → fold via `evolve()`.
- `snapshot(stream_id PK, sequence, state BYTEA enc, key_id)` — optional, added later when streams grow.
- The `event_log` **doubles as the transactional outbox**: the dispatcher tails `global_position`,
  publishes to Kafka, and advances a checkpoint (no separate outbox table for the orchestrator).

## 4. Persistence — object store (S3 / MinIO)

Large **in-flight** payloads are encrypted objects, referenced by `…Ref` (the object key) in events.
Backend is **S3-compatible object storage — MinIO for self-host** — behind a `BlobStore` port
(swappable to AWS S3 / GCS). Established from v1 (not Postgres) to avoid a later migration.

- **What's stored:** the **assembled context** only (transient; can be large — Confluence pages, code
  snippets). **Diffs are NOT stored** (decision 1 — re-fetched by commit). **Findings are NOT objects**
  — they go to a read-model table (§5).
- **Client-side encryption:** payloads are **Tink-encrypted before upload**, so MinIO only ever holds
  ciphertext; `key_id` travels in object metadata.
- **Object key:** `CONTEXT/{reviewId}/{uuid}`.
- **Lifecycle:** a bucket **TTL rule auto-deletes** context objects shortly after a review completes.
- **Port:** `BlobStore { Ref put(Kind kind, byte[] plaintext); byte[] get(Ref ref); void delete(Ref ref); }`
  — Tink encryption lives inside the adapter.

## 5. Persistence — read models (projections, disposable)

Built by consuming `cs.events` / `cs.results`; **no FK to the event store** (decoupled, rebuildable).

`review_status` (per PR):
| Column | Type |
|---|---|
| `review_id` TEXT PK · `repo` TEXT · `pr_id` BIGINT · `author` TEXT · `title` TEXT | |
| `status` TEXT · `current_commit` TEXT · `phase` TEXT (diff/context/generate/post) | |
| `findings_count` INT · `last_summary_comment_id` TEXT | |
| `started_at` · `updated_at` TIMESTAMPTZ · `source_position` BIGINT (last applied) | |

`review_thread` (per conversation): `thread_id` TEXT PK (a `ThreadRef` value), `review_id` TEXT, `pr_id` BIGINT,
`status` TEXT, `last_comment_id` TEXT, `updated_at`.

`review_finding` (the review output — the durable corpus analytics and learned memory read, P4 /
ADR-027, **V36**): `id` BIGSERIAL PK, `review_id` TEXT, `round` INT, `commit_sha` TEXT, `path` TEXT,
`start_line` INT, `end_line` INT, `severity` TEXT, `category` TEXT (nullable), `origin` TEXT
(`review` | `conversation`), `message` TEXT (**Tink-encrypted**, AAD = `review_id` — may quote
source), `suggestion` TEXT (**encrypted**, nullable), `thread_ref` TEXT (nullable), `verdict` TEXT
(nullable), `verdict_at` TIMESTAMPTZ, `suppressed_by` BIGINT (nullable), `created_at`.

Four of those columns carry decisions rather than data, and this file is where they are recorded.
**`round`** is in the row because `review_status` overwrites per round, and a corpus with the same
amnesia cannot answer "did this get fixed". **`category`** is nullable for real rather than in
theory: prompts are operator-customizable per repository (E16), so a customized `REVIEW` template
never asks for it. **`verdict` null means NOT YET JUDGED**, which is a different fact from "judged
and unchanged" — conflating them would count every unreconciled finding as a dismissal.
**`thread_ref` null means generated but never posted**, which happens on a degraded run or a
per-finding post failure.

There is deliberately **no unique constraint**: `category` is nullable, Postgres treats NULLs as
distinct, so a key would fail to deduplicate exactly the uncategorized rows — and it would
simultaneously drop two legitimate findings of one category on one line. Redelivery idempotency is
delete-then-insert per `(review_id, round)` instead.

**No backfill.** Rows accrue from the day V36 shipped. Findings had only ever lived inline on Kafka
integration events (short retention, ADR-014) and in one overwritten `review_status` column, so
there was no history to recover — the same honest shape as the symbol index.

`operator_identity` (which SCM account an operator is, P4 / FR-11, **V37**): `oidc_subject` TEXT,
`provider_type` TEXT, `author_id` TEXT, `created_at` — PK `(oidc_subject, provider_type)`. Keyed
with the platform because a bare `providerUserId` is not a person: the same id on two SCMs belongs
to two unrelated humans. Admin-managed; inferring the link by matching usernames is refused, since
a coincidental match shows one person another person's performance data with nothing on screen
looking wrong.

`review_event` (flattened timeline for the dashboard): `id` BIGINT PK, `review_id`, `type`,
`at`, `summary` — non-sensitive projection of the log for the UI (no payloads).

Infra tables (per consuming service):
- `projection_checkpoint(name PK, last_position BIGINT)` — projector/dispatcher progress.
- `consumed_event(consumer TEXT, event_id UUID, PRIMARY KEY(consumer,event_id))` — idempotency dedup.

Operational state (not projections — ADR-013 guards):
- `comment_idempotency(review_id TEXT, commit TEXT, anchor_key TEXT, comment_id TEXT NULL,
  posted_at TIMESTAMPTZ NULL, PRIMARY KEY(review_id, commit, anchor_key))` — owned by
  `spire-review-worker`. Row inserted **before** the external post; `comment_id` filled on success;
  on retry, existing rows (reconciled against the PR's bot comments) are skipped — prevents duplicate
  comments under at-least-once. `anchor_key` = `SUMMARY` or `path:line:side` for inline.
- `context_deadline(review_id TEXT PK, expected_sources TEXT[], received_sources TEXT[],
  deadline_at TIMESTAMPTZ)` — owned by `spire-context-worker`. The **DB-backed timeout sweep**: a
  scheduled job emits `ContextAssembled` for rows past `deadline_at`; survives rebalance/restart
  (no in-memory timers).

## 6. Relationships (logical ERD)

```
 event_log (source of truth, append-only)
    │  payload.contextRef ─────────►  MinIO/S3 object (encrypted; CONTEXT; transient, TTL)
    │
    │  (dispatcher tails global_position → Kafka → projectors)
    ▼
 review_status ─(1)─(N)─ review_thread
       └──(1)──(N)── review_finding          review_event (timeline)
    (projections owned by spire-ui; rebuildable; source_position = replay cursor; NO FK to event_log)

 code_symbol (P3 rung 2)    ── standalone; (repo, symbol, path, role). Structure only, never content;
                               a hint confirmed at citation, never an answer (ADR-026)
 diffs: NEVER stored — re-fetched from Bitbucket by (repo, commit)
```

- **No hard FKs cross the event-store → projection boundary** — event sourcing decouples them; a
  projection is rebuilt by truncating and replaying from position 0 (or a snapshot).
- Within projections, relationships are *logical* (by `review_id`), not enforced FKs, so a projector
  can upsert out of order and self-heal on replay.
- `event_log` ↔ `blob` is a **soft reference** (id string in the payload), not a FK.

## 7. Decisions — RESOLVED

1. **Persist diffs? NO.** Re-fetched from Bitbucket by `(repo, commit)`; `DiffFetched` carries
   **metadata only** (files/languages/size), never diff content. No source in our storage.
2. **Object store? S3/MinIO now** (not Postgres) — established from v1 to avoid a later migration; holds
   only transient encrypted assembled-context with TTL auto-delete.
3. **DB topology? One Postgres, schema-per-service** for v1.
4. **Snapshots? Deferred** until streams grow.
5. **Retention/GC?** Source: not stored → nothing to GC. Context objects: auto-expire (TTL). Events:
   permanent. Findings: retained for dashboard/memory.
6. **Read-model owner? `spire-ui`** owns/projects the dashboard read models (status/thread/finding/event)
   — simplest (build + serve collocated). The context-aggregation view lives in `spire-context-worker`
   (operational state). No dedicated projection service.

## 8. Encryption at the data layer (recap, see SECURITY.md)
- **Encrypted:** `event_log.payload`, `snapshot.state` (Tink AES-GCM envelope, `key_id`, rotation);
  **MinIO/S3 objects** (client-side Tink before upload); `review_finding.message`/`suggestion` (may quote source).
- **Cleartext (queryable):** read-model status/counts/ids/branch names, severities, timeline summaries.
- **Never stored:** diffs/source (re-fetched by commit); secrets/tokens (secret store only).
