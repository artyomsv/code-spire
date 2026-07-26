# Attention panel — design

**Date:** 2026-07-27
**Status:** approved, not yet implemented

## Goal

A topbar bell in `spire-ui` whose every row is a condition that is **true right now**, derived on
demand from state each service already owns, linking to the page that fixes it.

The originating case: a webhook URL edit in GitLab blanked the shared token. Every delivery was
rejected with 401. The gateway logged a WARN, nothing surfaced, and the symptom — the bot stopped
responding — was indistinguishable from a working bot that chose not to reply.

## Non-goals

- **Not an incident inbox.** No stored notification rows, no dismiss button, no retention policy.
  Every row is a query result; fixing the cause removes the row.
- **Not per-review noise.** Turn cap reached and reply declined are per-review facts. The turn cap
  already posts a notice to its own thread; declined replies belong in the review detail view
  (tracked in `techdebt/global/`). Routing them into a global bell would drown the BLOCKING rows
  that mean nothing works at all.
- **Not true dead-tunnel detection.** Absence of inbound webhooks is indistinguishable from a quiet
  afternoon. `REVIEW_STUCK` is the honest proxy: derived from our own state, no heartbeat guesswork.
- **No scheduled probing.** Credential outcomes are recorded only from work already happening.

## Architecture

Two independent read endpoints returning the same shape. The UI concatenates them.

```
orchestrator :34080   GET /api/attention                 -> List<AttentionView>
gateway      :34081   GET /api/webhook-repos/attention   -> List<AttentionView>
```

Both paths are already proxied — `spire-ui/vite.config.ts:26` routes `/api/webhook-repos` to the
gateway and `/api` to the orchestrator, so neither `vite.config.ts` nor `docker-compose.dev.yml`
changes.

### Why not a message

Most of this catalog is **state, not events**. "No default LLM provider" is not something that
happens; it is something that is, and it stays true until someone changes it. State needs a query,
not a topic.

Consequences, all deliberate:

- No new Kafka topic, and no first non-`reviewId`-keyed message class. `cs.integration` stays
  purely about pull requests.
- No cross-schema write. Schema-per-service holds: each service answers only for state it owns.
- If the gateway is down, the orchestrator's feed still renders — and the gateway's absence becomes
  its own BLOCKING row.

The event-driven convention in `CLAUDE.md` governs the review pipeline, where components hand work
to each other. A read surface for the operator's own UI is not that kind of boundary, and the UI
already reads both services.

### Contract

New in `spire-contract` (Apache-2.0 tier; both services already depend on it):

```java
package dev.codespire.contract.attention;

public record AttentionView(String code, Severity severity, String subject,
                            String message, String action) {

    public enum Severity { BLOCKING, WARNING }
}
```

| Field | Meaning |
|---|---|
| `code` | Stable machine identifier from the catalog below. Never contains a provider name. |
| `severity` | `BLOCKING` = no review can complete. `WARNING` = some reviews affected. |
| `subject` | What the condition is about — a provider name, a repo target, or `null` for system-wide. |
| `message` | One sentence, operator-facing, stating the consequence. |
| `action` | A UI route (`/settings/llm`), or `null`. Not a label — the panel renders the link text. |

`Severity` is deliberately two-valued and deliberately **not** named after review-finding severities
(`BLOCKER`/`HIGH`/...), which are a different vocabulary about code defects.

`*View` per the DTO naming rule: read-only on every code path, narrower than the state it derives
from, never round-trips as a request body. It matches every sibling REST response in the repo
(`ProviderView`, `WebhookRepoView`, `LlmProviderView`).

## Condition catalog

### Orchestrator feed — `GET /api/attention`

| Code | Severity | Derived from | `subject` | `action` |
|---|---|---|---|---|
| `LLM_PROVIDER_MISSING` | BLOCKING | no row in `llm_provider` with `enabled = TRUE` | `null` | `/settings/llm` |
| `LLM_DEFAULT_MISSING` | BLOCKING | enabled rows exist, but none with `is_default = TRUE AND enabled = TRUE` | `null` | `/settings/llm` |
| `SCM_PROVIDER_MISSING` | BLOCKING | no row in `scm_provider` with `enabled = TRUE` | `null` | `/settings/providers` |
| `BOT_IDENTITY_UNRESOLVED` | WARNING | enabled `scm_provider` row where `bot_account_id` and `bot_username` are both null/blank | provider name | `/settings/providers` |
| `CREDENTIAL_REJECTED` | WARNING | enabled row in any of the three registries with `last_check_ok = FALSE` | provider name | `/settings/providers`, `/settings/llm` or `/settings/context` — whichever registry the row came from |
| `REVIEW_STUCK` | WARNING | count of `review_status` rows with non-terminal `status`, `pr_state = 'OPEN'`, and `updated_at < now() - stuckMinutes` | `null` | `/` |
| `REVIEW_FAILED` | WARNING | count of `review_status` rows with `status = 'FAILED'` and `updated_at > now() - failedWindowHours` | `null` | `/` |
| `DLQ_PENDING` | WARNING | pending dead-letter count > 0 | `null` | `/settings/dlq` |

`LLM_DEFAULT_MISSING` mirrors the real gate: `LlmProviderRegistry:164` resolves the default with
`WHERE is_default = TRUE AND enabled = TRUE`, so a default that has been disabled is as blocking as
no default at all.

Non-terminal status means `status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')` — the terminal values
of `ReviewState.Status`, which also has `IDLE` and `REVIEWING`. `IDLE` past the threshold is a real
signal: the webhook landed but no command was ever dispatched.

`pr_state = 'OPEN'` on the stuck query matters. Cancel-on-close should already have moved a closed
PR's review to `CANCELLED`; alerting about a review for a PR merged yesterday is how a panel becomes
wallpaper.

`REVIEW_FAILED` is windowed for the same reason. With no dismiss button anywhere in this design, a
failure from three weeks ago would nag forever. The window makes it self-clearing by time.

`REVIEW_STUCK` and `REVIEW_FAILED` each emit **one aggregate row carrying a count**, not one row per
review. A stalled broker produces dozens of stuck reviews at once, and thirty rows saying the same
thing is the failure mode this panel exists to avoid.

### Gateway feed — `GET /api/webhook-repos/attention`

| Code | Severity | Derived from | `subject` | `action` |
|---|---|---|---|---|
| `WEBHOOK_SECRET_MISSING` | WARNING | `webhook_repo` row with `enabled = TRUE` and null/blank `webhook_secret` | `target` | `/settings/webhooks` |
| `WEBHOOK_DELIVERIES_REJECTED` | WARNING | `webhook_repo` row with `rejection_count > 0` | `target` | `/settings/webhooks` |

### UI-synthesized

| Code | Severity | Derived from | `action` |
|---|---|---|---|
| `GATEWAY_UNREACHABLE` | BLOCKING | the gateway fetch threw or returned non-OK | `null` |

The gateway being unreachable means no webhook is being received at all, which is strictly blocking.
The orchestrator's own unreachability needs no row: the reviews list and the existing LIVE badge
already fail visibly.

### Provider neutrality

Every `code` is neutral. The *subject* of a condition comes from `scm_provider.name` /
`webhook_repo.target` — data read at runtime, never source text. So no orchestrator or gateway source
file gains a provider name, and the ADR-020 `spire-arch` check stays green by construction rather
than by allowlist.

### Explicitly rejected as a row: `CREDENTIAL_UNVERIFIED`

"Never checked" is not a problem, and a permanent row for every provider whose Check button was never
pressed is wallpaper by week two. Instead `lastCheckAt` / `lastCheckOk` / `lastCheckError` are added
to `ProviderView`, `LlmProviderView` and `ContextProviderView` and rendered inline on the settings
pages, beside the Check button that acts on them.

## Credential health

Three writers, all of them work that is already happening:

```
Check button   ─┐
Provider save  ─┼─→  last_check_at, last_check_ok, last_check_error
Review 401s    ─┘    on scm_provider / llm_provider / context_provider
```

### 1. Check button

`ProviderResource` (`/api/providers/{id}/check`) and `ContextProviderResource`
(`/api/context-providers/{id}/check`) already probe and return a `CheckResult`. They now also persist
its outcome. The check's own message is stored — it is already shown in the UI.

`LlmProviderResource` gains the missing sibling: `POST /api/llm-providers/{id}/check`, probing
`GET {baseUrl}/models`. That endpoint validates the key on any OpenAI-compatible provider and costs
nothing, unlike a completion. Without it, LLM credential health could only ever report config gaps.

### 2. Provider save

Saving an SCM provider already resolves bot identity from the token
(`ProviderIdentityResolver` → `IdentitySource.whoamiOrValidate`). That resolution is a credential
probe, so its outcome is recorded on the same write.

### 3. Pipeline-observed 401s

A real review that gets a 401 proves the token is dead better than any synthetic probe. Two small
pieces carry that signal:

**`ScmApiException.isUnauthorized()`** — a new defaulted method:

```java
/**
 * The provider rejected our credential outright — terminal until an operator rotates it.
 *
 * <p>Deliberately 401-only by default: at least one provider answers 403 for rate limiting
 * as well as for permission denial, so treating 403 as a dead credential would report a
 * throttled repo as a broken token. An adapter that can distinguish its own 403s overrides.
 */
default boolean isUnauthorized() {
    return status() == 401;
}
```

Same shape as the existing `isDiffTooLarge()`: neutral question on the interface, per-adapter answer.

**`ReviewFailed` gains `boolean credentialRejected`:**

```java
record ReviewFailed(String reviewId, String commit, String phase, String error,
                    boolean retryable, int attempt, boolean credentialRejected)
        implements IntegrationEvent {

    /** Pre-credential-signal call sites and in-flight records. */
    public ReviewFailed(String reviewId, String commit, String phase, String error,
                        boolean retryable, int attempt) {
        this(reviewId, commit, phase, error, retryable, attempt, false);
    }
}
```

This is a Kafka wire-format change to a sealed hierarchy, made backward-compatible by the defaulted
convenience constructor — the same pattern `AuthorReplied` and `AnswerFollowUp` already use. No new
`@JsonSubTypes` entry is needed; the subtype is already registered.

`DiffWorker`, `ReviewWorker` and `FollowUpWorker` each already classify `ScmApiException` to decide
`retryable`; each now sets `credentialRejected = e.isUnauthorized()` on the same decision.

`ResultSaga` handles it: resolve `reviewId` → provider through the existing `ReviewProviderResolver`
(which disambiguates by the review's stored `provider_type`), then record the outcome.

**This signal marks the SCM registry only.** `ScmApiException` is the only typed failure the pipeline
raises; `spire-llm` wraps LangChain4j, which throws untyped runtime exceptions, so an LLM 401 cannot
be recognised without inventing a typed exception in the LLM adapter. That is out of scope here, and
it is exactly why the new `POST /api/llm-providers/{id}/check` matters: it is the *only* way an LLM
credential is ever verified. Extending the pipeline signal to LLM failures is a later, separable
change.

**Pipeline-observed failures store a fixed string** — `"Authentication rejected (HTTP 401)"` — never
the provider's response body. A 401 body is a plausible place for a token to be echoed back, and the
never-log-secrets rule applies equally to what is persisted and rendered.

### Recovery

A subsequent successful check or successful identity resolution sets `last_check_ok = TRUE`, clearing
the row. There is no separate "clear" action.

## Webhook rejection tracking

`RegistryWebhookEdge` has five rejection paths, all WARN-only today:

| Line | Condition | Recorded reason |
|---|---|---|
| `:73` | unknown or disabled key | *(not recorded — no row to record against)* |
| `:78` | key registered for a different provider type | `provider_mismatch` |
| `:85` | missing or invalid signature | `bad_signature` |
| `:94` | authenticated but malformed payload | `malformed_payload` |
| `:111` | event repo outside the registration scope | `out_of_scope` |

An unknown key resolves to no row, so there is nothing to attach a counter to; it stays a WARN log.
That is a real gap and it is accepted — a wrong *key* means the URL itself is wrong, which the
operator discovers on the provider's own delivery page. A wrong *secret* against a right key is the
case that was invisible, and that one is now covered.

Reasons are a closed neutral set, never the raw exception message — a malformed-payload exception can
quote payload content.

**A successfully verified delivery clears the counter** (`rejection_count = 0`, reason and timestamp
nulled) after `publishAllAwait` succeeds. This is what makes the condition self-clearing rather than
a log entry wearing a condition's clothes: rotate the secret, next delivery lands, row disappears.

## Migrations

Orchestrator is at `V27`, gateway at `V1`.

**`spire-orchestrator/.../V28__provider_credential_check.sql`** — the same three columns on each of
the three registries:

```sql
ALTER TABLE scm_provider     ADD COLUMN last_check_at    TIMESTAMPTZ,
                             ADD COLUMN last_check_ok    BOOLEAN,
                             ADD COLUMN last_check_error TEXT;
ALTER TABLE llm_provider     ADD COLUMN last_check_at    TIMESTAMPTZ,
                             ADD COLUMN last_check_ok    BOOLEAN,
                             ADD COLUMN last_check_error TEXT;
ALTER TABLE context_provider ADD COLUMN last_check_at    TIMESTAMPTZ,
                             ADD COLUMN last_check_ok    BOOLEAN,
                             ADD COLUMN last_check_error TEXT;
```

All nullable with no backfill: existing rows are genuinely unchecked, and `NULL` says so honestly.
`last_check_ok` is deliberately three-valued (`NULL` = never checked, `TRUE` = passed, `FALSE` =
rejected) so "unchecked" can never be mistaken for "failing" — only `FALSE` raises a row.

**`spire-gateway/.../V2__webhook_repo_rejection.sql`:**

```sql
ALTER TABLE webhook_repo
    ADD COLUMN last_rejected_at      TIMESTAMPTZ,
    ADD COLUMN last_rejection_reason VARCHAR(32),
    ADD COLUMN rejection_count       INTEGER NOT NULL DEFAULT 0;
```

## UI

**`hooks/useAttention.ts`** — fetches both feeds every 30s, merges, sorts `BLOCKING` first then by
code for stability, and synthesizes `GATEWAY_UNREACHABLE` when its own gateway fetch fails. The
interval is a module constant, not an env var. Both fetches are independent: one failing never blanks
the other's rows.

**`components/AttentionBell.tsx`** — a bell button with a count badge, coloured by the highest
severity present, opening a popover listing subject, message and link per row. Zero conditions means
**no badge at all** — not a green tick, which would be a claim the panel cannot make (it only knows
about conditions it checks).

Mounted in the topbar between Register PR and the theme toggle (`App.tsx:183–199`). lucide-react
icons only, per the standing rule.

**Settings pages** gain a "last checked" line beside each Check button, from the new
`lastCheckAt` / `lastCheckOk` / `lastCheckError` view fields.

## Configuration

Two tuning knobs, both with defaults — permitted because neither is environment-specific nor a
credential:

| Env var | Property | Default |
|---|---|---|
| `SPIRE_ATTENTION_STUCK_MINUTES` | `spire.attention.stuck-minutes` | `15` |
| `SPIRE_ATTENTION_FAILED_WINDOW_HOURS` | `spire.attention.failed-window-hours` | `24` |

Both are added to `.env.example`, which is the contract.

## File map

**`spire-contract`** (Apache-2.0)
- Create `attention/AttentionView.java`
- Modify `scm/ScmApiException.java` — add `isUnauthorized()`
- Modify `event/IntegrationEvent.java` — `ReviewFailed.credentialRejected`

**`spire-orchestrator`** (FSL)
- Create `db/migration/V28__provider_credential_check.sql`
- Create `attention/AttentionResource.java` — `GET /api/attention`
- Create `attention/AttentionQueries.java` — the SQL, returning `List<AttentionView>`
- Modify `provider/ProviderRegistry.java`, `llm/LlmProviderRegistry.java`,
  `context/ContextProviderRegistry.java` — `recordCheck(id, ok, message)` + read the three columns
- Modify `provider/ProviderView.java`, `llm/LlmProviderView.java`,
  `context/ContextProviderView.java` — three new fields each
- Modify `provider/ProviderResource.java`, `context/ContextProviderResource.java` — persist outcomes
- Modify `llm/LlmProviderResource.java` — new `POST /{id}/check`
- Modify `pipeline/ResultSaga.java` — record on `ReviewFailed.credentialRejected`

**`spire-review-worker`** (FSL)
- Modify the `ScmApiException` classification sites to set `credentialRejected`

**`spire-gateway`** (FSL)
- Create `db/migration/V2__webhook_repo_rejection.sql`
- Create `attention/WebhookAttentionResource.java` — `GET /api/webhook-repos/attention`
- Modify `registry/WebhookRepoRegistry.java` — `recordRejection(key, reason)`, `clearRejections(key)`
- Modify `RegistryWebhookEdge.java` — record on four paths, clear after a successful publish

**`spire-ui`** (FSL)
- Create `hooks/useAttention.ts`, `components/AttentionBell.tsx`
- Modify `api.ts` (interface + two fetchers), `App.tsx` (mount), `index.css` (styles)
- Modify the three provider settings pages — "last checked" line

## Testing

**Backend**
- `AttentionQueriesTest` (Testcontainers Postgres) — one case per orchestrator condition, plus a
  clean-system case asserting an empty list. The clean-system case is the one that catches a
  condition that fires unconditionally.
- `AttentionResourceTest` — JSON shape and severity ordering.
- `ScmApiExceptionTest` — 401 is unauthorized; **403 is not**. This is the GitHub rate-limit trap
  and the reason the method exists rather than a raw status compare at each call site.
- `ResultSagaTest` — `ReviewFailed(credentialRejected = true)` marks the resolved provider;
  `false` leaves it untouched.
- `WebhookRepoRegistryTest` — record then clear; count increments across repeated rejections.
- `RegistryWebhookEdgeTest` — each of the four recordable paths writes its reason; a successful
  delivery clears a non-zero counter.
- `WebhookRepoResourceTest` — a guard case hitting both `/api/webhook-repos/{uuid}` and
  `/api/webhook-repos/attention` in the same class. JAX-RS resolves literal segments ahead of
  `{id}` templates, so `/attention` wins — but that is a spec guarantee few readers hold in mind,
  and a future refactor could silently reorder it into a UUID-parse 400.
- `LlmProviderResourceTest` — WireMock `GET /models`: 200 records success, 401 records rejection.

**UI (vitest)**
- badge count, and colour driven by the highest severity present
- no badge rendered when the merged list is empty
- `GATEWAY_UNREACHABLE` row appears when the gateway fetch rejects, and the orchestrator's rows
  still render alongside it
- each row's link points at its `action` route

**Build gate**
- `spire-arch` provider-neutrality check stays green — no new provider name in orchestrator or
  gateway source, and no new allowlist entry.

## Deferred

- `CREDENTIAL_UNVERIFIED` as a panel row — rejected above; lives inline on the settings pages.
- Unknown-webhook-key rejections — no row to attach a counter to.
- Scheduled credential probing — rejected in favour of observed failures only.
- A typed LLM exception, so that an LLM 401 during a review marks its provider the way an SCM 401
  does. Until then an LLM credential is verified only by its Check button.
- Dead-tunnel detection by delivery-history polling against each provider's API.
- Conversation-level facts (turn cap, declined reply) — per-review, tracked separately.
