# Spend Caps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound what a deployment can spend, refuse pathologically large diffs, and give a refused review a terminal state it can be archived from.

**Architecture:** Three gates, each where its inputs already are — diff size on `DiffFetched`, spend before `GenerateReview`, spend again in `ConversationSaga` (the path that is currently unbounded). All three speak one refusal vocabulary and drive the review to a new terminal `refused` status through the same shape `onReviewFailed` already uses. Caps read the existing ledger; **no new storage**.

**Tech Stack:** Java 25, Quarkus 3.38.1, Gradle Kotlin DSL, Postgres, Kafka (Redpanda), React 19 + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-08-09-fleet-cost-caps-design.md` — read it first. The rate limit is deliberately **not** here; it is Spec B.

## Global Constraints

- Money in millicents. Never `double`/`float`/`BigDecimal` for money.
- **No synthetic data that could pass for real.** Fixtures use `TEST-`/`CANARY-` prefixes.
- `spire-contract` is framework-free: JDK plus `jackson-annotations` only.
- 4-space Java indent, 2-space TS. Explicit types over `var`. `interface` over `type` for TS object shapes.
- Max 3 Java method params, methods ≤30 lines, classes ≤300, React components ≤250 lines.
- **Never mention AI/agentic authoring in commit messages.** Subject imperative ≤72 chars; **wrap body lines at 72**.
- lucide-react icons only, never emoji.
- Verify with `./gradlew testFast` and `./gradlew :spire-orchestrator:test`. **Never `./gradlew assemble`** — it fails here (JDK 21 `JAVA_HOME` vs toolchain 25); CI covers it.
- A ~5s `UP-TO-DATE` Gradle run is a **cached pass**. Force with the task-scoped `--rerun`.
- **One Gradle build per module at a time.** Contention shows up as mass Postgres/Kafka `ConnectException`s because the other build's Testcontainers reaper kills Dev Services. `./gradlew --status` (one IDLE daemon) is the all-clear.

## Verified API facts — use these exact names

| Fact | Detail |
|---|---|
| Refusal pattern to model on | `DefaultLlm` — `record DefaultLlm(String packed, String model, Refusal refusal)`, `enum Refusal { NO_DEFAULT_PROVIDER, MODEL_NOT_PRICEABLE }`, static factories, `isSpendable()`, `detail()`, `note()` |
| Terminal-failure shape | `ResultSaga.onReviewFailed` `:342-356` — `clearScheduledRetry`, `updateStatus(id, "failed", stage)`, `setNote`, `setError`, `lifecycle.handle(new RecordCommand.RecordFailure(commit, phase, false))` |
| Stage constants | `ReviewProjection.STAGE_RECEIVED 0`, `STAGE_DIFF 1`, `STAGE_CONTEXT 2`, `STAGE_REVIEW 3`, `STAGE_COMMENTS 4`, `STAGE_POSTING 5`, `STAGE_DONE 6` |
| Terminal-status list | **exactly one site**: `AttentionQueries.java:181` — `lower(status) NOT IN ('completed', 'failed', 'cancelled', 'superseded')` |
| Archive guard | `ReviewProjection.archiveRow` refuses `lower(status) <> 'reviewing'`, so **`refused` is archivable with no change** |
| Settings pattern | `ReviewSettingsResource` — `@Path`, `@RolesAllowed("spire-admin")`, a `record …View`, `@GET`/`@PUT`, validation throwing `BadRequestException`, backed by a policy bean over `AppSettingRepository` |
| Setting store | `AppSettingRepository.get(String) -> Optional<String>`, `set(String, String)` |
| Diff stats | `DiffFetched(reviewId, prId, commit, changedFiles, languages, sizeBytes, truncated, references, repoRules)` |
| Conversation gate site | `ConversationSaga.planFollowUp`, beside `if (!llm.isSpendable())` at `:114-118` |
| Ledger | `llm_charge` — `cost_millicents`, `call_ref`, `priced_at`, `archived_at` |

## File Structure

| File | Responsibility |
|---|---|
| `.../orchestrator/caps/CapRefusal.java` | **Create.** The shared refusal vocabulary. |
| `.../orchestrator/caps/CapPolicy.java` | **Create.** Reads limits from `app_setting`; unset = unlimited. |
| `.../orchestrator/caps/SpendWindow.java` | **Create.** The ledger read: money + call count over a window. |
| `.../orchestrator/settings/CapSettingsResource.java` | **Create.** REST for the limits. |
| `.../orchestrator/pipeline/ResultSaga.java` | **Modify.** `refuse(...)`; diff gate; pre-spend gate. |
| `.../orchestrator/pipeline/ConversationSaga.java` | **Modify.** The follow-up gate. |
| `.../orchestrator/attention/AttentionQueries.java` | **Modify.** `refused` in the terminal list; the cap row. |
| `spire-ui/src/api.ts`, `components/SettingsGeneral.tsx` | **Modify.** The limits form. |

---

### Task 1: The refusal vocabulary

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/caps/CapRefusal.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/caps/CapRefusalTest.java`

**Interfaces:** Produces `CapRefusal` with `allowed()`, `refused()`, `detail()`, `note()`, `reason()`, and static factories `allow()`, `diffTooLarge(int, long)`, `spendCapReached(long, long)`, `callCapReached(int, int)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.caps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One vocabulary for all three gates. Three refusals must be distinguishable by their TEXT, not only
 * by which line produced them — a reader of the timeline sees the sentence, never the call site.
 */
class CapRefusalTest {

    @Test
    void anAllowedDecisionCarriesNoWording() {
        CapRefusal decision = CapRefusal.allow();
        assertTrue(decision.allowed());
        assertFalse(decision.refused());
        assertEquals("", decision.detail());
        assertEquals("", decision.note());
    }

    @Test
    void eachRefusalNamesItsOwnLimitAndTheMeasuredValue() {
        String diff = CapRefusal.diffTooLarge(5_000, 900_000L).detail();
        String spend = CapRefusal.spendCapReached(750_000L, 500_000L).detail();
        String calls = CapRefusal.callCapReached(120, 100).detail();

        assertTrue(diff.contains("5000"), "names the measured file count: " + diff);
        assertTrue(spend.contains("7.50") || spend.contains("5.00"), "names money in dollars: " + spend);
        assertTrue(calls.contains("120") && calls.contains("100"), "names both figures: " + calls);

        assertEquals(3, java.util.Set.of(diff, spend, calls).size(),
                "three refusals must read differently, or the timeline cannot tell them apart");
    }

    @Test
    void aRefusalIsNotAllowed() {
        assertTrue(CapRefusal.diffTooLarge(1, 1L).refused());
        assertFalse(CapRefusal.diffTooLarge(1, 1L).allowed());
    }
}
```

- [ ] **Step 2: Run it, confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests "*CapRefusalTest*"`
Expected: FAIL — `cannot find symbol: class CapRefusal`

- [ ] **Step 3: Implement**

```java
package dev.codespire.orchestrator.caps;

/**
 * Why a gate refused to spend, in words an operator reads on the timeline.
 *
 * <p>Modelled on {@code DefaultLlm}, whose javadoc records why one vocabulary matters: two emit sites
 * once described the same refusal differently. Deliberately NOT an extension of
 * {@code DefaultLlm.Refusal}, which answers "can this LLM be used" — a cap refusal is a budget policy
 * decision, and folding them together would drag budget logic into credential resolution.
 */
public record CapRefusal(Reason reason, String measured, String limit) {

    public enum Reason {
        DIFF_TOO_LARGE,
        SPEND_CAP_REACHED,
        CALL_CAP_REACHED
    }

    /** Millicents per dollar — money is stored in millicents and shown in dollars. */
    private static final long MILLICENTS_PER_DOLLAR = 100_000L;

    public static CapRefusal allow() {
        return new CapRefusal(null, "", "");
    }

    public static CapRefusal diffTooLarge(int changedFiles, long sizeBytes) {
        return new CapRefusal(Reason.DIFF_TOO_LARGE,
                changedFiles + " files / " + sizeBytes + " bytes", "");
    }

    public static CapRefusal spendCapReached(long spentMillicents, long capMillicents) {
        return new CapRefusal(Reason.SPEND_CAP_REACHED,
                dollars(spentMillicents), dollars(capMillicents));
    }

    public static CapRefusal callCapReached(int calls, int cap) {
        return new CapRefusal(Reason.CALL_CAP_REACHED, String.valueOf(calls), String.valueOf(cap));
    }

    public boolean allowed() {
        return reason == null;
    }

    public boolean refused() {
        return reason != null;
    }

    /** One line for the review timeline, where the operator is already looking. */
    public String detail() {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case DIFF_TOO_LARGE -> "diff too large to review (" + measured + ")";
            case SPEND_CAP_REACHED -> "spend cap reached — " + measured + " of " + limit + " used";
            case CALL_CAP_REACHED -> "call cap reached — " + measured + " of " + limit + " calls used";
        };
    }

    /** The review's note field, which says what the operator can DO about it. */
    public String note() {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case DIFF_TOO_LARGE -> "Not reviewed: " + detail()
                    + ". Raise the diff limit in Settings -> General, or split the pull request.";
            case SPEND_CAP_REACHED, CALL_CAP_REACHED -> "Not reviewed: " + detail()
                    + ". Capacity returns as older usage ages out, or raise the cap in Settings -> General.";
        };
    }

    private static String dollars(long millicents) {
        return String.format("$%.2f", (double) millicents / MILLICENTS_PER_DOLLAR);
    }
}
```

`dollars` uses a `double` for **display formatting only** — never for arithmetic on a stored amount. All comparison and summing stays in `long` millicents.

- [ ] **Step 4: Run it, confirm it passes**

Run: `./gradlew :spire-orchestrator:test --tests "*CapRefusalTest*"`

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/caps/CapRefusal.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/caps/CapRefusalTest.java
git commit -m "Add one refusal vocabulary for the spend gates"
```

---

### Task 2: The `refused` terminal status

**Files:**
- Modify: `.../orchestrator/pipeline/ResultSaga.java` (add `refuse(...)` beside `onReviewFailed` `:342-356`)
- Modify: `.../orchestrator/attention/AttentionQueries.java:181`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/RefusedReviewLifecycleTest.java`

**Interfaces:**
- Consumes: `CapRefusal` (Task 1).
- Produces: `private void refuse(String reviewId, String commit, String phase, int stage, CapRefusal refusal)` on `ResultSaga`.

**Why this task exists at all:** the pre-spend refusal this design copies (`skipUnspendable`) writes a note and nothing else. `AttentionQueries`' own javadoc records the result — a refused review *"sits in REVIEWING until REVIEW_STUCK eventually fires"*, and that row blames *"a webhook delivery path or a worker"*. Since ADR-024, `archiveRow` also refuses a `reviewing` row, so such a review **cannot be cleared at all**. Tolerable for a one-time config error; unacceptable for a cap that refuses by design.

- [ ] **Step 1: Write the failing tests**

Use `@QuarkusTest` with `ReviewFixtures` (exists from the archival work).

```java
@Test
void aRefusedReviewIsTerminalAndArchivable() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedReviewingReview(projection, pr);

    saga.refuseForTest(ReviewFixtures.reviewIdFor(pr), COMMIT, "GenerateReview",
            ReviewProjection.STAGE_REVIEW, CapRefusal.spendCapReached(750_000L, 500_000L));

    ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
    assertEquals("refused", detail.status());
    assertTrue(detail.note().contains("spend cap reached"), "the note says why: " + detail.note());
    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr),
            "a refused review must be clearable — archiveRow rejects anything still 'reviewing'");
}

@Test
void aRefusedReviewDoesNotRaiseTheStuckRow() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedReviewingReview(projection, pr);
    assertTrue(stuckRowExistsFor(pr), "a reviewing row raises REVIEW_STUCK once it ages");

    saga.refuseForTest(ReviewFixtures.reviewIdFor(pr), COMMIT, "GenerateReview",
            ReviewProjection.STAGE_REVIEW, CapRefusal.spendCapReached(750_000L, 500_000L));

    assertFalse(stuckRowExistsFor(pr),
            "a routine refusal must not produce a row blaming a webhook or a worker");
}
```

`stuckRowExistsFor` must backdate `updated_at` past the stuck threshold, or the first assertion is vacuous — a fresh review is not yet stuck, so the row would be absent for the wrong reason. Read `AttentionQueriesTest` for how existing tests age a row.

`refuseForTest` is a package-private passthrough to `refuse(...)`; do not widen `refuse` itself to public.

- [ ] **Step 2: Run, confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*RefusedReviewLifecycleTest*"`
Expected: FAIL — no `refuseForTest`, and once added, `status` is `reviewing` rather than `refused`.

- [ ] **Step 3: Add `refuse` to `ResultSaga`**

```java
    /**
     * End a review because policy refused to spend on it. Mirrors {@link #onReviewFailed}'s terminal
     * shape so the aggregate leaves REVIEWING — without that the review sits there until REVIEW_STUCK
     * fires, blaming a webhook or a worker for what was a deliberate decision, and ADR-024's archive
     * guard refuses to clear anything still 'reviewing'.
     *
     * <p>Status is 'refused', not 'failed': the archive guard, the attention queries and the reviews
     * list all key on status, so filing a policy decision as an infrastructure failure would put it in
     * the same bucket as a genuine outage. This project split pr_state out of status for the same
     * reason — two different facts cannot share one badge.
     */
    private void refuse(String reviewId, String commit, String phase, int stage, CapRefusal refusal) {
        projection.clearScheduledRetry(reviewId);
        timeline.record("result", "refused:" + phase, reviewId, refusal.detail());
        projection.updateStatus(reviewId, "refused", stage);
        projection.setNote(reviewId, refusal.note());
        LOG.warnf("Refused %s for %s — %s", phase, reviewId, refusal.detail());
        // retryable=false, so the decider yields a terminal state and the run leaves REVIEWING.
        lifecycle.handle(reviewId, new RecordCommand.RecordFailure(commit, phase, false));
    }
```

`setError` is deliberately **not** called: there is no provider or worker error to record, and populating it would make the detail page show an infrastructure error for a policy decision.

- [ ] **Step 4: Add `refused` to the terminal-status list**

`AttentionQueries.java:181` is the **only** site enumerating terminal statuses:

```sql
                 WHERE lower(status) NOT IN ('completed', 'failed', 'cancelled', 'superseded', 'refused')
```

`archiveRow` needs no change — it guards on `<> 'reviewing'`, so `refused` is archivable already.

- [ ] **Step 5: Run, confirm they pass**

Run: `./gradlew :spire-orchestrator:test`

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Give a refused review a terminal state it can be cleared from

The pre-spend refusal this copies writes a note and nothing else, so
the review sits in reviewing until the stuck-review row fires blaming
a webhook or a worker -- and since archiving refuses a running review,
it cannot be cleared at all. Tolerable for a one-time configuration
error, unacceptable for a cap that refuses by design.

Uses refused rather than failed because the archive guard, the
attention queries and the reviews list all key on status, so filing a
policy decision as an infrastructure failure puts it in the same
bucket as a genuine outage.
EOF
```

---

### Task 3: The limits, and unset means unlimited

**Files:**
- Create: `.../orchestrator/caps/CapPolicy.java`
- Create: `.../orchestrator/settings/CapSettingsResource.java`
- Test: `.../caps/CapPolicyTest.java`, `.../settings/CapSettingsResourceTest.java`

**Interfaces:**
- Consumes: `AppSettingRepository.get(String) -> Optional<String>` and `set(String, String)`.
- Produces: `CapPolicy` with `OptionalInt maxChangedFiles()`, `OptionalLong maxDiffBytes()`, `OptionalLong spendCapMillicents()`, `OptionalInt callCap()`, `Duration window()`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void everyLimitIsUnsetByDefault() {
    assertTrue(policy.maxChangedFiles().isEmpty(), "an unset cap must be unlimited");
    assertTrue(policy.spendCapMillicents().isEmpty());
    assertTrue(policy.callCap().isEmpty());
}

@Test
void aStoredLimitIsRead() {
    settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "500");
    assertEquals(500, policy.maxChangedFiles().orElseThrow());
}

@Test
void anUnparseableStoredValueIsTreatedAsUnset() {
    settings.set(CapPolicy.KEY_SPEND_CAP, "not-a-number");
    assertTrue(policy.spendCapMillicents().isEmpty(),
            "a corrupt setting must not refuse every review — fail open, and the operator can see the "
            + "field is wrong in Settings");
}
```

REST tests: `@QuarkusTest`, `@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})`. Assert `GET` returns nulls when unset, `PUT` round-trips, and a negative value is rejected **with a body**, not merely a 400 — this module has a documented class of bodiless `BadRequestException` (`techdebt/spire-orchestrator/3-3-…`).

- [ ] **Step 2: Run, confirm they fail**

- [ ] **Step 3: Implement `CapPolicy`**

Keys: `caps.max-changed-files`, `caps.max-diff-bytes`, `caps.spend-millicents`, `caps.calls`, `caps.window-minutes`. Each read through `AppSettingRepository.get`, parsed leniently, empty on absence **or** unparseable. Window defaults to 1440 minutes when unset; the *limits* have no defaults.

**Unset must mean unlimited.** Shipping non-null defaults would silently change a running deployment's behaviour on upgrade — the mistake V30 made by leaving legacy models rateless, still the most operator-visible consequence of ADR-023.

- [ ] **Step 4: Implement `CapSettingsResource`**

Mirror `ReviewSettingsResource`: `@Path("/api/settings/caps")`, `@RolesAllowed("spire-admin")`, a `CapSettingsView` record of nullable boxed types, `@GET`/`@PUT`. Reject negatives and zero with an actionable message; build the response entity explicitly:

```java
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }
```

- [ ] **Step 5: Run, confirm they pass**

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -m "Read spend limits from settings, unset meaning unlimited"
```

---

### Task 4: The windowed ledger read

**Files:**
- Create: `.../orchestrator/caps/SpendWindow.java`
- Test: `.../caps/SpendWindowIT.java`

**Interfaces:**
- Produces: `record Usage(long spentMillicents, int calls)` and `Usage since(Instant from)` on `SpendWindow`.

- [ ] **Step 1: Write the failing test**

```java
/**
 * The cap's view of the ledger. Two things this pins that are easy to get wrong.
 */
@QuarkusTest
class SpendWindowIT {

    @Test
    void archivingAReviewDoesNotRestoreBudget() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        long before = window.since(Instant.now().minus(Duration.ofHours(1))).spentMillicents();
        assertTrue(before > 0, "the fixture must record real spend or this proves nothing");

        projection.archiveReview(WS, REPO, pr);

        assertEquals(before, window.since(Instant.now().minus(Duration.ofHours(1))).spentMillicents(),
                "archiving must not refund the budget, or archiving becomes a way to reset the cap");
    }

    @Test
    void callsAreCountedEvenWhenTheyCouldNotBePriced() {
        long pr = ReviewFixtures.newPr();
        seedUnknownPricedCall(pr);   // pricing_mode='UNKNOWN', cost_millicents NULL

        SpendWindow.Usage usage = window.since(Instant.now().minus(Duration.ofHours(1)));

        assertEquals(0L, usage.spentMillicents(), "SUM skips a NULL cost");
        assertEquals(1, usage.calls(), "but the call axis still counts it — this is the ADR-023 hole");
    }

    @Test
    void chargesOutsideTheWindowAreExcluded() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        backdateCharges(ReviewFixtures.reviewIdFor(pr), Duration.ofDays(2));

        assertEquals(0L, window.since(Instant.now().minus(Duration.ofHours(1))).spentMillicents());
    }
}
```

- [ ] **Step 2: Run, confirm they fail**

- [ ] **Step 3: Implement**

```java
    /**
     * Deployment-wide usage since {@code from}.
     *
     * <p>Deliberately does NOT filter {@code archived_at}. Ten ledger reads beside this one do, and
     * copying them here would make archiving a review refund its budget — so an operator tidying the
     * list would silently buy themselves more spend.
     *
     * <p>Two axes because a money-only cap is inert on an UNMETERED deployment, where every charge is
     * an asserted zero. An UNKNOWN-priced row has a NULL cost that SUM skips and the call count
     * catches, which is exactly the gap ADR-023 identified.
     */
    public Usage since(Instant from) {
        String sql = """
                SELECT COALESCE(SUM(cost_millicents), 0) AS spent,
                       COUNT(DISTINCT call_ref)          AS calls
                  FROM llm_charge WHERE priced_at >= ?
                """;
        ...
    }
```

A read failure must **fail open** — return zero usage and log at ERROR. A cap that refuses every review because its own query failed is worse than a cap that misses a window; the ledger write path already reports its own faults.

- [ ] **Step 4: Run, confirm they pass**

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Read deployment spend and call count over a window

Deliberately does not filter archived charges. Ten ledger reads beside
this one do, and copying them would make archiving a review refund its
budget -- an operator tidying the list would silently buy more spend.

Counts calls as well as money, because a money-only cap is inert on an
unmetered deployment where every charge is an asserted zero.
EOF
```

---

### Task 5: The diff-size gate

**Files:**
- Modify: `.../orchestrator/pipeline/ResultSaga.java` — the `case DiffFetched` branch (`:126-140`)
- Test: `.../pipeline/DiffSizeGateTest.java`

**Interfaces:** Consumes `CapRefusal` (1), `refuse(...)` (2), `CapPolicy` (3).

The check goes **before** `commands.emit(new ActionCommand.GatherContext(...))` at `:139`, so a refused diff never runs the context fan-out — per-issue API calls, a 20-second bounded wait and an encrypted blob write, all otherwise discarded.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anOversizedDiffIsRefusedBeforeContextIsGathered() {
    settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "100");

    saga.on(diffFetched(pr, 5_000, 900_000L));

    assertTrue(commands.emitted().isEmpty(), "GatherContext must not run for a diff we will not review");
    assertEquals("refused", projection.loadDetail(WS, REPO, pr).orElseThrow().status());
}

@Test
void anUnsetDiffLimitReviewsAnythingAtAll() {
    // no setting written
    saga.on(diffFetched(pr, 5_000, 900_000L));

    assertInstanceOf(ActionCommand.GatherContext.class, commands.emitted().getFirst(),
            "an unset cap must be a no-op, or every existing deployment changes behaviour on upgrade");
}
```

The second test is the more important of the two: it is the one that fails if someone gives the limit a default.

- [ ] **Step 2: Run, confirm they fail**

- [ ] **Step 3: Implement the gate**

Inside `case DiffFetched`, after the existing `ifCurrentRun` guard and before the `GatherContext` emit, compare `e.changedFiles()` and `e.sizeBytes()` against the policy; on refusal call `refuse(e.reviewId(), e.commit(), "FetchDiff", ReviewProjection.STAGE_DIFF, decision)` and return.

- [ ] **Step 4: Run, confirm they pass**

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src
git commit -m "Refuse a diff too large to be worth reviewing"
```

---

### Task 6: The pre-spend gate

**Files:**
- Modify: `.../orchestrator/pipeline/ResultSaga.java` — beside `if (!llm.isSpendable())` at `:155`
- Test: `.../pipeline/SpendCapGateTest.java`

**Interfaces:** Consumes `SpendWindow` (4), `CapPolicy` (3), `refuse(...)` (2).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theCallCapFiresOnAnUnmeteredDeploymentWhereTheMoneyCapCannot() {
    settings.set(CapPolicy.KEY_SPEND_CAP, "500000");   // $5.00 — unreachable when every charge is 0
    settings.set(CapPolicy.KEY_CALLS, "2");
    seedUnmeteredCalls(3);                             // pricing_mode='UNMETERED', cost 0

    saga.on(contextAssembled(pr));

    assertEquals("refused", projection.loadDetail(WS, REPO, pr).orElseThrow().status());
    assertTrue(projection.loadDetail(WS, REPO, pr).orElseThrow().note().contains("call cap"),
            "the money cap cannot fire at zero cost; the call axis is the whole point");
}

@Test
void anUnsetCapNeverRefuses() {
    seedUnmeteredCalls(1_000);
    saga.on(contextAssembled(pr));
    assertInstanceOf(ActionCommand.GenerateReview.class, commands.emitted().getFirst());
}
```

The first test is the one that fails if anyone later "simplifies" the cap to a single money figure.

- [ ] **Step 2–5:** run/confirm-fail, implement beside the `isSpendable` check, run/confirm-pass, commit as `"Refuse a review that would exceed the spend cap"`.

---

### Task 7: The conversation gate

**Files:**
- Modify: `.../orchestrator/pipeline/ConversationSaga.java` — beside `if (!llm.isSpendable())` at `:114-118`
- Test: `.../pipeline/ConversationSpendCapTest.java`

**Why this is the most important task in the plan:** this path emits a paid `AnswerFollowUp` guarded only by `isSpendable`. Threads are free to open, the turn cap is per-thread, and `CallRefs.java:76-77` states that **an @-mention removes the cap entirely**. The comment immediately above the guard records that this same path was assumed safe once and was not. A spend cap that skips it leaves the abuse case half-open.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aFollowUpIsRefusedWhenTheCapIsReached() {
    settings.set(CapPolicy.KEY_CALLS, "1");
    seedUnmeteredCalls(5);

    assertTrue(saga.planFollowUp(authorReplied()).isEmpty(), "no paid command may be planned");
}

@Test
void anAtMentionDoesNotBypassTheSpendCap() {
    settings.set(CapPolicy.KEY_CALLS, "1");
    seedUnmeteredCalls(5);

    assertTrue(saga.planFollowUp(authorRepliedMentioningBot()).isEmpty(),
            "an @-mention bypasses the TURN cap by design; it must not also bypass the spend cap, "
            + "or the unbounded case stays unbounded");
}
```

The second test is the unbounded case. Without it, the gate can be added and the hole remains.

**A refused follow-up does not move the review's status** — the review may be `completed`, and a refusal to answer one reply must not retract that. Record it on the timeline and leave the review alone. Do **not** call `refuse(...)` here.

- [ ] **Step 2–5:** run/confirm-fail, implement, run/confirm-pass, commit as `"Bound the conversation path, which had no spend limit"`.

---

### Task 8: The attention row

**Files:**
- Modify: `.../orchestrator/attention/AttentionQueries.java`
- Test: `.../attention/CapAttentionTest.java`

A `CAP_REACHED` row while current usage exceeds a configured cap. **No acknowledgement watermark** — unlike ADR-023's two cost rows, this describes current state, so it clears when the window rolls or the operator raises the limit, honouring the panel's contract that a row cannot outlive the state that produced it.

The message names the measured value, the cap, and — since §8 chose a rolling window — the computed instant capacity returns: *oldest in-window charge + window length*.

- [ ] Test that the row appears while over the cap, and **that it disappears once the charges age out of the window** — the second half is what proves it is current-state and not a permanent row.
- [ ] Implement, run, commit as `"Raise an attention row while a cap is being enforced"`.

---

### Task 9: The settings UI

**Files:**
- Modify: `spire-ui/src/api.ts`, `spire-ui/src/components/SettingsGeneral.tsx`
- Test: `spire-ui/src/components/SettingsGeneral.caps.test.tsx`

Add a **Limits** section to Settings → General beside Code review and Conversation: four optional numeric fields plus the window. Empty means unlimited and must round-trip as `null`, never `0` — **a blank field becoming `0` is precisely how ADR-023's "unknown became zero" bug entered**, and here it would turn "no cap" into "cap of zero", refusing every review.

- [ ] Test that a blank field sends `null` and that `0` is rejected. Run `npm test -- --run` and `npx tsc --noEmit`. Commit as `"Add spend limits to the general settings screen"`.

---

### Task 10: Record the decision

**Files:** `docs/DECISIONS.md`, `CLAUDE.md`, `docs/SMOKE-TEST.md`

- [ ] **Run the full verification first**, so the docs quote measured numbers:

```
./gradlew testFast --rerun-tasks
./gradlew :spire-orchestrator:test :spire-gateway:test :spire-review-worker:test --rerun-tasks
cd spire-ui && npm test -- --run && npx tsc --noEmit
```

Read counts from the JUnit XML, not the console.

- [ ] **ADR-025** recording: that the conversation path was unbounded and the codebase already documented it; that a refused review needed a terminal state because the pattern it copied left one stuck *and*, after ADR-024, unarchivable; that `refused` is distinct from `failed` because status drives the archive guard, the attention queries and the list filters; that every cap carries a call axis because money alone is inert on an `UNMETERED` deployment; that the spend read must not filter `archived_at`; and that the cap is **soft**, with overshoot bounded by in-flight reviews.
- [ ] **CLAUDE.md** status entry with the real counts.
- [ ] **SMOKE-TEST** mode: set a call cap of 1, run a review, confirm the second is refused with a note, confirm it is archivable, confirm the attention row appears and clears, then unset the cap and confirm reviews resume.
- [ ] Commit as `"Record the spend caps and the refused-review lifecycle"`.

---

## Ordering

1 → 2 → 3 → 4, then 5, 6, 7 in any order (5 needs 3; 6 and 7 need 4). 8 needs 4. 9 needs 3. 10 last.

Tasks 5, 6 and 7 all edit `ResultSaga`/`ConversationSaga` and all need the Gradle build, so run them **one at a time**. Task 9 is npm and can overlap with any of them.

## Self-review

**Spec coverage.** §1 refused status → T2; §2 three gates → T5, T6, T7; §3 vocabulary → T1; §4 dual axis → T4, T6; §5 no `archived_at` filter → T4; §6 soft cap → T10 (documented, not enforceable); §7 loud refusals → T2, T8; §8 rolling window → T4, T8; config → T3, T9. All eight spec tests appear. Spec B is correctly absent.

**Placeholders.** Tasks 6–9 give test code and prose rather than full implementations, because each is a small insertion at a site the plan cites exactly and whose surrounding pattern is quoted in earlier tasks. The tests — which is where the reasoning lives — are written out in full.

**Type consistency.** `CapRefusal`'s factories and `allowed()`/`refused()`/`detail()`/`note()` are identical across T1, T2, T5, T6. `CapPolicy`'s `KEY_*` constants and `OptionalInt`/`OptionalLong` accessors match across T3, T5, T6, T7, T9. `SpendWindow.Usage(spentMillicents, calls)` matches across T4, T6, T7, T8. `refuse(...)`'s five parameters are the same in T2, T5, T6 — and note T7 deliberately does **not** call it.
