# Finalize GitHub Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all 12 findings of the 2026-07-21 GitHub audit — rate-limit handling, `/review` wiring, draft-PR policy, graceful large-diff failure, OLD-side/multi-line anchoring, pagination honesty, summary-comment conversations, self-loop hardening, status-aware summary updates, GHE test coverage, and doc drift — so GitHub is the finished reference implementation for the GitLab/Bitbucket parity run.

**Architecture:** Point fixes along the existing event-driven pipeline; no new services or topics. The one wire change is an additive `topLevel` flag on `AuthorReplied` (old-arity convenience constructor, same pattern as the reconciliation fields). Rate-limit awareness lives in the adapter exception (`isRateLimited()` override + `retryAfterSeconds()`), so every existing `status() >= 500 || isRateLimited()` classifier upgrades for free.

**Tech Stack:** Java 25 / Quarkus 3.36 / Gradle Kotlin DSL; WireMock + hand-written-fake tests per module conventions; MicroProfile `@ConfigProperty`.

## Global Constraints

- Wire compatibility: every extended record gets an old-arity convenience constructor; old JSON without new fields must deserialize (nulls/defaults).
- Pure domain (`spire-contract`) stays framework-free; adapters throw their `ScmApiException` subtype.
- Retryable classification stays exactly `status() >= 500 || isRateLimited()` at every call site — the fix is making `isRateLimited()` truthful, not adding new call-site conditions.
- Config: MicroProfile `@ConfigProperty`; feature-flag defaults allowed (draft policy, throttle); never credentials.
- Exact config keys introduced: `spire.review.draft-prs` (gateway, default `false`), `spire.scm.inline-post-throttle-ms` (worker, default `500`), `spire.scm.rate-limit-retry-cap-seconds` (worker, default `120`).
- Idempotency keys and `anchorKey()` format (`path:line:side`) are frozen — no change to existing keys.
- Java: 4-space indent, explicit types, methods ≤30 lines, catch specific exceptions; no emoji anywhere.
- Commit messages: imperative, ≤72-char first line, NO AI/tool attribution.
- TDD per task; run each module's full suite before committing. JDK 25 resolves via user gradle.properties; Docker available.

---

## File structure (locked)

| Task | Files touched |
|---|---|
| 1 Rate-limit surface | `spire-contract/.../scm/ScmApiException.java`, `spire-scm-github/.../GitHubApiException.java`, `GitHubClient.java` |
| 2 Posting backoff | `spire-review-worker/.../pipeline/ReviewWorker.java` |
| 3 `/review` wiring | `spire-orchestrator/.../pipeline/IntegrationSaga.java`, `ReviewRerunService.java` |
| 4 Draft policy | `spire-scm-github/.../GitHubIngress.java`, `spire-gateway/.../GitHubWebhookResource.java`, `spire-gateway/src/main/resources/application.yml`, `.env.example` |
| 5 406 failure | `spire-review-worker/.../pipeline/DiffWorker.java` |
| 6 OLD-side + multi-line anchors | `spire-contract/.../scm/InlineAnchor.java`, `spire-diff/.../Anchors.java`, `spire-scm-github/.../GitHubCommentSink.java`, `spire-review-worker/.../pipeline/ReviewWorker.java` |
| 7 Pagination honesty + GHE fixture | `spire-scm-github/.../GitHubCommentSink.java`, tests |
| 8 Summary conversations | `spire-contract/.../event/IntegrationEvent.java`, `spire-scm-github/.../GitHubIngress.java`, `GitHubCommentSink.java`, `spire-orchestrator/.../pipeline/ConversationSaga.java`, `ReviewProjection.java`, `spire-review-worker/.../pipeline/FollowUpWorker.java` |
| 9 Conversation identity guard | `spire-orchestrator/.../pipeline/ConversationSaga.java` |
| 10 Status-aware summary update | `spire-review-worker/.../pipeline/ReviewWorker.java` |
| 11 Docs sweep + verification | `docs/SMOKE-TEST.md`, `docs/ROADMAP.md`, `CLAUDE.md` |

---

### Task 1: Truthful rate-limit surface (contract + GitHub client)

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/scm/ScmApiException.java`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubApiException.java`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubClient.java`
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubApiTest.java` (extend)

**Interfaces:**
- Consumes: existing `ScmApiException { int status(); default isNotFound(); default isRateLimited() }`; `GitHubClient.send` non-2xx throw site (`if (status / 100 != 2) throw new GitHubApiException(status, method, path, bodySnippet(...))`); `postGraphQl` errors → `GitHubApiException(200, ...)`.
- Produces (later tasks rely on):
  - `ScmApiException` gains `default Integer retryAfterSeconds() { return null; }` (seconds to wait; null = unknown).
  - `GitHubApiException` gains constructor `(int status, String method, String path, String detail, boolean rateLimited, Integer retryAfterSeconds)`; overrides `isRateLimited()` → `status == 429 || rateLimited`; overrides `retryAfterSeconds()`. Old constructors delegate with `(false, null)`.
  - `GitHubClient` detects rate limits at the throw site: status 429 always; status 403 when `Retry-After` header present OR `x-ratelimit-remaining` is `"0"` OR the body snippet contains `"rate limit"` (case-insensitive). GraphQL: an `errors[*].type == "RATE_LIMITED"` entry → `rateLimited=true`.

- [ ] **Step 1: Write the failing tests** (extend `GitHubApiTest` — WireMock pattern already in the file):

```java
    @Test
    void secondaryRateLimit403WithRetryAfterIsRateLimited() {
        wireMock.stubFor(get(urlEqualTo("/repos/ws/repo/pulls/1"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Retry-After", "37")
                        .withBody("You have exceeded a secondary rate limit.")));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> diffSource.fetchPullRequest(repo, 1L));
        assertTrue(e.isRateLimited());
        assertEquals(37, e.retryAfterSeconds());
    }

    @Test
    void rateLimit403WithZeroRemainingHeaderIsRateLimited() {
        wireMock.stubFor(get(urlEqualTo("/repos/ws/repo/pulls/1"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("x-ratelimit-remaining", "0")
                        .withBody("API rate limit exceeded")));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> diffSource.fetchPullRequest(repo, 1L));
        assertTrue(e.isRateLimited());
        assertNull(e.retryAfterSeconds());
    }

    @Test
    void permission403IsNotRateLimited() {
        wireMock.stubFor(get(urlEqualTo("/repos/ws/repo/pulls/1"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("Resource not accessible by integration")));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> diffSource.fetchPullRequest(repo, 1L));
        assertFalse(e.isRateLimited());
    }

    @Test
    void graphQlRateLimitedErrorIsRateLimited() {
        wireMock.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"API rate limit exceeded\"}]}")));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> new GitHubCommentSink(client).resolveThread(repo, 1L, new ThreadRef("42")));
        assertTrue(e.isRateLimited());
    }
```
(Adapt fixture names — `diffSource`/`client`/`repo` — to the test class's real fields; keep assertions.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-scm-github:test --tests "dev.codespire.scm.github.GitHubApiTest"`
Expected: COMPILATION FAILURE (`retryAfterSeconds` missing) or assertion failures.

- [ ] **Step 3: Implement**

`ScmApiException.java` — add:
```java
    /** Seconds the provider asked us to wait (Retry-After); null when unknown. */
    default Integer retryAfterSeconds() {
        return null;
    }
```

`GitHubApiException.java` — full replacement of the field/constructor section:
```java
    private final int status;
    private final boolean rateLimited;
    private final Integer retryAfterSeconds;

    public GitHubApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    public GitHubApiException(int status, String method, String path, String detail) {
        this(status, method, path, detail, false, null);
    }

    /** rateLimited marks GitHub's 403-shaped (secondary) and GraphQL rate limits explicitly. */
    public GitHubApiException(int status, String method, String path, String detail,
                              boolean rateLimited, Integer retryAfterSeconds) {
        super(message(status, method, path, detail));
        this.status = status;
        this.rateLimited = rateLimited;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public boolean isRateLimited() {
        return status == 429 || rateLimited;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
```
(Extract the existing message-building into a private static `message(...)` helper so all constructors share it.)

`GitHubClient.java` — replace the non-2xx throw in the 5-arg `send` (currently `if (status / 100 != 2) { throw new GitHubApiException(status, method, path, bodySnippet(response.body())); }`):
```java
            if (status / 100 != 2) {
                throw failure(status, method, path, response);
            }
```
and add:
```java
    /** GitHub signals rate limits mostly as 403 (+Retry-After / x-ratelimit-remaining: 0), not 429. */
    private static GitHubApiException failure(int status, String method, String path,
                                              HttpResponse<String> response) {
        String snippet = bodySnippet(response.body());
        Integer retryAfter = response.headers().firstValue("Retry-After")
                .map(GitHubClient::parseSecondsOrNull).orElse(null);
        boolean zeroRemaining = response.headers().firstValue("x-ratelimit-remaining")
                .map("0"::equals).orElse(false);
        boolean rateLimited = status == 429 || (status == 403 && (retryAfter != null || zeroRemaining
                || snippet.toLowerCase(java.util.Locale.ROOT).contains("rate limit")));
        return new GitHubApiException(status, method, path, snippet, rateLimited, retryAfter);
    }

    private static Integer parseSecondsOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
```
`postGraphQl` — replace the errors throw:
```java
            JsonNode errors = response.path("errors");
            if (!errors.isEmpty()) {
                boolean rateLimited = false;
                for (JsonNode error : errors) {
                    rateLimited |= "RATE_LIMITED".equals(error.path("type").asText(""));
                }
                throw new GitHubApiException(200, "POST", path, "GraphQL errors: " + errors,
                        rateLimited, null);
            }
```
(`bodySnippet` may need a null-safe guard if it doesn't have one — check; the 403 body flows through it.)

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :spire-scm-github:test :spire-contract:test`
Expected: PASS — including the pre-existing `rateLimitSurfacesAs429` test.

- [ ] **Step 5: Commit**

```bash
git add spire-contract spire-scm-github
git commit -m "Detect GitHub 403 and GraphQL rate limits as retryable

GitHub signals primary and secondary rate limits predominantly as
HTTP 403 with Retry-After or x-ratelimit-remaining: 0, and GraphQL
rate limits as 200 + errors[type=RATE_LIMITED]. isRateLimited() now
reflects all three shapes and retryAfterSeconds() carries the wait,
so every status>=500-or-rate-limited classifier upgrades for free."
```

---

### Task 2: Inline-posting throttle + rate-limit-aware retry (worker)

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ReviewWorkerTest.java` (extend)

**Interfaces:**
- Consumes: Task 1's `ScmApiException.isRateLimited()/retryAfterSeconds()`; existing `postOneInline` (claim → `commentSink.postInline` → markPosted, per-finding catch → `failed`); `renderSummary`'s failed heading `"\nCould not be posted inline (SCM error — will appear on retry):\n"`.
- Produces:
  - `@ConfigProperty(name = "spire.scm.inline-post-throttle-ms", defaultValue = "500") long inlinePostThrottleMs;` — fixed pacing sleep between successful inline posts (0 disables; tests set 0).
  - `@ConfigProperty(name = "spire.scm.rate-limit-retry-cap-seconds", defaultValue = "120") long rateLimitRetryCapSeconds;`
  - `postInlineWithBackoff(CommentSink, PostComments, Diff, InlineAnchor, String body)` — up to 3 attempts total; on `ScmApiException.isRateLimited()` sleeps `min(retryAfterSeconds ?? 2^attempt seconds, cap)` and retries; any other failure propagates immediately (per-finding isolation unchanged).
  - Summary failed-heading text becomes `"\nCould not be posted inline (SCM error):\n"` (the old text promised a retry that never happens).

- [ ] **Step 1: Failing tests** (ReviewWorkerTest style — hand-wired fakes; make the fake CommentSink scriptable to fail N times with a supplied exception, and record post timestamps/attempts):

```java
    @Test
    void rateLimited403RetriesWithBackoffAndSucceeds() {
        // fake sink: first postInline throws rateLimited GitHubApiException-like
        // ScmApiException (retryAfterSeconds=0 to keep the test fast), second succeeds.
        worker.postComments(postCommand(...oneAnchoredFinding...));
        assertEquals(2, fakeSink.inlineAttempts);
        assertEquals(1, fakeSink.inlinePosts.size());
        assertTrue(lastCommentsPosted().inline().size() == 1, "finding posted after backoff");
    }

    @Test
    void nonRateLimitFailureStillFailsFastPerFinding() {
        // fake sink: postInline always throws a plain RuntimeException
        worker.postComments(postCommand(...oneAnchoredFinding...));
        assertEquals(1, fakeSink.inlineAttempts, "no retry for non-rate-limit errors");
        // finding lands in the summary's failed list (assert summary body contains the new heading)
        assertTrue(fakeSink.summaryBodies.getFirst().contains("Could not be posted inline (SCM error):"));
        assertFalse(fakeSink.summaryBodies.getFirst().contains("will appear on retry"));
    }

    @Test
    void rateLimitExhaustionLandsInFailedList() {
        // fake sink: always throws rateLimited (retryAfterSeconds=0)
        worker.postComments(postCommand(...oneAnchoredFinding...));
        assertEquals(3, fakeSink.inlineAttempts, "3 bounded attempts");
        assertTrue(fakeSink.summaryBodies.getFirst().contains("Could not be posted inline"));
    }
```
Use a small test-only `ScmApiException` implementation (anonymous or the existing GitHub one via test dependency — the worker module already depends on contract; prefer a local `record TestScmException(int status, boolean limited, Integer retryAfter)`-style RuntimeException implementing ScmApiException). Set `worker.inlinePostThrottleMs = 0` and `worker.rateLimitRetryCapSeconds = 0` in the test wiring (field injection like the other config fields in this test class).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-review-worker:test --tests "dev.codespire.worker.pipeline.ReviewWorkerTest"`
Expected: FAIL / compile failure.

- [ ] **Step 3: Implement**

Add the two `@ConfigProperty` fields next to the worker's existing config fields. In `postOneInline`, replace the direct `commentSink.postInline(...)` call with `postInlineWithBackoff(...)`, and after a successful post add the pacing sleep:
```java
    private static final int MAX_INLINE_ATTEMPTS = 3;

    /**
     * GitHub secondary rate limits hit bursty comment creation; honor Retry-After with a
     * bounded in-loop retry instead of dropping the finding. Non-rate-limit failures keep
     * the existing fail-fast per-finding isolation.
     */
    private CommentRef postInlineWithBackoff(CommentSink sink, PostComments command,
                                             Diff diff, InlineAnchor anchor, String body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return sink.postInline(command.repo(), command.prId(), diff.refs(), anchor, body);
            } catch (RuntimeException e) {
                Throwable cause = unwrap(e);
                if (attempt >= MAX_INLINE_ATTEMPTS || !(cause instanceof ScmApiException api)
                        || !api.isRateLimited()) {
                    throw e;
                }
                long waitSeconds = api.retryAfterSeconds() != null
                        ? api.retryAfterSeconds() : (1L << attempt);
                sleepSeconds(Math.min(waitSeconds, rateLimitRetryCapSeconds));
                LOG.infof("Rate limited posting inline (%s) — retry %d/%d",
                        api.status(), attempt + 1, MAX_INLINE_ATTEMPTS);
            }
        }
    }

    private void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during rate-limit backoff", e);
        }
    }
```
Pacing after a successful post (inside the `Claim.Post` success branch of `postOneInline`):
```java
                if (inlinePostThrottleMs > 0) {
                    try {
                        Thread.sleep(inlinePostThrottleMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
```
`renderSummary`: change the failed heading string to `"\nCould not be posted inline (SCM error):\n"`.
Set the two config fields' test defaults in the ReviewWorkerTest wiring (0/0) so existing tests stay instant.

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :spire-review-worker:test`
Expected: PASS (all suites; existing tests unaffected because throttle=0 in the fakes).

- [ ] **Step 5: Commit**

```bash
git add spire-review-worker
git commit -m "Throttle inline posting and retry through rate limits

A configurable pacing delay between inline posts avoids GitHub's
secondary rate limit on comment bursts; a bounded Retry-After-aware
backoff recovers when one hits anyway. The summary's failed-findings
heading no longer promises a retry that never happens."
```

---

### Task 3: Wire the `/review` command

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/IntegrationSagaPolicyTest.java` (extend)

**Interfaces:**
- Consumes: `ReviewRerunService.rerun(String workspace, String slug, long pr)` (already: clears worker idempotency, `RequestReview(commit, "rerun", force=true)`, emits FetchDiff, returns false when lifecycle refuses; throws `jakarta.ws.rs.NotFoundException` when the review or credential is unknown). `ManualCommandReceived e` carries `repo`, `prId`, `command`, `args`, `author`.
- Produces: the `ManualCommandReceived` case dispatches `"review"` to `rerunService.rerun(...)`; unknown PR → timeline note `skipped:/review` + appendEvent; other commands keep the existing log line. `IntegrationSaga` gains `@Inject ReviewRerunService rerunService;`.

- [ ] **Step 1: Failing tests** (extend `IntegrationSagaPolicyTest` — its `sagaWith(...)` fakes pattern; add a recording fake for `ReviewRerunService` — it's a plain class, subclass it with no-arg field injection like the other fakes in this file, or introduce the minimal seam the file's conventions allow):

```java
    @Test
    void reviewCommandTriggersARerun() {
        // saga with a recording ReviewRerunService fake; emit ManualCommandReceived("review", humanAuthor)
        // assert rerun called once with (workspace, slug, prId); no SelfLoopDropped.
    }

    @Test
    void reviewCommandOnUnknownPrIsSkippedNotFatal() {
        // rerun fake throws jakarta.ws.rs.NotFoundException
        // assert: no exception propagates; timeline records "skipped:/review".
    }

    @Test
    void botAuthoredReviewCommandStaysDropped() {
        // author id == provider botAccountId → SelfLoopDropped, rerun NOT called (existing guard intact).
    }
```
Write these as real tests in the file's established style (the ManualCommandReceived fixtures already exist at the bot-authored tests — reuse their builders).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-orchestrator:test --tests "*.IntegrationSagaPolicyTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** — replace the `ManualCommandReceived` case body:

```java
            case ManualCommandReceived e -> {
                if (isBotAuthored(e.repo().workspace(), e.author())) {
                    dropSelfLoop(reviewIdOf(e), "/" + e.command());
                } else if ("review".equals(e.command())) {
                    triggerManualReview(e);
                } else {
                    LOG.infof("Manual /%s command received — no handler", e.command());
                }
            }
```
```java
    /** A /review PR comment forces a re-review of the PR's last-known commit (FR-12). */
    private void triggerManualReview(ManualCommandReceived e) {
        String reviewId = reviewIdOf(e);
        try {
            boolean started = rerunService.rerun(e.repo().workspace(), e.repo().slug(), e.prId());
            projection.appendEvent(reviewId, "integration", "ManualReview",
                    started ? "/review by @" + e.author().username() : "/review refused (already running)");
        } catch (jakarta.ws.rs.NotFoundException unknown) {
            timeline.record("integration", "skipped:/review", reviewId,
                    "no registered review for this PR — open/update it first");
        }
    }
```
Add `@Inject ReviewRerunService rerunService;` beside the other injections. If `ReviewRerunService` needs a test seam (final/CDI-proxied), keep it a plain injectable class and let the test subclass it — match how the file fakes `ConversationSaga`.

- [ ] **Step 4: Run the suite**

Run: `./gradlew :spire-orchestrator:test --tests "*.IntegrationSagaPolicyTest"`
Expected: PASS. Then `./gradlew :spire-orchestrator:compileJava` clean.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator
git commit -m "Wire the /review command to a forced re-run

The webhook edge has translated /review PR comments since the GitHub
ingress landed, but the orchestrator only logged them. Route them to
ReviewRerunService (idempotency cleared, RequestReview force=true);
an unknown PR records a skipped note instead of failing the consumer."
```

---

### Task 4: Draft-PR policy (skip drafts, review on ready_for_review)

**Files:**
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubIngress.java`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/GitHubWebhookResource.java`
- Modify: `spire-gateway/src/main/resources/application.yml` (add `spire.review.draft-prs: false` default)
- Modify: `.env.example` (document `SPIRE_REVIEW_DRAFT_PRS=false`)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubIngressTest.java` (extend)

**Interfaces:**
- Consumes: `GitHubIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands)` constructor; `pullRequest(...)` switch over `opened/reopened/synchronize/closed`; the webhook JSON's `pull_request.draft` boolean; test fixture builder `pr(action, merged)`.
- Produces:
  - `GitHubIngress` constructor gains a 4th param `boolean reviewDrafts` (3-arg convenience constructor delegates with `false` so existing tests/uses compile).
  - Behavior with `reviewDrafts=false` (default): `opened`/`reopened`/`synchronize` on a PR with `"draft": true` → no events (dropped, logged debug); new action `ready_for_review` → `prEvent(..., OPENED)`. `closed` unaffected (cancel still flows for drafts).
  - Behavior with `reviewDrafts=true`: exactly today's (drafts reviewed; `ready_for_review` ignored — the PR was already reviewed on open).
  - `GitHubWebhookResource` reads `@ConfigProperty(name = "spire.review.draft-prs", defaultValue = "false") boolean reviewDrafts;` and passes it into the factory lambda.

- [ ] **Step 1: Failing tests** (extend `GitHubIngressTest`; add a `pr(action, merged, draft)` fixture variant that sets `"draft": true|false` in the pull_request JSON — copy the existing `pr(...)` builder):

```java
    @Test
    void draftPrOpenedIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(pr("opened", false, true), "pull_request")).isEmpty());
    }

    @Test
    void draftPrSynchronizeIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(pr("synchronize", false, true), "pull_request")).isEmpty());
    }

    @Test
    void readyForReviewTriggersAnOpenedEvent() {
        List<IntegrationEvent> events = ingress.translate(webhook(pr("ready_for_review", false, false), "pull_request"));
        assertEquals(1, events.size());
        assertEquals(IntegrationEvent.PrAction.OPENED,
                ((IntegrationEvent.PullRequestEventReceived) events.getFirst()).action());
    }

    @Test
    void draftClosedStillCancels() {
        List<IntegrationEvent> events = ingress.translate(webhook(pr("closed", false, true), "pull_request"));
        assertEquals(1, events.size());
        assertInstanceOf(IntegrationEvent.PullRequestClosed.class, events.getFirst());
    }

    @Test
    void reviewDraftsTrueRestoresTodaysBehavior() {
        GitHubIngress permissive = new GitHubIngress(SECRET, new ObjectMapper(), Set.of("review"), true);
        assertEquals(1, permissive.translate(webhook(pr("opened", false, true), "pull_request")).size());
        assertTrue(permissive.translate(webhook(pr("ready_for_review", false, false), "pull_request")).isEmpty());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-scm-github:test --tests "*.GitHubIngressTest"`
Expected: FAIL / compile failure.

- [ ] **Step 3: Implement**

`GitHubIngress`: add `private final boolean reviewDrafts;`, the 4-arg constructor, and a 3-arg convenience delegating `reviewDrafts=false`. In `pullRequest(...)`:
```java
        boolean draft = pr.path("draft").asBoolean(false);
        return switch (action) {
            case "opened", "reopened" -> draft && !reviewDrafts
                    ? List.of() : List.of(prEvent(repo, prNumber, pr, PrAction.OPENED));
            case "synchronize" -> draft && !reviewDrafts
                    ? List.of() : List.of(prEvent(repo, prNumber, pr, PrAction.UPDATED));
            case "ready_for_review" -> reviewDrafts
                    ? List.<IntegrationEvent>of() : List.of(prEvent(repo, prNumber, pr, PrAction.OPENED));
            case "closed" -> List.of(new PullRequestClosed(repo, prNumber,
                    pr.path("merged").asBoolean(false) ? CloseReason.MERGED : CloseReason.DECLINED));
            default -> List.of();
        };
```
(Adapt to the method's real local variable names/structure; the semantics above are the requirement.)

`GitHubWebhookResource`:
```java
    @ConfigProperty(name = "spire.review.draft-prs", defaultValue = "false")
    boolean reviewDrafts;
```
and the factory becomes `secret -> new GitHubIngress(secret, mapper, COMMANDS, reviewDrafts);`.

`application.yml` (gateway): add under the existing `spire:` tree `review.draft-prs: false` with a one-line comment. `.env.example`: add `SPIRE_REVIEW_DRAFT_PRS=false  # true = review draft PRs immediately (default waits for ready_for_review)`.

- [ ] **Step 4: Run suites**

Run: `./gradlew :spire-scm-github:test :spire-gateway:test`
Expected: PASS (gateway webhook tests unaffected — default false only changes draft fixtures, which don't exist there; if a gateway test posts a draft fixture, update it per the new default and note it).

- [ ] **Step 5: Commit**

```bash
git add spire-scm-github spire-gateway .env.example
git commit -m "Skip draft PRs and review on ready_for_review

Draft PRs no longer trigger paid reviews on open or push; the review
runs when the author marks the PR ready. SPIRE_REVIEW_DRAFT_PRS=true
restores the old always-review behavior. Closing a draft still
cancels normally."
```

---

### Task 5: Honest large-diff (406) failure

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/DiffWorker.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/DiffWorkerTest.java` (extend)

**Interfaces:**
- Consumes: `DiffWorker.fail(FetchDiff, RuntimeException)` (classifies `status()>=500 || isRateLimited()`); the fake `DiffSource` failure toggle in `DiffWorkerTest`.
- Produces: a 406 from the diff fetch emits `ReviewFailed(..., "fetch-diff", FRIENDLY_406, retryable=false, 1)` where `FRIENDLY_406 = "PR diff exceeds the provider's diff-generation limit — the PR is too large to review as one unit; split it or exclude generated files"`. All other classifications unchanged.

- [ ] **Step 1: Failing test** (DiffWorkerTest style):

```java
    @Test
    void oversizedDiff406FailsTerminallyWithAnActionableMessage() {
        worker.scm = scmFailingWith(new GitHubApiException(406, "GET", "/repos/ws/repo/pulls/1"));
        worker.on(COMMAND);
        ReviewFailed failed = (ReviewFailed) results.events.getFirst();
        assertFalse(failed.retryable());
        assertTrue(failed.error().contains("too large to review"));
    }
```
(Match the file's real fake-wiring helpers; `scmFailingWith` = however the 503/403 tests inject a failure.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-review-worker:test --tests "*.DiffWorkerTest"`
Expected: FAIL (today's message is the raw HTTP text).

- [ ] **Step 3: Implement** — in `fail(...)`, before the generic classification:

```java
        if (e instanceof ScmApiException api && api.status() == 406) {
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "fetch-diff",
                    "PR diff exceeds the provider's diff-generation limit — the PR is too large "
                            + "to review as one unit; split it or exclude generated files",
                    false, 1));
            return;
        }
```

- [ ] **Step 4: Run suite**

Run: `./gradlew :spire-review-worker:test --tests "*.DiffWorkerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-review-worker
git commit -m "Fail oversized-diff 406 with an actionable message"
```

---

### Task 6: OLD-side and multi-line inline anchors

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/scm/InlineAnchor.java`
- Modify: `spire-diff/src/main/java/dev/codespire/diff/Anchors.java`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCommentSink.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java` (call site)
- Test: `spire-diff/src/test/java/dev/codespire/diff/AnchorsTest.java` (extend or create beside existing diff tests), `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubReconciliationTest.java` or `GitHubApiTest.java` (range-post test)

**Interfaces:**
- Consumes: `InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine, Side side)` with `anchorKey()`; `Anchors.resolveNewLine(patches, path, newLine)`; `DiffLine(LineType type, Integer oldLine, Integer newLine, String content)`; `Finding.range()` (`LineRange(startLine, endLine)`); GitHub `postInline` body map.
- Produces:
  - `InlineAnchor` gains a 6th component `Integer endNewLine` (null = single-line). 5-arg convenience constructor delegates with `null`. **`anchorKey()` is unchanged** (frozen format).
  - `Anchors.resolveLine(List<FilePatch> patches, String path, int startLine, int endLine)` — resolves the NEW side first (existing semantics); when `endLine > startLine` and the end line also resolves on the NEW side **within the same hunk**, sets `endNewLine`; when the NEW side has no match, falls back to a REMOVED line with `oldLine == startLine` → `InlineAnchor(path, srcPath, startLine, null, Side.OLD, null)` (OLD-side anchors stay single-line). `resolveNewLine` stays as-is, delegating: `resolveLine(patches, path, newLine, newLine)`.
  - GitHub `postInline`: when `anchor.endNewLine() != null && anchor.side() == Side.NEW && anchor.endNewLine() > anchor.newLine()` → body adds `"start_line": anchor.newLine(), "start_side": "RIGHT"` and sets `"line": anchor.endNewLine()` (GitHub ranges anchor at the LAST line). Single-line and OLD-side bodies unchanged.
  - `ReviewWorker.postOneInline` resolves via `Anchors.resolveLine(diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine())`.

- [ ] **Step 1: Failing tests**

`AnchorsTest` (create if absent, plain JUnit against hand-built `FilePatch`/`Hunk`/`DiffLine` fixtures — check `UnifiedDiffParser` tests for fixture style):
```java
    @Test
    void resolvesARemovedLineOnTheOldSide() {
        // patch: file a.java, hunk containing DiffLine(REMOVED, oldLine=7, newLine=null)
        Optional<InlineAnchor> anchor = Anchors.resolveLine(patches, "a.java", 7, 7);
        assertTrue(anchor.isPresent());
        assertEquals(Side.OLD, anchor.get().side());
        assertEquals(7, anchor.get().oldLine());
        assertNull(anchor.get().endNewLine());
    }

    @Test
    void resolvesAMultiLineRangeWithinOneHunk() {
        // patch: ADDED lines newLine=5..8 in one hunk
        InlineAnchor anchor = Anchors.resolveLine(patches, "a.java", 5, 7).orElseThrow();
        assertEquals(5, anchor.newLine());
        assertEquals(7, anchor.endNewLine());
        assertEquals("a.java:5:NEW", anchor.anchorKey());
    }

    @Test
    void rangeEndOutsideTheHunkDegradesToSingleLine() {
        InlineAnchor anchor = Anchors.resolveLine(patches, "a.java", 5, 99).orElseThrow();
        assertNull(anchor.endNewLine());
    }

    @Test
    void newSideStillWinsOverOldSide() {
        // a line number present on BOTH sides (context/modified) must anchor NEW, as today
        assertEquals(Side.NEW, Anchors.resolveLine(patches, "a.java", 3, 3).orElseThrow().side());
    }
```
GitHub range-post test (WireMock, in the file that already stubs `POST /repos/ws/repo/pulls/1/comments`):
```java
    @Test
    void multiLineAnchorPostsAGitHubRangeComment() {
        // stub the review-comments POST; call postInline with
        // new InlineAnchor("a.java", "a.java", null, 5, Side.NEW, 8)
        // assert request body: start_line=5, start_side=RIGHT, line=8, side=RIGHT
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-diff:test :spire-scm-github:test`
Expected: COMPILATION FAILURE (`endNewLine`, `resolveLine` missing).

- [ ] **Step 3: Implement**

`InlineAnchor`:
```java
public record InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine,
                           Side side, Integer endNewLine) {

    public InlineAnchor(String path, String srcPath, Integer oldLine, Integer newLine, Side side) {
        this(path, srcPath, oldLine, newLine, side, null);
    }

    /** Stable idempotency key — frozen format, deliberately ignores endNewLine. */
    public String anchorKey() {
        return path + ":" + (side == Side.OLD ? oldLine : newLine) + ":" + side;
    }
}
```
`Anchors` — keep `resolveNewLine` delegating; add:
```java
    /**
     * NEW-side resolution wins (a finding on changed code anchors on the right side);
     * a pure deletion — the line exists only as REMOVED — anchors on the OLD side.
     * A NEW-side range extends to endLine only when the end resolves in the SAME hunk;
     * otherwise the anchor degrades to single-line. OLD-side anchors are single-line.
     */
    public static Optional<InlineAnchor> resolveLine(List<FilePatch> patches, String path,
                                                     int startLine, int endLine) {
        for (FilePatch patch : patches) {
            if (!path.equals(patch.newPath())) {
                continue;
            }
            String srcPath = patch.oldPath() != null ? patch.oldPath() : patch.newPath();
            for (Hunk hunk : patch.hunks()) {
                Optional<InlineAnchor> anchor = resolveInHunk(hunk, path, srcPath, startLine, endLine);
                if (anchor.isPresent()) {
                    return anchor;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<InlineAnchor> resolveInHunk(Hunk hunk, String path, String srcPath,
                                                        int startLine, int endLine) {
        DiffLine start = null;
        boolean endInHunk = false;
        DiffLine removedMatch = null;
        for (DiffLine line : hunk.lines()) {
            if (line.newLine() != null && line.newLine() == startLine) {
                start = line;
            }
            if (line.newLine() != null && line.newLine() == endLine) {
                endInHunk = true;
            }
            if (line.type() == LineType.REMOVED && line.oldLine() != null && line.oldLine() == startLine) {
                removedMatch = line;
            }
        }
        if (start != null) {
            Integer end = endLine > startLine && endInHunk ? endLine : null;
            return Optional.of(new InlineAnchor(path, srcPath, start.oldLine(), startLine, Side.NEW, end));
        }
        if (removedMatch != null) {
            return Optional.of(new InlineAnchor(path, srcPath, startLine, null, Side.OLD, null));
        }
        return Optional.empty();
    }
```
(Check the real `LineType` enum constant name for removals — `REMOVED`/`DELETED` — and use it.)

`GitHubCommentSink.postInline` — build the map conditionally:
```java
        boolean old = anchor.side() == Side.OLD;
        int line = old ? anchor.oldLine() : anchor.newLine();
        Map<String, Object> body = new HashMap<>(Map.of(
                "body", bodyMd,
                "commit_id", refs.headSha(),
                "path", anchor.path(),
                "side", old ? "LEFT" : "RIGHT"));
        if (!old && anchor.endNewLine() != null && anchor.endNewLine() > anchor.newLine()) {
            body.put("start_line", anchor.newLine());
            body.put("start_side", "RIGHT");
            body.put("line", anchor.endNewLine());
        } else {
            body.put("line", line);
        }
        JsonNode created = client.postJson(reviewCommentsPath(repo, prId), body);
```
`ReviewWorker.postOneInline`: switch the resolution call to `Anchors.resolveLine(diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine())`.

- [ ] **Step 4: Run the suites**

Run: `./gradlew :spire-diff:test :spire-scm-github:test :spire-review-worker:test :spire-scm-gitlab:test :spire-scm-bitbucket:test`
Expected: PASS — GitLab/Bitbucket sinks consume `InlineAnchor` positionally-safely (they read named accessors; the convenience ctor keeps their tests compiling). If a GitLab/Bitbucket test constructs a 5-arg `InlineAnchor`, it hits the convenience ctor unchanged.

- [ ] **Step 5: Commit**

```bash
git add spire-contract spire-diff spire-scm-github spire-review-worker
git commit -m "Anchor deleted-line findings and post multi-line ranges

Findings on purely deleted lines now anchor on the OLD side instead
of folding into the summary; NEW-side findings spanning several lines
post as GitHub range comments (start_line..line) when the whole range
sits in one hunk. anchorKey stays frozen at the start line."
```

---

### Task 7: Pagination honesty + GHE GraphQL fixture

**Files:**
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCommentSink.java`
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubReconciliationTest.java` + `GitHubThreadFetchTest.java` (extend)

**Interfaces:**
- Consumes: `resolveThread` cursor loop (fallthrough `return ALREADY_RESOLVED`), `fetchThread` page loop (`break when count<100`), `botLogin()` catch-return-"".
- Produces:
  - `resolveThread`: when the pagination cap is exhausted while `hasNextPage` was still true → `LOG.warnf` + `return ThreadResolution.UNSUPPORTED` (degrades to reply-only, honest). A completed walk with no match keeps returning `ALREADY_RESOLVED` (comment deleted — nothing to resolve).
  - `fetchThread`: when the page cap is hit with a full page (`count == 100` on page `MAX_THREAD_PAGES`) → `LOG.warnf("thread %s transcript may be truncated after %d pages", ...)` (behavior unchanged).
  - `botLogin()`: failure logs `LOG.warnf` (still returns `""`).
  - GHE fixture: a `GitHubClient` built with base URL `wireMock.baseUrl() + "/api/v3"` must send GraphQL to `/api/graphql`.

- [ ] **Step 1: Failing tests**

```java
    @Test
    void gheBaseUrlRoutesGraphQlToApiGraphql() {
        GitHubClient ghe = new GitHubClient(
                new GitHubConfig(wireMock.baseUrl() + "/api/v3", "t", "unused"), new ObjectMapper());
        wireMock.stubFor(post(urlEqualTo("/api/graphql"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                                  "pageInfo":{"hasNextPage":false,"endCursor":null},
                                  "nodes":[]}}}}}""")));
        assertEquals(CommentSink.ThreadResolution.ALREADY_RESOLVED,
                new GitHubCommentSink(ghe).resolveThread(repo, 1L, new ThreadRef("42")));
        assertFalse(wireMock.findAll(postRequestedFor(urlEqualTo("/api/graphql"))).isEmpty());
    }

    @Test
    void resolveExhaustedPaginationReportsUnsupportedNotResolved() {
        // stub /graphql: every page returns 100-node pages with hasNextPage=true (a small
        // generator building the JSON with distinct databaseIds, none matching), endCursor "c".
        assertEquals(CommentSink.ThreadResolution.UNSUPPORTED,
                new GitHubCommentSink(client).resolveThread(repo, 1L, new ThreadRef("999999")));
    }
```
(For the exhaustion test, a WireMock scenario or a single stub returning `hasNextPage: true` with a constant cursor is enough — the loop hits the cap.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-scm-github:test --tests "*GitHubReconciliationTest*"`
Expected: exhaustion test FAILS (currently ALREADY_RESOLVED). GHE test may already pass (path logic exists) — keep it as the missing regression fixture either way.

- [ ] **Step 3: Implement** — in `resolveThread`, track exhaustion:

```java
        String cursor = null;
        for (int page = 0; page < MAX_THREAD_PAGES; page++) {
            JsonNode threads = ...;               // existing per-page call via resolveInPage
            ThreadResolution result = resolveInPage(...);
            if (result != null) {
                return result;
            }
            if (!threads.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                return ThreadResolution.ALREADY_RESOLVED;   // walked everything; comment is gone
            }
            cursor = threads.path("pageInfo").path("endCursor").asText(null);
        }
        LOG.warnf("resolveThread %s: pagination cap (%d pages) exhausted — degrading to reply-only",
                thread.value(), MAX_THREAD_PAGES);
        return ThreadResolution.UNSUPPORTED;
```
(Restructure the existing loop minimally to that shape; add the `fetchThread` truncation warn at the loop end and the `botLogin` warn in its catch.)

- [ ] **Step 4: Run suite**

Run: `./gradlew :spire-scm-github:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-github
git commit -m "Report resolve pagination exhaustion honestly

A thread not found because the 2000-thread pagination cap ran out now
degrades to reply-only (UNSUPPORTED) instead of claiming the thread
was already resolved; transcript truncation and bot-login failures
log warnings. Adds the missing GHE /api/graphql routing fixture."
```

---

### Task 8: Summary-comment conversations

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java` (`AuthorReplied` + `topLevel`)
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubIngress.java` (`issueComment` non-command branch)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ConversationSaga.java` (summary-thread routing)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (`summaryRefOf`)
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubCommentSink.java` (`fetchThread` issue-comment fallback, `replyInThread` fallback)
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/FollowUpWorker.java` (null-commit → PR head)
- Tests: `GitHubIngressReplyTest`/`GitHubIngressTest`, `ConversationSagaTest`-adjacent saga test, `GitHubThreadFetchTest`, `FollowUpWorkerTest`, `ReconciliationTypesTest` (wire compat)

**Interfaces:**
- Consumes: `AuthorReplied(repo, prId, reviewId, threadRef, commentId, text, author)`; `ConversationSaga.planFollowUp` (policy decide → `AnswerFollowUp`); `review_status.last_summary_comment_id` (V19); `fetchThread` review-comment walk; `FollowUpWorker.answer` static (`diffs.fetchDiff(repo, prId, transcript.commit())`).
- Produces:
  - `AuthorReplied` gains `boolean topLevel` (8th component; 7-arg convenience ctor delegates `false`). `topLevel=true` = a plain (non-command) PR issue comment.
  - `GitHubIngress.issueComment`: bodies NOT starting with `/` (action `created`, on a PR) → `AuthorReplied(..., new ThreadRef(commentId), commentId, body.trim(), author, true)`. Command bodies keep today's path. Non-command bodies starting with `/` but unknown commands stay dropped (today's behavior).
  - `ReviewProjection.summaryRefOf(String reviewId)` → `Optional<String>` reading `last_summary_comment_id`.
  - `ConversationSaga.planFollowUp`: for `e.topLevel()`, resolve `summaryRefOf(reviewId)` (empty → timeline `skipped:AnswerFollowUp` note "no summary to converse on" → empty); treat `threadIsOurs = true`; use the SUMMARY ref as the command's `threadRef` (turn counting via `threads.turnCount(reviewId, summaryThread)`); `triggeringCommentId` stays the author's comment id. Inject `ReviewProjection projection`.
  - `GitHubCommentSink.fetchThread`: if the review-comment walk finds NO root match, fall back to the issue-comment walk: `GET /repos/{full}/issues/{pr}/comments?per_page=100&page=N` (same page cap); root = comment with `id == thread.value()`; messages = root + every later comment (list order); anchor fields `path=null, line=0`, `commit=null`. Returns the normal `ThreadTranscript`.
  - `GitHubCommentSink.replyInThread`: on a `GitHubApiException.isNotFound()` from the review-comment replies POST, fall back to posting an ISSUE comment (`issueCommentsPath`) — the "reply" to a summary thread is the next top-level comment. Return its ref with `CommentKind.REPLY`.
  - `FollowUpWorker` static `answer(...)`: when `transcript.commit() == null`, fetch the PR head first: `String commit = transcript.commit() != null ? transcript.commit() : diffs.fetchPullRequest(repo, prId).diffRefs().headSha();` then fetch the diff by that commit.

- [ ] **Step 1: Failing tests** (write all five, then implement bottom-up):

1. Wire compat (`ReconciliationTypesTest` or a sibling contract test): 8-arg `AuthorReplied` round-trips; legacy JSON without `topLevel` deserializes `false`; 7-arg ctor defaults `false`.
2. `GitHubIngressTest`: `plainPrCommentBecomesTopLevelAuthorReplied` — `issueComment("looks wrong to me", true)` fixture (non-`/` body) → one `AuthorReplied` with `topLevel() == true`, threadRef == comment id; `unknownSlashCommandStaysDropped` unchanged-behavior assertion.
3. Saga test (IntegrationSagaPolicyTest style, or a focused ConversationSaga test with hand fakes): topLevel reply + `summaryRefOf` returning `"sum-1"` → emitted `AnswerFollowUp.threadRef().value() == "sum-1"`; `summaryRefOf` empty → no command, timeline `skipped:AnswerFollowUp`.
4. `GitHubThreadFetchTest`: root id not among review comments + stubbed `/issues/1/comments` page containing the root and two later comments → transcript with 3 messages, `commit() == null`.
5. `FollowUpWorkerTest`: transcript with null commit → `fetchPullRequest` consulted for the head sha, diff fetched by it, reply posted (extend the existing fake wiring).

- [ ] **Step 2: Run to verify failures** per module:

`./gradlew :spire-contract:test :spire-scm-github:test :spire-orchestrator:test --tests "*IntegrationSagaPolicyTest*" :spire-review-worker:test --tests "*FollowUpWorkerTest*"` → FAIL/compile errors.

- [ ] **Step 3: Implement** in this order (each keeps the module compiling):

a) Contract — `AuthorReplied`:
```java
    /** topLevel = a plain PR (issue) comment, answered in the summary "thread" (no SCM threading). */
    record AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                         String commentId, String text, Author author,
                         boolean topLevel) implements IntegrationEvent {

        public AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                             String commentId, String text, Author author) {
            this(repo, prId, reviewId, threadRef, commentId, text, author, false);
        }
    }
```
b) Ingress — in `issueComment`, after the guards for `created` + PR: if body does NOT start with `/`:
```java
        if (!body.startsWith("/")) {
            return List.of(new AuthorReplied(repo, issueNumber,
                    ReviewIds.reviewId(repo, issueNumber), new ThreadRef(commentId),
                    commentId, body.trim(), author(comment.path("user")), true));
        }
```
(then the existing command path; keep unknown-command drop).
c) Projection:
```java
    public Optional<String> summaryRefOf(String reviewId) {
        // SELECT last_summary_comment_id FROM review_status WHERE review_id = ?
        // null/blank -> Optional.empty(); follow the class's query helper style.
    }
```
(write as real JDBC per the class's existing single-column readers, e.g. `commitOf`.)
d) ConversationSaga — at the top of `planFollowUp`, after provider resolution:
```java
        ThreadRef thread = e.threadRef();
        boolean threadIsOurs;
        if (e.topLevel()) {
            Optional<String> summaryRef = projection.summaryRefOf(e.reviewId());
            if (summaryRef.isEmpty()) {
                timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                        "top-level comment but no posted summary to converse on");
                return Optional.empty();
            }
            thread = new ThreadRef(summaryRef.get());
            threadIsOurs = true;
        } else {
            threadIsOurs = threads.isOurThread(e.reviewId(), thread);
        }
```
and use `thread` everywhere downstream (turn count, the emitted `AnswerFollowUp.threadRef`). `triggeringCommentId` remains `e.commentId()`.
e) Sink — `fetchThread` fallback + `replyInThread` fallback per the Produces block (mirror the existing paging loop; extract a private `issueThreadTranscript(...)` helper to keep methods ≤30 lines).
f) Worker — the null-commit head fetch in the static `answer(...)`.

- [ ] **Step 4: Run all touched module suites**

Run: `./gradlew :spire-contract:test :spire-scm-github:test :spire-orchestrator:test :spire-review-worker:test`
Expected: PASS (known pre-existing orchestrator Kafka-race flakes: verify in isolation before blaming).

- [ ] **Step 5: Commit**

```bash
git add spire-contract spire-scm-github spire-orchestrator spire-review-worker
git commit -m "Answer top-level PR comments in the summary thread

A plain (non-command) PR comment now becomes AuthorReplied(topLevel)
and is routed to the review's summary comment as its thread: the
transcript is the issue-comment tail, the reply is the next top-level
comment, and the conversation policy/turn caps apply unchanged. The
worker resolves the PR head when a summary transcript has no commit."
```

---

### Task 9: Conversation bot-identity fail-closed guard

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ConversationSaga.java`
- Test: the saga test file extended in Task 8

**Interfaces:**
- Consumes: `planFollowUp`'s resolved `ScmProvider provider` (has `botAccountId()`).
- Produces: immediately after provider resolution — a blank/null `botAccountId` records `timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(), "bot identity unknown — re-save the provider to resolve it")` and returns empty. Conversations fail CLOSED on unknown bot identity (the self-loop guard in IntegrationSaga fails open and cannot tell the bot's own replies apart without it).

- [ ] **Step 1: Failing test**: provider fake with `botAccountId = ""` → `planFollowUp` returns empty + the skipped note recorded; with a real id → command emitted (existing tests cover).

- [ ] **Step 2: Run to verify failure** → FAIL.

- [ ] **Step 3: Implement**:
```java
        if (provider.botAccountId() == null || provider.botAccountId().isBlank()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "bot identity unknown — re-save the provider to resolve it");
            return Optional.empty();
        }
```

- [ ] **Step 4: Run suite** → PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator
git commit -m "Refuse conversational replies when the bot identity is unknown

Without a resolved botAccountId the self-loop guard cannot recognize
the bot's own comments, so engaging conversationally risks a reply
loop. Fail closed with an actionable skipped note instead."
```

---

### Task 10: Status-aware summary update fallback

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java` (`updateOrPost`)
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ReviewWorkerTest.java` (extend)

**Interfaces:**
- Consumes: `updateOrPost` (currently catches ANY RuntimeException → fresh post).
- Produces: only `ScmApiException.isNotFound()` falls back to a fresh `postSummary` (deleted summary); every other failure rethrows into the existing summary failure handling (5xx/rate-limit → retryable `ReviewFailed`, 403 → terminal with the real cause — no doomed second post).

- [ ] **Step 1: Failing tests**:
```java
    @Test
    void deletedSummary404FallsBackToAFreshPost() {
        // fake sink updateComment throws ScmApiException status=404 → summaryPosts.size()==1
    }

    @Test
    void forbiddenSummaryUpdateDoesNotDoublePost() {
        // fake sink updateComment throws ScmApiException status=403 (not rate-limited)
        // → NO fresh summary post; the failure propagates into ReviewFailed (assert emitted event)
    }
```

- [ ] **Step 2: Run to verify failure** → FAIL (403 currently fresh-posts).

- [ ] **Step 3: Implement**:
```java
    private CommentRef updateOrPost(CommentSink sink, PostComments command, String body) {
        if (command.priorSummaryRef() != null) {
            try {
                return sink.updateComment(command.repo(), command.prId(), command.priorSummaryRef(), body);
            } catch (RuntimeException e) {
                if (!(unwrap(e) instanceof ScmApiException api) || !api.isNotFound()) {
                    throw e;   // permission/transient failures classify upstream; don't double-post
                }
                LOG.infof("summary %s is gone (404) — posting fresh", command.priorSummaryRef());
            }
        }
        return sink.postSummary(command.repo(), command.prId(), body);
    }
```

- [ ] **Step 4: Run suite** → `./gradlew :spire-review-worker:test` PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-review-worker
git commit -m "Fresh-post the summary only when the old one is gone

A 404 on the in-place update means the summary was deleted; anything
else (lost permission, transient failure) now classifies through the
normal failure path instead of attempting a doomed second post."
```

---

### Task 11: Docs sweep + full verification

**Files:**
- Modify: `docs/SMOKE-TEST.md`, `docs/ROADMAP.md`, `CLAUDE.md`

**Steps:**

- [ ] **Step 1: `docs/SMOKE-TEST.md`** — (a) header: replace "Two modes: **A** … **B** …" with a one-line index of all five modes (A stub, B Bitbucket, C GitHub manual, D GitLab, E GitHub webhook); (b) Mode E "Events" bullet: `/review` now actively re-reviews (Task 3) — reword "(and **Issue comments** if you want `/review`)" to state it triggers a forced re-run, and note plain PR comments start a summary conversation (Task 8); (c) add two "Known limits" lines where the mode-B limits live: draft PRs are skipped until `ready_for_review` unless `SPIRE_REVIEW_DRAFT_PRS=true`; PRs whose diff exceeds the provider's generation limit fail with an explicit too-large error; (d) remove/replace any "known v1 limits" line still claiming `/review` is inactive.
- [ ] **Step 2: `docs/ROADMAP.md`** — backlog item 13: mark the GitHub half done with one line (audit + 12 fixes, date); items stay open for GitLab/Bitbucket parity.
- [ ] **Step 3: `CLAUDE.md`** — one status bullet (≤6 lines, existing style): GitHub finalized — 403/GraphQL rate-limit detection + posting backoff, /review wiring, draft-PR policy, OLD-side/multi-line anchors, honest pagination/406 failures, summary-comment conversations.
- [ ] **Step 4: Full verification**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL modulo the known pre-existing Kafka-race flakes (OrchestratorChoreographyTest, GitHub/GitLabWebhookTest, ProviderResourceResolveTest) — re-run any failure in isolation and report both facts.
Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS (UI untouched; sanity only).
- [ ] **Step 5: Commit**

```bash
git add docs CLAUDE.md
git commit -m "Document the finalized GitHub integration"
```

---

## Self-review checklist (author ran this)

- Audit coverage: finding 1→T1+T2, 2→T2, 3→T5 (+truncated-flag documented as prompt-clip semantics, no change), 4→T3, 5→T9 (+T8's ingress keeps orchestrator-side bot-drop), 6→T6, 7→T7 (warn) , 8→T7, 9→T1 (GraphQL RATE_LIMITED), 10→T7 (GHE fixture), 11→(GitLab/Bitbucket parity — deliberately deferred to roadmap item 13's next phase; T8 fixes the GitHub summary-reply half of #14), 12→T4, 13→T10, 14→T8, 15/16→T11.
- Type consistency: `retryAfterSeconds()` Integer everywhere; `InlineAnchor.endNewLine` Integer with frozen `anchorKey`; `AuthorReplied.topLevel` boolean with 7-arg convenience ctor; config keys match Global Constraints exactly.
- Flagged for implementers: fixture-helper names in each test class are indicative — match the real files; `LineType` constant name must be checked (T6); ReviewRerunService test seam follows the file's fake conventions (T3).
