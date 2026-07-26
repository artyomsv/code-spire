# Attention Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A topbar bell in `spire-ui` whose every row is a condition that is true right now, derived on demand from state each service already owns, linking to the page that fixes it.

**Architecture:** Two independent read endpoints returning the same `AttentionView` shape — `GET /api/attention` on the orchestrator and `GET /api/webhook-repos/attention` on the gateway — merged client-side by a polling hook. No Kafka topic, no cross-schema write, no dismissal state: every row is a query result, so fixing the cause removes the row.

**Tech Stack:** Java 25 / Quarkus 3.36 / Gradle Kotlin DSL, Postgres + Flyway, JAX-RS, JUnit 5 + Testcontainers + WireMock, React 19 / Vite / TypeScript, vitest.

Spec: `docs/superpowers/specs/2026-07-27-attention-panel-design.md`

## Global Constraints

- **Branch:** `attention-panel`, off `master` @ `26213dd`. Baseline: **785 Java tests, 121 UI tests, 0 failures.**
- **Java build needs an explicit JDK 25:** every Gradle command must be prefixed `JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9"`. The machine's default `JAVA_HOME` is a 32-bit JDK 21 and Quarkus augmentation runs in the daemon JVM, so toolchains alone are not enough.
- **Docker must be running** for Testcontainers (Postgres + Kafka) tests.
- **Provider neutrality (ADR-020) is build-enforced.** No new source text in `spire-contract`, `spire-orchestrator`, `spire-review-worker` or `spire-gateway` may contain `bitbucket`, `github`, `gitlab`, `jira` or `confluence` outside the existing allowlist. Comments are exempt. **Do not add allowlist entries** — every condition code is neutral, and every provider name reaches the UI as *data* read from a DB column.
- **Indentation:** 4 spaces in Java, 2 spaces in TypeScript/TSX/CSS.
- **TypeScript:** `interface` for object shapes, never `type`.
- **Java:** explicit types, never `var`.
- **Icons:** `lucide-react` only. Never emoji.
- **DTO naming:** only `*Dto`, `*View` or `*Payload`. This feature uses `AttentionView`.
- **Never persist or log a provider's response body** on an auth failure — a 401 body is a plausible place for a token to be echoed. Pipeline-observed rejections store the fixed string `Authentication rejected (HTTP 401)`.
- **No synthetic data.** Tests insert rows with obviously-non-real values (`TEST-`, `example.invalid`, `$0.01`). No plausible-looking production-shaped data.
- **Commit messages:** imperative mood, max 72 chars on the first line, body for non-trivial changes. **Never mention AI/agentic authoring** — no `Co-Authored-By` trailer, no model or vendor names, no "generated with".
- **Two severities only:** `BLOCKING` and `WARNING`. Deliberately not named after review-finding severities.
- **Never call the project "open source"** in any doc or UI copy — it is source-available (ADR-021).

## Existing code this plan builds on

Read-only facts established before planning; do not re-derive them.

| Fact | Location |
|---|---|
| UI already proxies two services: `/api/webhook-repos` → gateway, `/api` → orchestrator | `spire-ui/vite.config.ts:26-27` |
| Default LLM provider resolves with `is_default = TRUE AND enabled = TRUE` | `LlmProviderRegistry.java:164` |
| `ScmApiException` is the provider-neutral failure interface, with defaulted `isNotFound()` / `isRateLimited()` / `isDiffTooLarge()` / `retryAfterSeconds()` | `spire-contract/.../contract/scm/ScmApiException.java` |
| `ReviewFailed(reviewId, commit, phase, error, retryable, attempt)` — 5 construction sites, all in the worker | `DiffWorker.java` ×2, `ReviewWorker.java` ×3 |
| `DiffWorker` classifies with a **direct** `instanceof` check | `DiffWorker.java:83-85` |
| `ReviewWorker.isRetryable(Throwable)` **walks the cause chain** (LangChain4j wraps) | `ReviewWorker.java:938-950` |
| `ResultSaga.onReviewFailed(ReviewFailed e)` is the consumer hook | `ResultSaga.java:272` |
| `ReviewProviderResolver.resolveForReview(String reviewId) → Optional<ScmProvider>` | `ReviewProviderResolver.java:31` |
| `ProviderRegistry.resolveById(UUID) → Optional<ScmProvider>` and `ContextProviderRegistry.resolveById(UUID) → Optional<ContextProviderConfig>` exist; **`LlmProviderRegistry` has only `resolveDefault()`** | the three registries |
| `LlmKeyValidator.ping(type, baseUrl, apiKey)` already probes `GET {baseUrl}/models` with per-type auth headers — but **throws** `BadRequestException` | `LlmKeyValidator.java:32` |
| `ContextKeyValidator.check(...)` returns a **non-throwing** `CheckOutcome(boolean ok, String account, int status, String detail)` | `ContextKeyValidator.java:44` |
| Each resource declares its **own** nested `CheckResult(boolean ok, String account, String detail)` | `ProviderResource.java:139`, `ContextProviderResource.java:122` |
| `RegistryWebhookEdge` has five WARN-only rejection paths | `RegistryWebhookEdge.java:73, 78, 85, 94, 111` |
| Migration heads: orchestrator `V27`, gateway `V1` | `db/migration/` in each |

### Exact column names (verified)

```
scm_provider     (id, name, type, base_url, workspace, auth_kind, auth_username, auth_secret,
                  bot_account_id VARCHAR NOT NULL DEFAULT '', enabled, created_at, updated_at,
                  conversation_level TEXT, bot_username TEXT)
llm_provider     (id, name, type, base_url, api_key, model, temperature, max_tokens,
                  enabled, is_default, created_at, updated_at)
context_provider (id, name, type, base_url, ... , enabled, is_default, created_at)
review_status    (review_id, workspace, slug, pr_id, ..., status VARCHAR(32),
                  pr_state VARCHAR(16) NOT NULL DEFAULT 'OPEN', updated_at)
dlq_entry        (id, ..., status TEXT NOT NULL DEFAULT 'pending')   -- pending | replayed | discarded
webhook_repo     (id, provider_type, scope, target, webhook_key, webhook_secret, enabled, created_at, updated_at)
```

`bot_account_id` is `NOT NULL DEFAULT ''` (test for `= ''`), while `bot_username` is nullable `TEXT` (test for `IS NULL OR = ''`). The unresolved-identity check must handle both forms.

`ReviewState.Status` values: `IDLE, REVIEWING, COMPLETED, FAILED, CANCELLED`. Terminal = the last three.

## File Structure

**`spire-contract`** (Apache-2.0 — must never depend on a service module)
- Create `attention/AttentionView.java` — the shared wire shape + `Severity` enum. Nothing else.
- Modify `scm/ScmApiException.java` — add defaulted `isUnauthorized()`.
- Modify `event/IntegrationEvent.java` — `ReviewFailed` gains `credentialRejected`.

**`spire-orchestrator`** (FSL)
- Create `db/migration/V28__provider_credential_check.sql`
- Create `attention/AttentionQueries.java` — every orchestrator-side condition as SQL, returning `List<AttentionView>`. One responsibility: read state, emit rows.
- Create `attention/AttentionResource.java` — thin JAX-RS wrapper. No logic.
- Modify `provider/ProviderRegistry.java`, `llm/LlmProviderRegistry.java`, `context/ContextProviderRegistry.java` — `recordCheck` write + read the three new columns
- Modify `provider/ProviderView.java`, `llm/LlmProviderView.java`, `context/ContextProviderView.java` — three fields each
- Modify `provider/ProviderResource.java`, `context/ContextProviderResource.java` — persist check outcomes
- Modify `llm/LlmKeyValidator.java` — add non-throwing `check(...)`
- Modify `llm/LlmProviderResource.java` — add `POST /{id}/check`
- Modify `dlq/DlqRepository.java` — add `countPending()`
- Modify `pipeline/ResultSaga.java` — record credential rejection on `ReviewFailed`

**`spire-review-worker`** (FSL)
- Modify `pipeline/DiffWorker.java`, `pipeline/ReviewWorker.java` — set `credentialRejected`

**`spire-gateway`** (FSL)
- Create `db/migration/V2__webhook_repo_rejection.sql`
- Create `attention/WebhookAttentionResource.java`
- Modify `registry/WebhookRepoRegistry.java` — `recordRejection` / `clearRejections` / rejection reads
- Modify `RegistryWebhookEdge.java` — record on four paths, clear after a successful publish

**`spire-ui`** (FSL)
- Create `hooks/useAttention.ts` — fetch both feeds, merge, sort, synthesize `GATEWAY_UNREACHABLE`
- Create `components/AttentionBell.tsx` — badge + popover
- Modify `api.ts`, `App.tsx`, `index.css`
- Modify `components/SettingsProviders.tsx`, `SettingsLlmProviders.tsx`, `SettingsContextProviders.tsx` — "last checked"

---

### Task 1: `AttentionView` + orchestrator config-gap and DLQ conditions

Delivers a working `GET /api/attention` reporting the four conditions that need no schema change.

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/attention/AttentionView.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionResource.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/dlq/DlqRepository.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java`

**Interfaces:**
- Produces: `dev.codespire.contract.attention.AttentionView(String code, AttentionView.Severity severity, String subject, String message, String action)`; `AttentionView.Severity` = `{BLOCKING, WARNING}`; `AttentionQueries.collect() → List<AttentionView>`; `DlqRepository.countPending() → int`.

- [ ] **Step 1: Write the failing test**

Create `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java`:

```java
package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every panel row is a query result, so the contract under test is "which codes does this
 * state produce". The empty-system case matters most: a condition that fires unconditionally
 * would still look correct in every other test.
 */
@QuarkusTest
class AttentionQueriesTest {

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    private Set<String> codes() {
        return queries.collect().stream().map(AttentionView::code).collect(java.util.stream.Collectors.toSet());
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }

    @BeforeEach
    void reset() {
        sql("DELETE FROM llm_provider");
        sql("DELETE FROM provider_author");
        sql("DELETE FROM scm_provider");
        sql("DELETE FROM dlq_entry");
        sql("DELETE FROM review_event");
        sql("DELETE FROM review_status");
    }

    /** An operator with nothing configured is blocked on both registries at once. */
    @Test
    void anEmptySystemReportsBothMissingProviderBlockers() {
        Set<String> found = codes();
        assertTrue(found.contains("LLM_PROVIDER_MISSING"), found.toString());
        assertTrue(found.contains("SCM_PROVIDER_MISSING"), found.toString());
        // Not this one: with no providers at all, "no default" is not the actionable problem.
        assertFalse(found.contains("LLM_DEFAULT_MISSING"), found.toString());
    }

    /** A fully configured system must produce NOTHING. This is the unconditional-firing guard. */
    @Test
    void aFullyConfiguredSystemReportsNothing() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertEquals(List.of(), queries.collect());
    }

    /** An enabled provider exists but nothing is marked default, so brokering cannot pick one. */
    @Test
    void enabledLlmProvidersWithNoDefaultReportTheMissingDefault() {
        insertLlmProvider("TEST-llm", true, false);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        Set<String> found = codes();
        assertTrue(found.contains("LLM_DEFAULT_MISSING"), found.toString());
        assertFalse(found.contains("LLM_PROVIDER_MISSING"), found.toString());
    }

    /** Disabling the default is as blocking as never setting one -- the resolver requires both. */
    @Test
    void aDisabledDefaultLlmProviderStillReportsTheMissingDefault() {
        insertLlmProvider("TEST-llm-off", false, true);
        insertLlmProvider("TEST-llm-on", true, false);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertTrue(codes().contains("LLM_DEFAULT_MISSING"), codes().toString());
    }

    /** The bot cannot recognise its own comments without an identity, so conversation breaks. */
    @Test
    void anScmProviderWithNoBotIdentityIsReportedByName() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-nameless", "", null);
        AttentionView row = queries.collect().stream()
                .filter(v -> "BOT_IDENTITY_UNRESOLVED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("TEST-nameless", row.subject());
        assertEquals(AttentionView.Severity.WARNING, row.severity());
    }

    /** Either identity field alone is enough -- only a provider with neither is unresolved. */
    @Test
    void anScmProviderWithOnlyAUsernameIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "", "test-bot");
        assertFalse(codes().contains("BOT_IDENTITY_UNRESOLVED"), codes().toString());
    }

    /** Dropped work must be visible; the row carries the count so one row covers any number. */
    @Test
    void pendingDeadLetterEntriesAreReportedWithTheirCount() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertDlqEntry("pending");
        insertDlqEntry("pending");
        insertDlqEntry("discarded");
        AttentionView row = queries.collect().stream()
                .filter(v -> "DLQ_PENDING".equals(v.code()))
                .findFirst().orElseThrow();
        assertTrue(row.message().contains("2"), row.message());
        assertEquals("/settings/dlq", row.action());
    }

    /** Blockers must sort ahead of warnings -- the operator reads the top of the list. */
    @Test
    void blockingRowsSortBeforeWarnings() {
        insertScmProvider("TEST-nameless", "", null);
        List<AttentionView> rows = queries.collect();
        int firstWarning = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).severity() == AttentionView.Severity.WARNING) {
                firstWarning = i;
                break;
            }
        }
        assertTrue(firstWarning > 0, "expected at least one blocker before the first warning");
        for (int i = firstWarning; i < rows.size(); i++) {
            assertEquals(AttentionView.Severity.WARNING, rows.get(i).severity());
        }
    }

    // ---- fixtures: obviously-synthetic values only --------------------------

    private void insertLlmProvider(String name, boolean enabled, boolean isDefault) {
        sql("INSERT INTO llm_provider (id, name, type, base_url, api_key, model, temperature, enabled, is_default) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + name + "', 'openai', "
                + "'https://llm.example.invalid', 'TEST-KEY', 'TEST-MODEL', 0.0, "
                + enabled + ", " + isDefault + ")");
    }

    private void insertScmProvider(String name, String botAccountId, String botUsername) {
        sql("INSERT INTO scm_provider (id, name, type, base_url, workspace, auth_kind, auth_secret, "
                + "bot_account_id, bot_username, enabled) VALUES ('" + UUID.randomUUID() + "', '" + name
                + "', 'stub', 'https://scm.example.invalid', 'TEST-WS', 'bearer', 'TEST-SECRET', '"
                + botAccountId + "', " + (botUsername == null ? "NULL" : "'" + botUsername + "'") + ", TRUE)");
    }

    private void insertDlqEntry(String status) {
        sql("INSERT INTO dlq_entry (id, kafka_key, message_type, original_topic, reason, payload, status) "
                + "VALUES ('" + UUID.randomUUID() + "', 'TEST-KEY', 'TEST-TYPE', 'cs.commands', "
                + "'TEST-REASON', '{}', '" + status + "')");
    }
}
```

> **If the `dlq_entry` insert fails on a column name,** read `spire-orchestrator/src/main/resources/db/migration/*dlq*.sql` and correct the column list. Do not change the assertion.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*AttentionQueriesTest*'
```

Expected: compilation failure — `package dev.codespire.contract.attention does not exist`.

- [ ] **Step 3: Create the contract type**

Create `spire-contract/src/main/java/dev/codespire/contract/attention/AttentionView.java`:

```java
package dev.codespire.contract.attention;

/**
 * One operator-facing condition that is true RIGHT NOW, derived on demand from state a
 * service already owns. Deliberately not a stored notification: there is no id, no
 * timestamp and no dismissal, because fixing the cause is what removes the row.
 *
 * <p>Shared by every service that reports conditions, so the UI can concatenate feeds from
 * more than one service without knowing which produced what.
 */
public record AttentionView(String code, Severity severity, String subject,
                            String message, String action) {

    /**
     * How badly the condition hurts. Two values on purpose — an operator triaging a bell
     * needs "nothing works" separated from "something is off", and finer grades would only
     * invite argument. Deliberately NOT named after review-finding severities
     * (BLOCKER/HIGH/...), which are a different vocabulary about defects in reviewed code.
     */
    public enum Severity {
        /** No review can complete until this is fixed. */
        BLOCKING,
        /** Some reviews are affected, or work has been dropped. */
        WARNING
    }

    /**
     * @param code     stable machine identifier; NEVER contains a provider name (ADR-020)
     * @param subject  what the condition is about — a provider name or repo target read
     *                 from the database, or null for a system-wide condition
     * @param message  one operator-facing sentence stating the consequence
     * @param action   a spire-ui route to the page that fixes it, or null
     */
    public AttentionView {
    }
}
```

- [ ] **Step 4: Add the DLQ count**

In `spire-orchestrator/src/main/java/dev/codespire/orchestrator/dlq/DlqRepository.java`, add after `list(boolean)`:

```java
    /** Pending entries only — a count, so the panel never loads payloads it will not show. */
    public int countPending() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM dlq_entry WHERE status = 'pending'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count pending dead-letter entries", e);
        }
    }
```

- [ ] **Step 5: Create `AttentionQueries`**

Create `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java`:

```java
package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.orchestrator.dlq.DlqRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every orchestrator-side condition, evaluated fresh on each call. Nothing is stored and
 * nothing is cached: the panel's whole correctness argument is that a row cannot outlive
 * the state that produced it.
 *
 * <p>Aggregate conditions (stuck reviews, failed reviews, dead-letter entries) emit ONE row
 * carrying a count rather than one row per record. A stalled broker produces dozens of stuck
 * reviews at once, and thirty rows saying the same thing is the failure this panel exists to
 * avoid.
 */
@ApplicationScoped
public class AttentionQueries {

    @Inject
    DataSource dataSource;

    @Inject
    DlqRepository dlq;

    /** Blockers first, then warnings; stable by code within a severity. */
    public List<AttentionView> collect() {
        List<AttentionView> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            llmProviderRows(c, rows);
            scmProviderRows(c, rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to evaluate attention conditions", e);
        }
        deadLetterRows(rows);
        rows.sort(Comparator.comparing((AttentionView v) -> v.severity().ordinal())
                .thenComparing(AttentionView::code)
                .thenComparing(v -> v.subject() == null ? "" : v.subject()));
        return rows;
    }

    private void llmProviderRows(Connection c, List<AttentionView> rows) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE") == 0) {
            rows.add(new AttentionView("LLM_PROVIDER_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is configured, so no review can run.", "/settings/llm"));
            return; // "no default" is not the actionable problem when there is nothing to default to
        }
        // Mirrors the real gate in LlmProviderRegistry.resolveDefault(): a default that has been
        // disabled resolves to nothing, so it is exactly as blocking as never setting one.
        if (count(c, "SELECT COUNT(*) FROM llm_provider WHERE enabled = TRUE AND is_default = TRUE") == 0) {
            rows.add(new AttentionView("LLM_DEFAULT_MISSING", Severity.BLOCKING, null,
                    "No enabled LLM provider is marked as the default, so no review can run.",
                    "/settings/llm"));
        }
    }

    private void scmProviderRows(Connection c, List<AttentionView> rows) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM scm_provider WHERE enabled = TRUE") == 0) {
            rows.add(new AttentionView("SCM_PROVIDER_MISSING", Severity.BLOCKING, null,
                    "No enabled source-control provider is configured, so no pull request can be reviewed.",
                    "/settings/providers"));
        }
        // bot_account_id is NOT NULL DEFAULT '' while bot_username is a nullable TEXT, so both
        // blank forms have to be tested. Either field alone is enough to identify the bot.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT name FROM scm_provider
                 WHERE enabled = TRUE
                   AND (bot_account_id IS NULL OR bot_account_id = '')
                   AND (bot_username   IS NULL OR bot_username   = '')
                 ORDER BY name
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new AttentionView("BOT_IDENTITY_UNRESOLVED", Severity.WARNING, rs.getString("name"),
                        "The bot's own identity could not be resolved, so it cannot recognise its own "
                                + "comments and will not hold a conversation.", "/settings/providers"));
            }
        }
    }

    private void deadLetterRows(List<AttentionView> rows) {
        int pending = dlq.countPending();
        if (pending > 0) {
            rows.add(new AttentionView("DLQ_PENDING", Severity.WARNING, null,
                    pending + " message(s) failed processing and are waiting in the dead-letter queue.",
                    "/settings/dlq"));
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
```

- [ ] **Step 6: Create `AttentionResource`**

Create `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionResource.java`:

```java
package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Conditions the operator should act on, for the spire-ui attention bell. */
@Path("/api/attention")
@Produces(MediaType.APPLICATION_JSON)
public class AttentionResource {

    @Inject
    AttentionQueries queries;

    @GET
    public List<AttentionView> list() {
        return queries.collect();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*AttentionQueriesTest*'
```

Expected: 8 tests pass.

- [ ] **Step 8: Verify the endpoint serves and neutrality holds**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-arch:test :spire-contract:test
```

Expected: BUILD SUCCESSFUL. The `spire-arch` provider-neutrality check must pass **without** a new allowlist entry.

- [ ] **Step 9: Commit**

```bash
git add spire-contract/src/main/java/dev/codespire/contract/attention/ \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/ \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/dlq/DlqRepository.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/
git commit -m "Report configuration gaps that stop reviews running

Adds an on-demand conditions endpoint: an operator can see that no LLM
provider is usable, no SCM provider is registered, a bot identity never
resolved, or work is sitting in the dead-letter queue. Every row is a query
result rather than a stored notification, so fixing the cause removes it and
there is nothing to dismiss or expire."
```

---

### Task 2: Pipeline-health conditions

A stuck or failed review is the only honest signal for a broken delivery path — absence of inbound webhooks is indistinguishable from a quiet afternoon.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java`
- Modify: `spire-orchestrator/src/main/resources/application.properties`
- Modify: `.env.example`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java`

**Interfaces:**
- Consumes: `AttentionQueries.collect()`, `AttentionView` (Task 1).
- Produces: codes `REVIEW_STUCK`, `REVIEW_FAILED`; config `spire.attention.stuck-minutes` (default 15), `spire.attention.failed-window-hours` (default 24).

- [ ] **Step 1: Write the failing tests**

Append to `AttentionQueriesTest`, and add the fixture helper at the end of the fixtures section:

```java
    /** A review that has not moved is the closest honest signal that deliveries stopped arriving. */
    @Test
    void aReviewStuckPastTheThresholdIsReportedWithItsCount() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "REVIEWING", "OPEN", "2 hours");
        insertReview("TEST-r2", "IDLE", "OPEN", "2 hours");
        AttentionView row = queries.collect().stream()
                .filter(v -> "REVIEW_STUCK".equals(v.code()))
                .findFirst().orElseThrow();
        assertTrue(row.message().contains("2"), row.message());
        assertEquals("/", row.action());
    }

    /** A review that is merely young is not stuck. */
    @Test
    void aRecentInProgressReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "REVIEWING", "OPEN", "1 minute");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** Cancel-on-close should have ended it; alerting about a merged PR is how a panel becomes noise. */
    @Test
    void aStuckReviewOnAClosedPrIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "REVIEWING", "MERGED", "2 hours");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** A terminal review is not stuck, however old. */
    @Test
    void anOldCompletedReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "COMPLETED", "OPEN", "30 days");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** Recent failures are actionable. */
    @Test
    void aRecentlyFailedReviewIsReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "FAILED", "OPEN", "1 hour");
        assertTrue(codes().contains("REVIEW_FAILED"), codes().toString());
    }

    /**
     * With no dismiss button anywhere in this design, an unwindowed failure row would nag
     * forever. The window is what makes it self-clearing.
     */
    @Test
    void aFailureOlderThanTheWindowIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "FAILED", "OPEN", "30 days");
        assertFalse(codes().contains("REVIEW_FAILED"), codes().toString());
    }
```

Fixture helper (add beside the other fixtures):

```java
    /** {@code age} is a Postgres interval literal, e.g. "2 hours". */
    private void insertReview(String reviewId, String status, String prState, String age) {
        sql("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, pr_state, "
                + "created_at, updated_at) VALUES ('" + reviewId + "', 'TEST-WS', 'TEST-REPO', 1, '"
                + status + "', '" + prState + "', now() - interval '" + age + "', "
                + "now() - interval '" + age + "')");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*AttentionQueriesTest*'
```

Expected: 4 failures (`NoSuchElementException` on the two count assertions, and the two `assertTrue` checks) — the new codes are never produced. The three `assertFalse` cases pass vacuously; that is expected and is exactly why they are paired with positive cases.

- [ ] **Step 3: Add the config properties**

In `spire-orchestrator/src/main/resources/application.properties`, add:

```properties
# Attention panel thresholds. A review normally finishes in seconds; 15 minutes without
# movement means the delivery path or a worker is broken. The failure window keeps a row
# self-clearing, since the panel has no dismiss action.
spire.attention.stuck-minutes=15
spire.attention.failed-window-hours=24
```

In `.env.example`, add:

```bash
# Attention panel (optional; shown defaults apply when unset)
SPIRE_ATTENTION_STUCK_MINUTES=15
SPIRE_ATTENTION_FAILED_WINDOW_HOURS=24
```

- [ ] **Step 4: Add the conditions**

In `AttentionQueries`, add the two injected properties below the existing `@Inject` fields:

```java
    @ConfigProperty(name = "spire.attention.stuck-minutes")
    int stuckMinutes;

    @ConfigProperty(name = "spire.attention.failed-window-hours")
    int failedWindowHours;
```

with `import org.eclipse.microprofile.config.inject.ConfigProperty;`.

Call `reviewRows(c, rows);` inside the `try` block of `collect()`, after `scmProviderRows(c, rows);`, and add:

```java
    /**
     * Non-terminal reviews that have stopped moving, and recent terminal failures.
     *
     * <p>Restricted to {@code pr_state = 'OPEN'}: cancel-on-close should already have ended a
     * review whose PR was merged or closed, and a row about yesterday's merged PR is not
     * actionable. Both rows are aggregates carrying a count — one stalled broker should not
     * produce thirty identical rows.
     */
    private void reviewRows(Connection c, List<AttentionView> rows) throws SQLException {
        int stuck = countWithInt(c, """
                SELECT COUNT(*) FROM review_status
                 WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                   AND pr_state = 'OPEN'
                   AND updated_at < now() - make_interval(mins => ?)
                """, stuckMinutes);
        if (stuck > 0) {
            rows.add(new AttentionView("REVIEW_STUCK", Severity.WARNING, null,
                    stuck + " review(s) have not progressed for over " + stuckMinutes
                            + " minutes — a webhook delivery path or a worker may be down.", "/"));
        }
        int failed = countWithInt(c, """
                SELECT COUNT(*) FROM review_status
                 WHERE status = 'FAILED'
                   AND updated_at > now() - make_interval(hours => ?)
                """, failedWindowHours);
        if (failed > 0) {
            rows.add(new AttentionView("REVIEW_FAILED", Severity.WARNING, null,
                    failed + " review(s) failed in the last " + failedWindowHours + " hours.", "/"));
        }
    }

    private static int countWithInt(Connection c, String sql, int arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*AttentionQueriesTest*'
```

Expected: 14 tests pass.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java \
        spire-orchestrator/src/main/resources/application.properties \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java \
        .env.example
git commit -m "Surface reviews that stalled or failed

A dead webhook tunnel produces no event to report, only silence, and the
symptom is identical to a bot that chose not to reply. A review that has not
moved for fifteen minutes is the closest honest signal, derived from our own
state instead of guessing at a heartbeat.

Failures are windowed to twenty-four hours: the panel has no dismiss action,
so an unwindowed row would nag about a failure from weeks ago."
```

---

### Task 3: Credential-check persistence (`V28`) and `CREDENTIAL_REJECTED`

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V28__provider_credential_check.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderRegistry.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderRegistry.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextProviderRegistry.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextProviderView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextProviderResource.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java`

**Interfaces:**
- Produces: `ProviderRegistry.recordCheck(UUID id, boolean ok, String detail)`, `LlmProviderRegistry.recordCheck(UUID, boolean, String)`, `ContextProviderRegistry.recordCheck(UUID, boolean, String)`; three new trailing fields on each `*View`: `Instant lastCheckAt, Boolean lastCheckOk, String lastCheckError`; code `CREDENTIAL_REJECTED`.

- [ ] **Step 1: Write the failing test**

Append to `AttentionQueriesTest`:

```java
    /** A credential the provider refused is the case that started this feature. */
    @Test
    void aRejectedScmCredentialIsReportedByProviderName() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET last_check_at = now(), last_check_ok = FALSE, "
                + "last_check_error = 'Authentication rejected (HTTP 401)' WHERE name = 'TEST-scm'");
        AttentionView row = queries.collect().stream()
                .filter(v -> "CREDENTIAL_REJECTED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("TEST-scm", row.subject());
        assertEquals("/settings/providers", row.action());
        assertTrue(row.message().contains("401"), row.message());
    }

    /** A rejected LLM key routes the operator to the LLM page, not the SCM page. */
    @Test
    void aRejectedLlmCredentialLinksToTheLlmSettingsPage() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE llm_provider SET last_check_at = now(), last_check_ok = FALSE, "
                + "last_check_error = 'The LLM provider rejected the API key' WHERE name = 'TEST-llm'");
        AttentionView row = queries.collect().stream()
                .filter(v -> "CREDENTIAL_REJECTED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("/settings/llm", row.action());
    }

    /**
     * NULL means never checked, which is not a problem. Only an explicit FALSE raises a row —
     * otherwise every provider whose Check button was never pressed would nag forever.
     */
    @Test
    void anUncheckedCredentialIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }

    /** A passing check clears the row; there is no separate clear action. */
    @Test
    void aPassingCheckClearsTheRejection() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET last_check_ok = FALSE WHERE name = 'TEST-scm'");
        assertTrue(codes().contains("CREDENTIAL_REJECTED"));
        sql("UPDATE scm_provider SET last_check_ok = TRUE WHERE name = 'TEST-scm'");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }

    /** A disabled provider cannot break a review, so its dead credential is not actionable. */
    @Test
    void aDisabledProvidersRejectedCredentialIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET enabled = FALSE, last_check_ok = FALSE WHERE name = 'TEST-scm'");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }
```

Also add a `recordCheck` round-trip test — create `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderCheckRecordTest.java`:

```java
package dev.codespire.orchestrator.provider;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outcome has to survive a round trip, since the panel reads it back on every poll. */
@QuarkusTest
class ProviderCheckRecordTest {

    @Inject
    ProviderRegistry registry;

    private ProviderView created() {
        return registry.create(new ProviderInput("TEST-provider", "stub", "https://scm.example.invalid",
                "TEST-WS-" + UUID.randomUUID(), "bearer", null, "TEST-SECRET", "acct-1", true,
                List.of(), "test-bot", null));
    }

    @Test
    void aNewProviderHasNeverBeenChecked() {
        ProviderView view = created();
        assertNull(view.lastCheckAt());
        assertNull(view.lastCheckOk());
        assertNull(view.lastCheckError());
    }

    @Test
    void aFailedCheckIsStoredWithItsDetail() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertNotNull(reread.lastCheckAt());
        assertFalse(reread.lastCheckOk());
        assertEquals("Authentication rejected (HTTP 401)", reread.lastCheckError());
    }

    /** Success must null the stored error, or a stale message outlives the failure it described. */
    @Test
    void aPassingCheckClearsThePreviousError() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        registry.recordCheck(UUID.fromString(view.id()), true, null);
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertTrue(reread.lastCheckOk());
        assertNull(reread.lastCheckError());
    }
}
```

> **`ProviderInput`'s constructor arity must match the existing record.** Confirm it against `ProviderRegistryTest.java:30-31`, which builds one with 12 arguments; copy that call shape exactly.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*AttentionQueriesTest*' --tests '*ProviderCheckRecordTest*'
```

Expected: compilation failure — `cannot find symbol: method lastCheckAt()`.

- [ ] **Step 3: Create the migration**

Create `spire-orchestrator/src/main/resources/db/migration/V28__provider_credential_check.sql`:

```sql
-- Last credential-verification outcome per provider, so the attention panel can report a
-- credential the provider refused. Written by the Check endpoints, by provider save (which
-- already probes to resolve bot identity), and by the pipeline when a real review is
-- rejected with a 401.
--
-- All three columns are nullable with no backfill: existing rows are genuinely unchecked and
-- NULL says so. last_check_ok is deliberately three-valued -- NULL never checked, TRUE
-- passed, FALSE rejected -- so "unchecked" can never be mistaken for "failing". Only an
-- explicit FALSE raises a panel row.

ALTER TABLE scm_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;

ALTER TABLE llm_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;

ALTER TABLE context_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;
```

- [ ] **Step 4: Extend the three `*View` records**

Append these three components to each of `ProviderView`, `LlmProviderView` and `ContextProviderView`, after their current last component (adding a trailing comma to it):

```java
        Instant lastCheckAt,
        Boolean lastCheckOk,
        String lastCheckError) {
```

`Boolean` (boxed) is required — `null` means never checked and must stay distinguishable from `false`. `Instant` is already imported in all three.

- [ ] **Step 5: Add `recordCheck` and read the columns in all three registries**

In each of `ProviderRegistry`, `LlmProviderRegistry` and `ContextProviderRegistry`, add (substituting the table name `scm_provider` / `llm_provider` / `context_provider`):

```java
    /**
     * Record the outcome of verifying this provider's credential. A passing check nulls the
     * stored error, so a stale message never outlives the failure it described.
     *
     * @param detail a safe, non-echoing reason on failure; null on success
     */
    @Transactional
    public void recordCheck(UUID id, boolean ok, String detail) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE scm_provider SET last_check_at = now(), last_check_ok = ?, "
                             + "last_check_error = ? WHERE id = ?")) {
            ps.setBoolean(1, ok);
            ps.setString(2, ok ? null : detail);
            ps.setObject(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the credential check for " + id, e);
        }
    }
```

Then extend each registry's `toView(ResultSet)` to pass the three new values as the trailing arguments:

```java
                rs.getTimestamp("last_check_at") == null
                        ? null : rs.getTimestamp("last_check_at").toInstant(),
                rs.getObject("last_check_ok", Boolean.class),
                rs.getString("last_check_error"));
```

`rs.getObject(name, Boolean.class)` is required — `rs.getBoolean` collapses SQL `NULL` to `false`, which would report every unchecked provider as rejected.

- [ ] **Step 6: Persist outcomes from the two existing check endpoints**

In `ProviderResource.check` (currently `ProviderResource.java:123-136`), record before returning:

```java
    public CheckResult check(@PathParam("id") String id) {
        ScmProvider provider = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
        try {
            Author owner = identity.resolveForCheck(provider);
            registry.recordCheck(provider.id(), true, null);
            return new CheckResult(true, owner.username(), null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Provider connectivity check failed for %s (type %s)", id, provider.type());
            String detail = reason(e);
            registry.recordCheck(provider.id(), false, detail);
            return new CheckResult(false, null, detail);
        }
    }
```

In `ContextProviderResource.check` (currently `:104-119`), record both branches the same way, storing the `detail` it already computes.

- [ ] **Step 7: Record the outcome on provider save**

Saving an SCM provider already probes the credential — `resolveForRegistration` calls the SCM to
resolve bot identity, and throws `BadRequestException` when the token is refused. Discarding that
outcome would leave a provider reading "never checked" seconds after it was demonstrably verified.

A `create` cannot record before its row exists, so record after the insert returns. In
`ProviderResource.create`, where it currently returns `registry.create(resolved)`:

```java
        ProviderView created = registry.create(resolve(in));
        // resolve(...) just proved the token works by resolving the bot's identity with it.
        registry.recordCheck(UUID.fromString(created.id()), true, null);
        return Response.status(Response.Status.CREATED).entity(created).build();
```

In `ProviderResource.update`, record against the id already in hand after a successful resolve:

```java
        ProviderView updated = registry.update(uuid(id), resolve(in))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
        registry.recordCheck(uuid(id), true, null);
        return updated;
```

Only the success path is recorded here: a refused token makes the save itself fail with a 400, so
there is no provider row to attach a failure to on create, and on update the stored credential is
unchanged. Match the surrounding method's actual variable names and return shape — the resolve
helper may be named differently.

- [ ] **Step 8: Add the `CREDENTIAL_REJECTED` condition**

In `AttentionQueries`, call `credentialRows(c, rows);` inside `collect()`'s `try` block after `reviewRows(c, rows);`, and add:

```java
    /**
     * A credential the provider refused, across all three registries. Only an explicit FALSE
     * qualifies: NULL means never checked, which is not a problem and would otherwise nag for
     * every provider whose Check button was never pressed. Disabled providers are excluded —
     * they cannot break a review.
     */
    private void credentialRows(Connection c, List<AttentionView> rows) throws SQLException {
        credentialRows(c, rows, "scm_provider", "/settings/providers", "source-control provider");
        credentialRows(c, rows, "llm_provider", "/settings/llm", "LLM provider");
        credentialRows(c, rows, "context_provider", "/settings/context", "context provider");
    }

    private void credentialRows(Connection c, List<AttentionView> rows, String table,
                                String action, String kind) throws SQLException {
        // The table name is a compile-time constant from the private call sites above, never
        // caller input, so it cannot carry injection.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, last_check_error FROM " + table
                        + " WHERE enabled = TRUE AND last_check_ok = FALSE ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String detail = rs.getString("last_check_error");
                rows.add(new AttentionView("CREDENTIAL_REJECTED", Severity.WARNING, rs.getString("name"),
                        "The " + kind + "'s credential was rejected"
                                + (detail == null || detail.isBlank() ? "." : ": " + detail),
                        action));
            }
        }
    }
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test
```

Expected: BUILD SUCCESSFUL. Existing `*View` constructor call sites in tests may need the three trailing `null`s — update them.

- [ ] **Step 10: Commit**

```bash
git add spire-orchestrator/src/main/resources/db/migration/V28__provider_credential_check.sql \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/ \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/
git commit -m "Remember whether a provider's credential was accepted

The Check endpoints and provider save already probe the credential, but threw
the answer away, so a token that had gone dead was invisible until a review
failed. The outcome is now stored per provider and raises a panel row.

last_check_ok is three-valued on purpose: NULL never checked, TRUE passed,
FALSE rejected. Only an explicit FALSE raises a row, so a provider nobody has
checked yet stays quiet instead of nagging permanently."
```

---

### Task 4: LLM provider check endpoint

`LlmKeyValidator` already probes `GET {baseUrl}/models` on save, but throws instead of reporting, and there is no way to re-check a stored key. Without this, an LLM credential can never be verified after creation.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmKeyValidator.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderRegistry.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmProviderResource.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmProviderCheckTest.java`

**Interfaces:**
- Consumes: `LlmProviderRegistry.recordCheck(UUID, boolean, String)` (Task 3).
- Produces: `LlmKeyValidator.CheckOutcome(boolean ok, int status, String detail)`; `LlmKeyValidator.check(String type, String baseUrl, String apiKey) → CheckOutcome`; `LlmProviderRegistry.resolveById(UUID) → Optional<LlmProviderConfig>`; `POST /api/llm-providers/{id}/check → LlmProviderResource.CheckResult(boolean ok, String detail)`.

- [ ] **Step 1: Write the failing test**

Create `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmProviderCheckTest.java`:

```java
package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check is the ONLY way an LLM credential is ever verified after creation: the pipeline's
 * credential signal rides on ScmApiException, which the LLM adapter does not raise.
 */
@QuarkusTest
class LlmProviderCheckTest {

    @Inject
    LlmProviderResource resource;

    @Inject
    LlmProviderRegistry registry;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private String createProvider() {
        LlmProviderView view = registry.create(new LlmProviderInput("TEST-llm-" + UUID.randomUUID(),
                "openai", server.baseUrl(), "TEST-KEY", "TEST-MODEL", 0.0, null, true, false));
        return view.id();
    }

    @Test
    void anAcceptedKeyRecordsAPassingCheck() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));
        String id = createProvider();

        LlmProviderResource.CheckResult result = resource.check(id);

        assertTrue(result.ok());
        assertTrue(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk());
    }

    /** A rejected key must come back as a RESULT, not a 400 — the panel needs to store it. */
    @Test
    void aRejectedKeyRecordsAFailingCheckWithoutThrowing() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok());
        assertNotNull(result.detail());
        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertFalse(reread.lastCheckOk());
        assertNotNull(reread.lastCheckError());
    }

    /** An unreachable provider is not the same as a bad key, and must not be silent. */
    @Test
    void anUnreachableProviderRecordsAFailingCheck() {
        String id = createProvider();
        server.stop(); // nothing is listening now

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok());
        assertFalse(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk());
    }

    @Test
    void aPassingCheckClearsAPreviousRejection() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();
        resource.check(id);
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));

        resource.check(id);

        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertTrue(reread.lastCheckOk());
        assertEquals(null, reread.lastCheckError());
    }
}
```

> Two things to confirm against the current code, adjusting the test's construction only (never its assertions): `LlmProviderInput`'s component order and arity, and that `%test` sets `spire.security.allow-insecure-provider-urls=true` so a WireMock `http://localhost:PORT` base URL survives the SSRF guard. If it does not, add it to `src/test/resources/application.properties`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*LlmProviderCheckTest*'
```

Expected: compilation failure — `cannot find symbol: method check(String)`.

- [ ] **Step 3: Add the non-throwing outcome to `LlmKeyValidator`**

Add to `LlmKeyValidator`, keeping `ping` exactly as it is for the save path:

```java
    /**
     * Outcome of a re-check, mirroring {@link dev.codespire.orchestrator.context.ContextKeyValidator.CheckOutcome}.
     * {@code status} is 0 when the provider could not be reached at all.
     */
    public record CheckOutcome(boolean ok, int status, String detail) {
    }

    /**
     * Re-check a stored key and REPORT the outcome instead of throwing, so the attention panel
     * can persist a rejection rather than surfacing it as a failed request.
     *
     * <p>401 and 403 both mean "key rejected" here. That is deliberately unlike
     * {@code ScmApiException.isUnauthorized()}, which is 401-only because at least one SCM
     * answers 403 for rate limiting; the LLM vendors in {@code TYPES} signal throttling with
     * 429, so 403 is unambiguous on this side. Do not "harmonise" the two.
     */
    public CheckOutcome check(String type, String baseUrl, String apiKey) {
        HttpRequest request;
        try {
            request = authenticated(type, baseUrl, apiKey);
        } catch (BadRequestException e) {
            return new CheckOutcome(false, 0, e.getMessage());
        }
        int status;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            LOG.warnf(e, "LLM key check call failed for host %s", request.uri().getHost());
            return new CheckOutcome(false, 0, "Could not reach the LLM provider.");
        }
        if (status == 401 || status == 403) {
            return new CheckOutcome(false, status,
                    "The LLM provider rejected the API key (HTTP " + status + ").");
        }
        if (status / 100 != 2) {
            return new CheckOutcome(false, status,
                    "The LLM provider returned an unexpected status (" + status + ").");
        }
        return new CheckOutcome(true, status, null);
    }
```

Extract the shared request build so `ping` and `check` cannot drift apart. Replace `ping`'s body with:

```java
    /** @throws BadRequestException with a generic message if the key/model is rejected or unreachable. */
    public void ping(String type, String baseUrl, String apiKey) {
        HttpRequest request = authenticated(type, baseUrl, apiKey);
        interpret(send(request, request.uri()));
    }

    /** The per-type authenticated {@code /models} request. Key travels in a header, never the URL. */
    private HttpRequest authenticated(String type, String baseUrl, String apiKey) {
        URI uri = URI.create(trimTrailingSlash(baseUrl) + "/models");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        switch (type == null ? "" : type) {
            case "openai" -> req.header("Authorization", "Bearer " + apiKey);
            case "anthropic" -> req.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            case "gemini" -> req.header("x-goog-api-key", apiKey);
            default -> throw new BadRequestException("Unsupported LLM provider type '" + type + "'");
        }
        return req.build();
    }
```

- [ ] **Step 4: Add `resolveById` to `LlmProviderRegistry`**

`LlmProviderRegistry` has only `resolveDefault()`; the check needs one provider's decrypted key. Add beside it:

```java
    /** One provider with its key decrypted, for a re-check. Empty when unknown. */
    public Optional<LlmProviderConfig> resolveById(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decrypted(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve LLM provider " + id, e);
        }
    }
```

- [ ] **Step 5: Add the endpoint**

In `LlmProviderResource`, add after `makeDefault`:

```java
    /**
     * Live key check against the provider's stored credential, mirroring the SCM and context
     * providers' Check buttons. Records the outcome so the attention panel can report a key the
     * provider has started refusing. Never returns the key; only a safe category of the failure.
     */
    @POST
    @Path("/{id}/check")
    @Consumes(MediaType.WILDCARD) // no request body — don't require a JSON content type
    public CheckResult check(@PathParam("id") String id) {
        LlmProviderConfig config = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No LLM provider " + id));
        LlmKeyValidator.CheckOutcome outcome =
                validator.check(config.type(), config.baseUrl(), config.apiKey());
        registry.recordCheck(config.id(), outcome.ok(), outcome.detail());
        if (!outcome.ok()) {
            LOG.warnf("LLM provider %s (%s) key check failed: %s", id, config.type(), outcome.detail());
        }
        return new CheckResult(outcome.ok(), outcome.detail());
    }

    /** Result of {@link #check}: a safe {@code detail} on failure, null on success. */
    public record CheckResult(boolean ok, String detail) {
    }
```

Add a logger field if the class has none:

```java
    private static final Logger LOG = Logger.getLogger(LlmProviderResource.class);
```

with `import org.jboss.logging.Logger;`.

- [ ] **Step 6: Run the test to verify it passes**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*LlmProviderCheckTest*'
```

Expected: 4 tests pass.

- [ ] **Step 7: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/
git commit -m "Let an LLM provider's key be re-checked after saving

The key was probed on save and never again, so a rotated or expired key stayed
invisible. SCM and context providers already had a Check button; this is the
missing third one, and it matters more than the others because the pipeline's
credential signal rides on ScmApiException, which the LLM adapter never raises.

The existing save-time ping is kept and both paths now share one request
builder, so the header set per provider type cannot drift between them."
```

---

### Task 5: `isUnauthorized()` and the `credentialRejected` signal

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/scm/ScmApiException.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/DiffWorker.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/scm/ScmApiExceptionTest.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/DiffWorkerTest.java`

**Interfaces:**
- Produces: `ScmApiException.isUnauthorized()` (defaulted, `status() == 401`); `ReviewFailed(String reviewId, String commit, String phase, String error, boolean retryable, int attempt, boolean credentialRejected)` with a 6-arg convenience constructor defaulting the flag to `false`.

- [ ] **Step 1: Write the failing contract test**

Create `spire-contract/src/test/java/dev/codespire/contract/scm/ScmApiExceptionTest.java`:

```java
package dev.codespire.contract.scm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 403 case is the whole reason this lives on the interface rather than as a raw status
 * compare at each call site: at least one SCM answers 403 for rate limiting as well as for
 * permission denial, so treating 403 as a dead credential would report a throttled repo as a
 * broken token and send the operator to rotate a key that was fine.
 */
class ScmApiExceptionTest {

    private static ScmApiException withStatus(int status) {
        return () -> status;
    }

    @Test
    void a401IsAnUnauthorizedCredential() {
        assertTrue(withStatus(401).isUnauthorized());
    }

    @Test
    void a403IsNotTreatedAsAnUnauthorizedCredential() {
        assertFalse(withStatus(403).isUnauthorized());
    }

    @Test
    void otherStatusesAreNotUnauthorized() {
        assertFalse(withStatus(404).isUnauthorized());
        assertFalse(withStatus(429).isUnauthorized());
        assertFalse(withStatus(500).isUnauthorized());
    }

    /** An adapter that can tell its own 403s apart is free to widen the answer. */
    @Test
    void anAdapterMayWidenTheAnswer() {
        ScmApiException widened = new ScmApiException() {
            @Override
            public int status() {
                return 403;
            }

            @Override
            public boolean isUnauthorized() {
                return true;
            }
        };
        assertTrue(widened.isUnauthorized());
    }
}
```

- [ ] **Step 2: Write the failing worker test**

Append to `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/DiffWorkerTest.java`:

```java
    /**
     * A real review rejected with a 401 is stronger evidence than any synthetic probe: it is
     * the credential actually failing at the work it exists to do.
     */
    @Test
    void a401WhileFetchingTheDiffMarksTheCredentialAsRejected() {
        // Arrange the same way the existing retryable test does, but with a 401 failure.
        List<IntegrationEvent> emitted = runDiffFetchFailing(401);

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getFirst());
        assertTrue(failed.credentialRejected());
        assertFalse(failed.retryable(), "a rejected credential cannot be fixed by retrying");
    }

    /** Everything else must leave the credential's standing alone. */
    @Test
    void a500WhileFetchingTheDiffDoesNotMarkTheCredential() {
        List<IntegrationEvent> emitted = runDiffFetchFailing(500);

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getFirst());
        assertFalse(failed.credentialRejected());
    }
```

> Implement `runDiffFetchFailing(int status)` as a private helper in this test class by extracting the arrangement already used by the existing test at `DiffWorkerTest.java:120` (the one asserting `failed.retryable()`), parameterised by the status its stub `DiffSource` throws. Reuse that class's existing fakes — do not introduce new ones.

- [ ] **Step 3: Run both tests to verify they fail**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-contract:test --tests '*ScmApiExceptionTest*'
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-review-worker:test --tests '*DiffWorkerTest*'
```

Expected: `cannot find symbol: method isUnauthorized()` and `cannot find symbol: method credentialRejected()`.

- [ ] **Step 4: Add `isUnauthorized()`**

In `ScmApiException`, after `isRateLimited()`:

```java
    /**
     * The provider refused our credential outright — terminal until an operator rotates it,
     * and worth telling the operator about rather than burying in a failed review.
     *
     * <p>Deliberately 401-only by default. At least one provider answers 403 for rate limiting
     * as well as for permission denial, so treating 403 as a dead credential would report a
     * throttled repo as a broken token. An adapter that can distinguish its own 403s overrides.
     */
    default boolean isUnauthorized() {
        return status() == 401;
    }
```

- [ ] **Step 5: Extend `ReviewFailed`**

In `IntegrationEvent`, replace the `ReviewFailed` declaration:

```java
    /**
     * @param credentialRejected the provider refused the credential (not a transient fault), so
     *                           the orchestrator can flag it for the operator instead of leaving
     *                           a dead token to be inferred from repeated failures
     */
    record ReviewFailed(String reviewId, String commit, String phase, String error,
                        boolean retryable, int attempt, boolean credentialRejected)
            implements IntegrationEvent {

        /** Call sites and in-flight records from before the credential signal existed. */
        public ReviewFailed(String reviewId, String commit, String phase, String error,
                            boolean retryable, int attempt) {
            this(reviewId, commit, phase, error, retryable, attempt, false);
        }
    }
```

- [ ] **Step 6: Set the flag in `DiffWorker`**

`DiffWorker` classifies with a direct `instanceof` (no cause chain — its failures arrive unwrapped). At `DiffWorker.java:83-87`, keep that idiom:

```java
        boolean retryable = e instanceof ScmApiException api
                && (api.status() >= 500 || api.isRateLimited());
        boolean credentialRejected = e instanceof ScmApiException api && api.isUnauthorized();
        results.emit(new ReviewFailed(command.reviewId(), command.commit(), "fetch-diff",
                e.getMessage(), retryable, 1, credentialRejected));
```

Leave the `isDiffTooLarge` emit at `:75` on the 6-arg constructor — an oversize diff says nothing about the credential.

- [ ] **Step 7: Set the flag in `ReviewWorker`**

`ReviewWorker.isRetryable` walks the cause chain because LangChain4j wraps. Mirror that walk rather than adding a direct check, and add beside it:

```java
    /**
     * The provider refused the credential. Walks the cause chain for the same reason
     * {@link #isRetryable} does: an adapter failure can arrive wrapped by the LLM client.
     */
    private static boolean isCredentialRejected(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && api.isUnauthorized()) {
                return true;
            }
        }
        return false;
    }
```

Then at all three `ReviewFailed` emit sites (`:175`, `:590`, `:845`), add the argument:

```java
                    cause.getMessage(), isRetryable(cause), command.attempt(), isCredentialRejected(cause)));
```

using the attempt value each site already passes (`command.attempt()` at `:175`, `1` at `:590` and `:845`).

- [ ] **Step 8: Run the tests to verify they pass**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-contract:test :spire-review-worker:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add spire-contract/src/main/java/dev/codespire/contract/scm/ScmApiException.java \
        spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java \
        spire-contract/src/test/java/dev/codespire/contract/scm/ScmApiExceptionTest.java \
        spire-review-worker/src/
git commit -m "Distinguish a refused credential from a transient failure

A review that dies on a 401 is the strongest possible evidence that a token is
dead, but the failure event could not say so, so the orchestrator had to infer
it from an error string. ReviewFailed now carries the classification, and the
question is asked through a neutral method on ScmApiException.

The default is 401-only. At least one provider answers 403 for rate limiting as
well as permission denial, so widening it would report a throttled repository
as a broken token; an adapter that can tell its own 403s apart may override."
```

---

### Task 6: `ResultSaga` records a pipeline-observed rejection

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ResultSagaCredentialTest.java`

**Interfaces:**
- Consumes: `ReviewFailed.credentialRejected()` (Task 5), `ProviderRegistry.recordCheck(UUID, boolean, String)` (Task 3), `ReviewProviderResolver.resolveForReview(String) → Optional<ScmProvider>`.

- [ ] **Step 1: Write the failing test**

Create `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ResultSagaCredentialTest.java`:

```java
package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Turning a failed review into a standing "this credential is dead" fact — the only path by
 * which real work teaches the panel that a token stopped working.
 */
class ResultSagaCredentialTest {

    private static final String REVIEW_ID = "stub:TEST-WS/TEST-REPO:1:abc";

    /** Recorded against the review's OWN provider, resolved by its stored provider type. */
    @Test
    void aCredentialRejectedFailureMarksTheReviewsProvider() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry);

        saga.onResult(new ReviewFailed(REVIEW_ID, "abc", "fetch-diff", "boom", false, 1, true));

        assertEquals(1, registry.calls);
        assertFalse(registry.lastOk);
        // Never the provider's own response body: a 401 body may echo the token back.
        assertEquals("Authentication rejected (HTTP 401)", registry.lastDetail);
    }

    /** An ordinary failure must leave the credential's standing untouched. */
    @Test
    void anOrdinaryFailureRecordsNothing() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry);

        saga.onResult(new ReviewFailed(REVIEW_ID, "abc", "generate", "boom", true, 1, false));

        assertEquals(0, registry.calls);
    }

    /** A review whose provider cannot be resolved must not blow up the result path. */
    @Test
    void anUnresolvableProviderIsSkippedQuietly() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry);
        unresolvable(saga);

        saga.onResult(new ReviewFailed(REVIEW_ID, "abc", "fetch-diff", "boom", false, 1, true));

        assertEquals(0, registry.calls);
        assertNull(registry.lastDetail);
    }

    private static class RecordingProviderRegistry {
        int calls;
        boolean lastOk = true;
        String lastDetail;

        void recordCheck(UUID id, boolean ok, String detail) {
            calls++;
            lastOk = ok;
            lastDetail = detail;
        }
    }
}
```

> **Wire `sagaWith(...)` and `unresolvable(...)` following the existing pattern in `ResultSagaRetryTest.java:303-307`**, which builds a `ResultSaga` by direct field assignment with anonymous-subclass fakes. Substitute `RecordingProviderRegistry` for the real `ProviderRegistry` and a fake `ReviewProviderResolver` that returns a provider (or `Optional.empty()` for the third test). Match `ResultSagaRetryTest`'s existing fakes for `lifecycle` and `projection` — do not invent new collaborators. If `ResultSaga`'s entry point is not named `onResult`, use the real name and keep the assertions.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test --tests '*ResultSagaCredentialTest*'
```

Expected: the first test fails with `expected: 1 but was: 0`.

- [ ] **Step 3: Record the rejection**

In `ResultSaga`, inject the two collaborators (if not already present):

```java
    @Inject
    ReviewProviderResolver providers;

    @Inject
    ProviderRegistry providerRegistry;
```

In `onReviewFailed` (`ResultSaga.java:272`), after the existing `projection.appendEvent(...)` line:

```java
        if (e.credentialRejected()) {
            markCredentialRejected(e.reviewId());
        }
```

and add:

```java
    /**
     * Turn one review's 401 into a standing fact about its provider, so the operator sees a
     * credential to rotate instead of a review that failed for no visible reason.
     *
     * <p>The stored detail is a fixed string, never the provider's response body: a 401 body is a
     * plausible place for a token to be echoed back, and this text is persisted and rendered.
     */
    private void markCredentialRejected(String reviewId) {
        providers.resolveForReview(reviewId).ifPresentOrElse(
                provider -> providerRegistry.recordCheck(provider.id(), false,
                        "Authentication rejected (HTTP 401)"),
                () -> LOG.warnf("Credential rejected for review %s but its provider could not be "
                        + "resolved — not recording", reviewId));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-orchestrator:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ResultSagaCredentialTest.java
git commit -m "Flag the provider when a review is refused its credential

A 401 during a real review is now recorded against that review's provider, so
the operator is shown a credential to rotate instead of a review that failed
for no visible reason. The provider is resolved from the review's own stored
type, the way the credential path already does.

The stored reason is a fixed string rather than the provider's response body,
which is a plausible place for a token to be echoed back."
```

---

### Task 7: Gateway rejection tracking (`V2`)

**Files:**
- Create: `spire-gateway/src/main/resources/db/migration/V2__webhook_repo_rejection.sql`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoRegistry.java`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/RegistryWebhookEdge.java`
- Test: `spire-gateway/src/test/java/dev/codespire/gateway/registry/WebhookRepoRejectionTest.java`

**Interfaces:**
- Produces: `WebhookRepoRegistry.recordRejection(String webhookKey, String reason)`, `WebhookRepoRegistry.clearRejections(String webhookKey)`, `WebhookRepoRegistry.Rejection(String target, String reason, int count)`, `WebhookRepoRegistry.rejecting() → List<Rejection>`, `WebhookRepoRegistry.missingSecretTargets() → List<String>`.

- [ ] **Step 1: Write the failing test**

Create `spire-gateway/src/test/java/dev/codespire/gateway/registry/WebhookRepoRejectionTest.java`:

```java
package dev.codespire.gateway.registry;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rejected delivery is stored as STATE on the row it concerns, not as a log entry, which is
 * what lets a successful delivery clear it. Without the clearing half, this would be an
 * incident log wearing a condition's clothes and would never stop nagging.
 */
@QuarkusTest
class WebhookRepoRejectionTest {

    @Inject
    WebhookRepoRegistry registry;

    private WebhookRepoSecret register(String target) {
        return registry.create(new WebhookRepoInput("stub", "repo", target, true));
    }

    @Test
    void aFreshRegistrationIsNotRejecting() {
        register("TEST-OWNER/TEST-REPO-fresh");
        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("fresh")));
    }

    @Test
    void aRecordedRejectionIsReportedWithItsReasonAndCount() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-bad");
        String key = created.view().webhookKey();

        registry.recordRejection(key, "bad_signature");
        registry.recordRejection(key, "bad_signature");

        WebhookRepoRegistry.Rejection row = registry.rejecting().stream()
                .filter(r -> r.target().endsWith("bad")).findFirst().orElseThrow();
        assertEquals("bad_signature", row.reason());
        assertEquals(2, row.count());
    }

    /** Rotate the secret, next delivery lands, row disappears. This is the self-clearing half. */
    @Test
    void aSuccessfulDeliveryClearsTheRejections() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-recovered");
        String key = created.view().webhookKey();
        registry.recordRejection(key, "bad_signature");

        registry.clearRejections(key);

        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("recovered")));
    }

    /** Clearing a row that was never rejecting must not write, so it stays cheap on the hot path. */
    @Test
    void clearingACleanRegistrationIsHarmless() {
        WebhookRepoSecret created = register("TEST-OWNER/TEST-REPO-clean");
        registry.clearRejections(created.view().webhookKey());
        assertTrue(registry.rejecting().stream().noneMatch(r -> r.target().endsWith("clean")));
    }

    /** An unknown key resolves to no row; there is nothing to attach a counter to. */
    @Test
    void recordingAgainstAnUnknownKeyIsIgnored() {
        registry.recordRejection("TEST-UNKNOWN-KEY", "unknown_key");
        List<WebhookRepoRegistry.Rejection> rows = registry.rejecting();
        assertTrue(rows.stream().noneMatch(r -> "unknown_key".equals(r.reason())));
    }
}
```

> Confirm `WebhookRepoInput`'s component order/arity and `WebhookRepoSecret`'s accessor names against those two records, and adjust the construction only.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-gateway:test --tests '*WebhookRepoRejectionTest*'
```

Expected: `cannot find symbol: method recordRejection(String,String)`.

- [ ] **Step 3: Create the migration**

Create `spire-gateway/src/main/resources/db/migration/V2__webhook_repo_rejection.sql`:

```sql
-- Why this registration's deliveries are being refused, so a wrong or blanked shared secret
-- surfaces to the operator instead of only reaching a WARN log.
--
-- Deliberately STATE on the row rather than an append-only log: a successfully verified
-- delivery resets the counter, which is what makes the attention panel's row self-clearing.
-- Rotate the secret, the next delivery lands, the row disappears.
--
-- last_rejection_reason is a closed neutral set (provider_mismatch, bad_signature,
-- malformed_payload, out_of_scope) and NEVER an exception message -- a malformed-payload
-- failure can quote payload content.

ALTER TABLE webhook_repo
    ADD COLUMN last_rejected_at      TIMESTAMPTZ,
    ADD COLUMN last_rejection_reason VARCHAR(32),
    ADD COLUMN rejection_count       INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 4: Add the registry methods**

In `WebhookRepoRegistry`, add:

```java
    /** A registration whose deliveries are being refused, for the attention panel. */
    public record Rejection(String target, String reason, int count) {
    }

    /**
     * Count one refused delivery against the registration this key resolves to. An unknown key
     * matches no row and is silently ignored — there is nothing to attach a counter to, which is
     * why a wrong URL stays a log-only condition.
     *
     * @param reason one of the closed neutral set; never an exception message
     */
    @Transactional
    public void recordRejection(String webhookKey, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE webhook_repo
                        SET rejection_count = rejection_count + 1,
                            last_rejection_reason = ?,
                            last_rejected_at = now()
                      WHERE webhook_key = ?
                     """)) {
            ps.setString(1, reason);
            ps.setString(2, webhookKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a webhook rejection", e);
        }
    }

    /**
     * A verified delivery landed, so this registration is healthy again. Guarded on a non-zero
     * count so the hot path does no write in the normal case.
     */
    @Transactional
    public void clearRejections(String webhookKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE webhook_repo
                        SET rejection_count = 0, last_rejection_reason = NULL, last_rejected_at = NULL
                      WHERE webhook_key = ? AND rejection_count > 0
                     """)) {
            ps.setString(1, webhookKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear webhook rejections", e);
        }
    }

    /** Enabled registrations currently refusing deliveries. */
    public List<Rejection> rejecting() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target, last_rejection_reason, rejection_count FROM webhook_repo "
                             + "WHERE enabled = TRUE AND rejection_count > 0 ORDER BY target");
             ResultSet rs = ps.executeQuery()) {
            List<Rejection> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Rejection(rs.getString("target"),
                        rs.getString("last_rejection_reason"), rs.getInt("rejection_count")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list rejecting webhook repos", e);
        }
    }

    /** Enabled registrations with no shared secret — they can never verify a delivery. */
    public List<String> missingSecretTargets() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target FROM webhook_repo WHERE enabled = TRUE "
                             + "AND (webhook_secret IS NULL OR webhook_secret = '') ORDER BY target");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString("target"));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list webhook repos without a secret", e);
        }
    }
```

- [ ] **Step 5: Record and clear in the edge**

In `RegistryWebhookEdge.route`, replace the four post-resolution rejection returns so each records first, and clear after a successful publish. The unknown-key return at `:73` is unchanged — it resolves to no row.

```java
        Resolved repo = found.get();
        if (!providerType.equals(repo.providerType())) {
            LOG.warnf("Webhook key is registered for provider type %s, not %s", repo.providerType(), providerType);
            registry.recordRejection(key, "provider_mismatch");
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        RawWebhook raw = rawFrom(headers, body);
        ScmIngress ingress = ingressFactory.apply(repo.secret());
        if (!ingress.verifySignature(raw)) {
            LOG.warnf("Rejected %s webhook with missing/invalid signature", providerType);
            registry.recordRejection(key, "bad_signature");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<IntegrationEvent> events;
        try {
            events = ingress.translate(raw);
        } catch (RuntimeException e) {
            LOG.warnf(e, "%s webhook payload rejected", providerType);
            registry.recordRejection(key, "malformed_payload");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (events.isEmpty()) {
            // ping / uninteresting action — accepted, nothing to publish. The signature verified,
            // so the registration is demonstrably healthy: clear any standing rejection.
            registry.clearRejections(key);
            return Response.noContent().build();
        }
```

and in the scope loop:

```java
            LOG.warnf("%s webhook event '%s' (repo %s) is outside the registered %s scope '%s'",
                    providerType, event.getClass().getSimpleName(),
                    eventRepo == null ? "?" : eventRepo.full(), repo.scope(), repo.target());
            registry.recordRejection(key, "out_of_scope");
            return Response.status(Response.Status.BAD_REQUEST).build();
```

and at the publish:

```java
        MDC.put("reviewId", EventKeys.of(events.getFirst()));
        if (!publisher.publishAllAwait(events)) {
            return Response.serverError().build();
        }
        // A verified, in-scope, published delivery proves the registration works.
        registry.clearRejections(key);
        return Response.accepted().build();
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-gateway:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add spire-gateway/src/main/resources/db/migration/V2__webhook_repo_rejection.sql \
        spire-gateway/src/main/java/dev/codespire/gateway/ \
        spire-gateway/src/test/java/dev/codespire/gateway/registry/
git commit -m "Remember why a webhook registration is refusing deliveries

A blanked shared secret made every delivery fail verification, and the only
trace was a WARN log, so the bot simply went quiet. The reason and a count now
live on the registration itself.

Stored as state rather than an append-only log so that a verified delivery
resets it: rotate the secret, the next delivery lands, the condition clears.
Reasons come from a closed neutral set, never an exception message, because a
malformed-payload failure can quote payload content."
```

---

### Task 8: Gateway attention endpoint

**Files:**
- Create: `spire-gateway/src/main/java/dev/codespire/gateway/attention/WebhookAttentionResource.java`
- Test: `spire-gateway/src/test/java/dev/codespire/gateway/attention/WebhookAttentionResourceTest.java`

**Interfaces:**
- Consumes: `AttentionView` (Task 1), `WebhookRepoRegistry.rejecting()`, `.missingSecretTargets()` (Task 7).
- Produces: `GET /api/webhook-repos/attention → List<AttentionView>` with codes `WEBHOOK_DELIVERIES_REJECTED`, `WEBHOOK_SECRET_MISSING`.

- [ ] **Step 1: Write the failing test**

Create `spire-gateway/src/test/java/dev/codespire/gateway/attention/WebhookAttentionResourceTest.java`:

```java
package dev.codespire.gateway.attention;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.codespire.gateway.registry.WebhookRepoInput;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.gateway.registry.WebhookRepoSecret;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class WebhookAttentionResourceTest {

    @Inject
    WebhookRepoRegistry registry;

    @Test
    void aRejectingRegistrationIsReportedWithItsTarget() {
        WebhookRepoSecret created = registry.create(
                new WebhookRepoInput("stub", "repo", "TEST-OWNER/TEST-REPO-att", true));
        registry.recordRejection(created.view().webhookKey(), "bad_signature");

        given().when().get("/api/webhook-repos/attention")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("code", hasItem("WEBHOOK_DELIVERIES_REJECTED"))
                .body("subject", hasItem("TEST-OWNER/TEST-REPO-att"))
                .body("findAll { it.code == 'WEBHOOK_DELIVERIES_REJECTED' }.severity",
                        everyItem(is("WARNING")));
    }

    /**
     * The literal /attention segment must win over the sibling @Path("/{id}") GET, which parses
     * its argument as a UUID. JAX-RS resolves literal segments ahead of templates, but that is a
     * spec guarantee few readers hold in mind and a refactor could silently reorder it into a
     * 400. This pair is the guard.
     */
    @Test
    void theAttentionPathDoesNotShadowTheByIdPath() {
        WebhookRepoSecret created = registry.create(
                new WebhookRepoInput("stub", "repo", "TEST-OWNER/TEST-REPO-shadow", true));

        given().when().get("/api/webhook-repos/attention").then().statusCode(200);
        given().when().get("/api/webhook-repos/" + created.view().id()).then().statusCode(200);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-gateway:test --tests '*WebhookAttentionResourceTest*'
```

Expected: the first test fails — the endpoint returns 400 or 404 (the `{id}` route matching `attention`).

- [ ] **Step 3: Create the resource**

Create `spire-gateway/src/main/java/dev/codespire/gateway/attention/WebhookAttentionResource.java`:

```java
package dev.codespire.gateway.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * The gateway's own attention conditions, in the shape the UI merges with the orchestrator's.
 * The gateway never reads the orchestrator's schema and vice versa, so each service answers
 * only for state it owns and the UI concatenates the two feeds.
 *
 * <p>Deliberately mounted under the already-proxied {@code /api/webhook-repos} prefix so no
 * dev-server or compose proxy rule has to change. The literal {@code /attention} segment wins
 * over the sibling {@code @Path("/{id}")} in {@code WebhookRepoResource} — see the guard test.
 */
@Path("/api/webhook-repos/attention")
@Produces(MediaType.APPLICATION_JSON)
public class WebhookAttentionResource {

    @Inject
    WebhookRepoRegistry registry;

    @GET
    public List<AttentionView> list() {
        List<AttentionView> rows = new ArrayList<>();
        for (String target : registry.missingSecretTargets()) {
            rows.add(new AttentionView("WEBHOOK_SECRET_MISSING", Severity.WARNING, target,
                    "This webhook registration has no shared secret, so no delivery can be verified.",
                    "/settings/webhooks"));
        }
        for (WebhookRepoRegistry.Rejection rejection : registry.rejecting()) {
            rows.add(new AttentionView("WEBHOOK_DELIVERIES_REJECTED", Severity.WARNING, rejection.target(),
                    rejection.count() + " delivery(s) refused (" + reason(rejection.reason())
                            + "). Rotate the secret and re-save it at the provider.",
                    "/settings/webhooks"));
        }
        return rows;
    }

    /** The closed neutral reason set, as operator-facing text. */
    private static String reason(String stored) {
        return switch (stored == null ? "" : stored) {
            case "bad_signature" -> "signature did not verify — the shared secret does not match";
            case "provider_mismatch" -> "the key is registered for a different provider type";
            case "malformed_payload" -> "the payload could not be understood";
            case "out_of_scope" -> "the payload's repository is outside this registration's scope";
            default -> "refused";
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew :spire-gateway:test :spire-arch:test
```

Expected: BUILD SUCCESSFUL, including the neutrality check with no new allowlist entry.

- [ ] **Step 5: Commit**

```bash
git add spire-gateway/src/main/java/dev/codespire/gateway/attention/ \
        spire-gateway/src/test/java/dev/codespire/gateway/attention/
git commit -m "Serve the gateway's own attention conditions

Registrations with no shared secret, and registrations whose deliveries are
being refused, in the same shape the orchestrator uses so the UI can
concatenate both feeds without knowing which service produced a row. Neither
service reads the other's schema.

Mounted under the already-proxied /api/webhook-repos prefix so no dev-server
or compose proxy rule changes. A paired test pins that the literal /attention
segment does not shadow the sibling by-id route."
```

---

### Task 9: The attention bell

**Files:**
- Create: `spire-ui/src/hooks/useAttention.ts`
- Create: `spire-ui/src/components/AttentionBell.tsx`
- Modify: `spire-ui/src/api.ts`
- Modify: `spire-ui/src/App.tsx`
- Modify: `spire-ui/src/index.css`
- Test: `spire-ui/src/components/AttentionBell.test.tsx`

**Interfaces:**
- Consumes: `GET /api/attention` (Task 1), `GET /api/webhook-repos/attention` (Task 8).
- Produces: `interface AttentionItem { code: string; severity: 'BLOCKING' | 'WARNING'; subject: string | null; message: string; action: string | null }`; `fetchAttention(): Promise<AttentionItem[]>`; `fetchWebhookAttention(): Promise<AttentionItem[]>`; `useAttention(): { items: AttentionItem[] }`; `<AttentionBell />`.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/AttentionBell.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AttentionBell from './AttentionBell';
import type { AttentionItem } from '../api';

const blocking: AttentionItem = {
  code: 'LLM_DEFAULT_MISSING',
  severity: 'BLOCKING',
  subject: null,
  message: 'No enabled LLM provider is marked as the default, so no review can run.',
  action: '/settings/llm',
};

const warning: AttentionItem = {
  code: 'DLQ_PENDING',
  severity: 'WARNING',
  subject: null,
  message: '2 message(s) failed processing and are waiting in the dead-letter queue.',
  action: '/settings/dlq',
};

/** Serve the orchestrator feed and the gateway feed independently, as the hook fetches them. */
function stubFeeds(orchestrator: AttentionItem[] | Error, gateway: AttentionItem[] | Error) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      const body = url.includes('webhook-repos') ? gateway : orchestrator;
      if (body instanceof Error) return Promise.reject(body);
      return Promise.resolve({ ok: true, json: () => Promise.resolve(body) } as Response);
    }),
  );
}

const renderBell = () =>
  render(
    <MemoryRouter>
      <AttentionBell />
    </MemoryRouter>,
  );

describe('AttentionBell', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('counts every condition from both feeds', async () => {
    stubFeeds([blocking], [warning]);
    renderBell();
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
  });

  /** A green tick would be a claim the panel cannot make: it only knows what it checks. */
  it('renders no badge when nothing needs attention', async () => {
    stubFeeds([], []);
    renderBell();
    await waitFor(() => expect(screen.queryByTestId('attention-count')).toBeNull());
  });

  it('takes its colour from the most severe condition present', async () => {
    stubFeeds([blocking], [warning]);
    renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('blocking'),
    );
  });

  it('is a warning when no blocker is present', async () => {
    stubFeeds([warning], []);
    renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('warning'),
    );
  });

  /** An unreachable gateway means no webhook is arriving at all — strictly blocking. */
  it('reports an unreachable gateway without losing the other feed', async () => {
    stubFeeds([warning], new Error('connection refused'));
    renderBell();
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    expect(screen.getByTestId('attention-count').className).toContain('blocking');
  });

  it('lists each condition with a link to the page that fixes it', async () => {
    stubFeeds([blocking], []);
    renderBell();
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(blocking.message)).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /settings/i })).toHaveAttribute('href', '/settings/llm');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd spire-ui && npx vitest run src/components/AttentionBell.test.tsx
```

Expected: `Failed to resolve import "./AttentionBell"`.

- [ ] **Step 3: Add the API types and fetchers**

In `spire-ui/src/api.ts`, add:

```ts
/** One operator-facing condition that is true right now. Mirrors the backend AttentionView. */
export interface AttentionItem {
  code: string;
  severity: 'BLOCKING' | 'WARNING';
  subject: string | null;
  message: string;
  action: string | null;
}

export async function fetchAttention(): Promise<AttentionItem[]> {
  const res = await fetch('/api/attention');
  if (!res.ok) throw new Error(`Attention request failed: ${res.status}`);
  return res.json();
}

/** The gateway's own feed. Served by a different service, so it can fail independently. */
export async function fetchWebhookAttention(): Promise<AttentionItem[]> {
  const res = await fetch('/api/webhook-repos/attention');
  if (!res.ok) throw new Error(`Webhook attention request failed: ${res.status}`);
  return res.json();
}
```

- [ ] **Step 4: Create the hook**

Create `spire-ui/src/hooks/useAttention.ts`:

```ts
import { useEffect, useState } from 'react';
import { fetchAttention, fetchWebhookAttention, type AttentionItem } from '../api';

/**
 * Conditions are derived on the server on every request, so there is nothing to subscribe to —
 * a poll is both sufficient and impossible to get stale. 30s keeps the badge honest without
 * making the bell a load source.
 */
const POLL_MS = 30_000;

const SEVERITY_RANK: Record<AttentionItem['severity'], number> = { BLOCKING: 0, WARNING: 1 };

/** The gateway being unreachable means no webhook is arriving at all. */
const GATEWAY_UNREACHABLE: AttentionItem = {
  code: 'GATEWAY_UNREACHABLE',
  severity: 'BLOCKING',
  subject: null,
  message: 'The webhook gateway is not responding, so no pull request event can arrive.',
  action: null,
};

function bySeverityThenCode(a: AttentionItem, b: AttentionItem): number {
  const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (bySeverity !== 0) return bySeverity;
  const byCode = a.code.localeCompare(b.code);
  return byCode !== 0 ? byCode : (a.subject ?? '').localeCompare(b.subject ?? '');
}

export function useAttention(): { items: AttentionItem[] } {
  const [items, setItems] = useState<AttentionItem[]>([]);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      // Settled, not all: one service being down must never blank the other's rows.
      const [orchestrator, gateway] = await Promise.allSettled([
        fetchAttention(),
        fetchWebhookAttention(),
      ]);
      if (cancelled) return;
      const merged: AttentionItem[] = [];
      if (orchestrator.status === 'fulfilled') merged.push(...orchestrator.value);
      if (gateway.status === 'fulfilled') merged.push(...gateway.value);
      else merged.push(GATEWAY_UNREACHABLE);
      setItems(merged.sort(bySeverityThenCode));
    };

    void load();
    const timer = setInterval(() => void load(), POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return { items };
}
```

- [ ] **Step 5: Create the bell**

Create `spire-ui/src/components/AttentionBell.tsx`:

```tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Bell, CircleAlert, TriangleAlert } from 'lucide-react';
import { useAttention } from '../hooks/useAttention';
import Tooltip from './Tooltip';

/**
 * Conditions needing the operator's attention. There is no dismiss action anywhere: every row
 * is a query result, so fixing the cause is what removes it. Zero conditions renders NO badge
 * rather than a green tick — the panel only knows about conditions it checks, so "all clear"
 * would be a claim it cannot make.
 */
export default function AttentionBell() {
  const { items } = useAttention();
  const [open, setOpen] = useState(false);

  const blocking = items.some((item) => item.severity === 'BLOCKING');
  const tone = blocking ? 'blocking' : 'warning';

  return (
    <div className="attention">
      <Tooltip label={items.length === 0 ? 'Nothing needs attention' : 'Needs attention'}>
        <button
          className="iconbtn"
          data-testid="attention-toggle"
          aria-label="Needs attention"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Bell size={17} />
          {items.length > 0 && (
            <span className={`attention-count ${tone}`} data-testid="attention-count">
              {items.length}
            </span>
          )}
        </button>
      </Tooltip>

      {open && (
        <div className="attention-panel" role="dialog" aria-label="Needs attention">
          {items.length === 0 ? (
            <p className="attention-empty">No conditions need attention.</p>
          ) : (
            <ul className="attention-list">
              {items.map((item) => (
                <li key={`${item.code}:${item.subject ?? ''}`} className={`attention-row ${item.severity.toLowerCase()}`}>
                  <span className="attention-icon">
                    {item.severity === 'BLOCKING' ? <CircleAlert size={15} /> : <TriangleAlert size={15} />}
                  </span>
                  <span className="attention-body">
                    {item.subject && <span className="attention-subject">{item.subject}</span>}
                    <span className="attention-message">{item.message}</span>
                    {item.action && (
                      <Link className="attention-action" to={item.action} onClick={() => setOpen(false)}>
                        Settings
                      </Link>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
```

> If `Tooltip`'s import path or default-export shape differs, match how `App.tsx` already imports it.

- [ ] **Step 6: Mount it and add styles**

In `spire-ui/src/App.tsx`, import `AttentionBell` and place it in the topbar between the Register PR button and the theme toggle (after the `Tooltip`-wrapped `pr` button, before the theme `Tooltip`):

```tsx
          <AttentionBell />
```

In `spire-ui/src/index.css`, add:

```css
/* Attention bell. The count badge is the only always-visible signal, so it must read at a
   glance without relying on colour alone -- the panel repeats the severity as an icon. */
.attention {
  position: relative;
}

.attention-count {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 15px;
  padding: 0 3px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 600;
  line-height: 15px;
  text-align: center;
  color: #fff;
}

.attention-count.blocking {
  background: var(--danger, #d64545);
}

.attention-count.warning {
  background: var(--warn, #c07d1a);
}

.attention-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 40;
  width: min(380px, calc(100vw - 32px));
  max-height: 60vh;
  overflow-y: auto;
  padding: 6px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-1);
  box-shadow: 0 8px 24px rgb(0 0 0 / 22%);
}

.attention-empty {
  margin: 0;
  padding: 10px;
  color: var(--text-2);
  font-size: 12px;
}

.attention-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.attention-row {
  display: flex;
  gap: 8px;
  padding: 9px 8px;
  border-bottom: 1px solid var(--border);
}

.attention-row:last-child {
  border-bottom: none;
}

.attention-row.blocking .attention-icon {
  color: var(--danger, #d64545);
}

.attention-row.warning .attention-icon {
  color: var(--warn, #c07d1a);
}

.attention-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.attention-subject {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-2);
  overflow-wrap: anywhere;
}

.attention-message {
  font-size: 12px;
  line-height: 1.4;
}

.attention-action {
  align-self: flex-start;
  font-size: 11px;
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd spire-ui && npx vitest run src/components/AttentionBell.test.tsx && npx tsc --noEmit
```

Expected: 6 tests pass, `tsc` clean.

- [ ] **Step 8: Run the whole UI suite**

```bash
cd spire-ui && npm test -- --run
```

Expected: 127 tests pass (121 baseline + 6).

- [ ] **Step 9: Commit**

```bash
git add spire-ui/src/hooks/useAttention.ts spire-ui/src/components/AttentionBell.tsx \
        spire-ui/src/components/AttentionBell.test.tsx spire-ui/src/api.ts \
        spire-ui/src/App.tsx spire-ui/src/index.css
git commit -m "Show conditions needing attention in the topbar

Merges the orchestrator's and gateway's condition feeds behind one bell. The
two are fetched independently so a service being down cannot blank the other's
rows, and a gateway that does not answer becomes its own blocking row -- an
unreachable gateway means no pull request event is arriving at all.

Zero conditions renders no badge rather than a green tick: the panel only knows
about conditions it checks, so all-clear is not a claim it can make."
```

---

### Task 10: "Last checked" on the provider settings pages

`CREDENTIAL_UNVERIFIED` was deliberately rejected as a panel row — a permanent row for every provider nobody has checked is wallpaper. Its home is beside the Check button that acts on it.

**Files:**
- Modify: `spire-ui/src/api.ts`
- Modify: `spire-ui/src/components/SettingsProviders.tsx`
- Modify: `spire-ui/src/components/SettingsLlmProviders.tsx`
- Modify: `spire-ui/src/components/SettingsContextProviders.tsx`
- Test: `spire-ui/src/components/ProviderLastChecked.test.tsx`

**Interfaces:**
- Consumes: the three `*View` records' new `lastCheckAt` / `lastCheckOk` / `lastCheckError` fields (Task 3); `POST /api/llm-providers/{id}/check` (Task 4).
- Produces: `lastCheckedLabel(item): string` exported from `spire-ui/src/components/lastChecked.ts`.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/ProviderLastChecked.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { lastCheckedLabel } from './lastChecked';

/**
 * "Never checked" is information, not a problem — which is exactly why it lives here and not
 * as an attention row. The three states must stay visually distinct.
 */
describe('lastCheckedLabel', () => {
  it('says so when a credential has never been checked', () => {
    expect(lastCheckedLabel({ lastCheckAt: null, lastCheckOk: null, lastCheckError: null })).toBe(
      'Never checked',
    );
  });

  it('reports a passing check with its time', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: true,
      lastCheckError: null,
    });
    expect(label).toContain('Checked');
    expect(label).not.toContain('rejected');
  });

  it('surfaces the stored reason on a failing check', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: 'Authentication rejected (HTTP 401)',
    });
    expect(label).toContain('Authentication rejected (HTTP 401)');
  });

  /** A false with no stored detail must still read as a failure, not as a blank. */
  it('reports a failing check with no detail as rejected', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: null,
    });
    expect(label.toLowerCase()).toContain('rejected');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd spire-ui && npx vitest run src/components/ProviderLastChecked.test.tsx
```

Expected: `Failed to resolve import "./lastChecked"`.

- [ ] **Step 3: Add the shared fields and helper**

Find the three interfaces that model the provider rows — they are the return types of the
`/api/providers`, `/api/llm-providers` and `/api/context-providers` list fetchers:

```bash
cd spire-ui && grep -n "interface .*Provider" src/api.ts
```

Add these three fields to each of those three interfaces:

```ts
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
```

Create `spire-ui/src/components/lastChecked.ts`:

```ts
/** The credential-check fields shared by all three provider kinds. */
export interface LastChecked {
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
}

/**
 * One line for the credential's standing. Three states, deliberately distinct: never checked is
 * information rather than a problem, which is why it is shown here instead of raising an
 * attention row for every provider whose Check button was never pressed.
 */
export function lastCheckedLabel(item: LastChecked): string {
  if (item.lastCheckAt === null || item.lastCheckOk === null) return 'Never checked';
  const when = new Date(item.lastCheckAt).toLocaleString();
  if (item.lastCheckOk) return `Checked ${when}`;
  return item.lastCheckError
    ? `Rejected ${when} — ${item.lastCheckError}`
    : `Rejected ${when}`;
}
```

- [ ] **Step 4: Render it on all three settings pages**

The same markup is needed on all three pages, so it is a component rather than a repeated block.
Create `spire-ui/src/components/LastChecked.tsx`:

```tsx
import { lastCheckedLabel, type LastChecked as LastCheckedFields } from './lastChecked';

/** The credential's standing, shown beside the Check control on every provider settings page. */
export default function LastChecked({ item }: { item: LastCheckedFields }) {
  return (
    <span className={`last-checked ${item.lastCheckOk === false ? 'failed' : ''}`}>
      {lastCheckedLabel(item)}
    </span>
  );
}
```

In each of `SettingsProviders.tsx`, `SettingsLlmProviders.tsx` and `SettingsContextProviders.tsx`,
import it and render it next to that page's Check control:

```tsx
<LastChecked item={item} />
```

`SettingsLlmProviders.tsx` has no Check control yet — add a button calling the new endpoint, mirroring how `SettingsProviders.tsx` calls its own check:

```ts
export async function checkLlmProvider(id: string): Promise<{ ok: boolean; detail: string | null }> {
  const res = await fetch(`/api/llm-providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  if (!res.ok) throw new Error(`LLM provider check failed: ${res.status}`);
  return res.json();
}
```

(place that fetcher in `api.ts` beside the other LLM calls).

Add to `index.css`:

```css
.last-checked {
  font-size: 11px;
  color: var(--text-2);
}

.last-checked.failed {
  color: var(--danger, #d64545);
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd spire-ui && npx vitest run src/components/ProviderLastChecked.test.tsx && npx tsc --noEmit && npm test -- --run
```

Expected: 4 new tests pass, `tsc` clean, 131 total UI tests pass.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/api.ts spire-ui/src/components/
git commit -m "Show each provider's last credential check beside its button

Never-checked is information rather than a problem, so it belongs next to the
control that acts on it instead of raising a permanent attention row for every
provider nobody has pressed Check on -- which would be wallpaper within a week.

LLM providers gain the Check button the other two registries already had."
```

---

### Task 11: Documentation and full verification

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/SMOKE-TEST.md`

- [ ] **Step 1: Run the complete suite**

```bash
JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9" ./gradlew clean build
cd spire-ui && npm test -- --run && npx tsc --noEmit && npm audit
```

Expected: BUILD SUCCESSFUL; UI tests pass; `tsc` clean; `npm audit` reports 0 vulnerabilities. **Use `clean` — a cached "up-to-date" pass proves nothing about code you just changed.** Confirm the run was real by checking test XML timestamps:

```bash
find . -name "TEST-*.xml" -newermt "-15 minutes" | wc -l
```

Expected: a non-zero count. Report the exact test totals from this run, not from the baseline.

- [ ] **Step 2: Add the status bullet to `CLAUDE.md`**

Append to the Status list, matching the surrounding entries' style:

```markdown
- **Operator attention panel (2026-07-27):** a topbar bell in `spire-ui` whose every row is a
  condition true *right now*, derived on demand — nothing stored, nothing to dismiss, so fixing
  the cause removes the row. Two same-shape feeds (`AttentionView` in `spire-contract`) merged
  client-side: `GET /api/attention` (orchestrator — no usable default LLM provider, no SCM
  provider, unresolved bot identity, rejected credential, stuck/failed reviews, pending DLQ) and
  `GET /api/webhook-repos/attention` (gateway — registrations with no secret or refusing
  deliveries). **No new topic and no non-`reviewId` message class:** most of the catalog is
  *state*, not events, so each service answers for its own schema over the HTTP surface it
  already has. Credential health is recorded only from work already happening — the Check
  buttons, provider save, and a real review's 401 (new neutral `ScmApiException.isUnauthorized()`,
  **401-only** because one provider overloads 403 for rate limiting; carried by
  `ReviewFailed.credentialRejected`). `llm_provider` gains the Check endpoint the other two
  registries had. Gateway rejections are state on `webhook_repo` (V2) that a verified delivery
  clears, so the row self-clears when the secret is rotated. V28 adds three-valued
  `last_check_ok` (NULL never checked / TRUE passed / FALSE rejected) to all three registries.
  Deliberately excluded: `CREDENTIAL_UNVERIFIED` as a row (wallpaper — it lives inline on the
  settings pages), per-review facts like the turn cap, and dead-tunnel detection (absence of
  traffic is indistinguishable from a quiet afternoon; `REVIEW_STUCK` is the honest proxy).
```

- [ ] **Step 3: Add a runbook section to `docs/SMOKE-TEST.md`**

Add, matching the existing modes' formatting:

```markdown
## Mode H — attention panel

Each check should make a bell row appear, and undoing it should make the row disappear with no
dismissal.

1. **No usable default LLM provider.** Settings → LLM, disable the default provider. Expect a
   red badge with `LLM_DEFAULT_MISSING`. Re-enable it; the row goes.
2. **Rejected credential.** Settings → Providers, edit a provider's token to a wrong value and
   press Check. Expect `CREDENTIAL_REJECTED` naming that provider. Restore the token and press
   Check; the row goes.
3. **Rejected webhook deliveries.** Change a registration's secret at the provider without
   rotating it here, then push a commit. Expect `WEBHOOK_DELIVERIES_REJECTED` naming the repo.
   Rotate the secret, re-save it at the provider and push again; the row goes on the next
   verified delivery.
4. **Stuck review.** Stop the review worker and push a commit. After
   `SPIRE_ATTENTION_STUCK_MINUTES` expect `REVIEW_STUCK`. Restart the worker and let the review
   finish; the row goes.
5. **Unreachable gateway.** Stop the gateway container. Expect a blocking `GATEWAY_UNREACHABLE`
   row **and** the orchestrator's own rows still listed. Restart it; the row goes.
6. **Clean system.** With everything configured and healthy, expect **no badge at all** — not a
   green tick.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/SMOKE-TEST.md
git commit -m "Record the attention panel and how to smoke-test it

Notes the deliberate exclusions alongside what shipped: an unverified
credential is not a row, per-review facts stay out of a global bell, and
dead-tunnel detection is not attempted because absence of traffic cannot be
told from a quiet afternoon."
```

---

## Interface summary

Names later tasks depend on, in one place.

| Symbol | Introduced | Signature |
|---|---|---|
| `AttentionView` | T1 | `record(String code, Severity severity, String subject, String message, String action)` |
| `AttentionView.Severity` | T1 | `enum { BLOCKING, WARNING }` |
| `AttentionQueries.collect()` | T1 | `→ List<AttentionView>` |
| `DlqRepository.countPending()` | T1 | `→ int` |
| `spire.attention.stuck-minutes` | T2 | `int`, default `15` |
| `spire.attention.failed-window-hours` | T2 | `int`, default `24` |
| `*Registry.recordCheck(...)` | T3 | `(UUID id, boolean ok, String detail) → void`, on all three registries |
| `*View.lastCheckAt/Ok/Error` | T3 | `Instant` / `Boolean` (boxed) / `String`, trailing components |
| `LlmKeyValidator.CheckOutcome` | T4 | `record(boolean ok, int status, String detail)` |
| `LlmKeyValidator.check(...)` | T4 | `(String type, String baseUrl, String apiKey) → CheckOutcome` |
| `LlmProviderRegistry.resolveById(UUID)` | T4 | `→ Optional<LlmProviderConfig>` |
| `LlmProviderResource.CheckResult` | T4 | `record(boolean ok, String detail)` |
| `ScmApiException.isUnauthorized()` | T5 | `default boolean` — `status() == 401` |
| `ReviewFailed` | T5 | `(String, String, String, String, boolean, int, boolean credentialRejected)` + 6-arg convenience ctor |
| `WebhookRepoRegistry.Rejection` | T7 | `record(String target, String reason, int count)` |
| `WebhookRepoRegistry.recordRejection` | T7 | `(String webhookKey, String reason) → void` |
| `WebhookRepoRegistry.clearRejections` | T7 | `(String webhookKey) → void` |
| `WebhookRepoRegistry.rejecting()` | T7 | `→ List<Rejection>` |
| `WebhookRepoRegistry.missingSecretTargets()` | T7 | `→ List<String>` |
| `AttentionItem` (TS) | T9 | `{ code, severity: 'BLOCKING' \| 'WARNING', subject, message, action }` |
| `useAttention()` | T9 | `→ { items: AttentionItem[] }` |
| `lastCheckedLabel(item)` | T10 | `(LastChecked) → string` |

## Condition-code coverage

| Code | Task |
|---|---|
| `LLM_PROVIDER_MISSING`, `LLM_DEFAULT_MISSING`, `SCM_PROVIDER_MISSING`, `BOT_IDENTITY_UNRESOLVED`, `DLQ_PENDING` | T1 |
| `REVIEW_STUCK`, `REVIEW_FAILED` | T2 |
| `CREDENTIAL_REJECTED` | T3 (condition), T4 + T6 (the signals that raise it) |
| `WEBHOOK_SECRET_MISSING`, `WEBHOOK_DELIVERIES_REJECTED` | T8 (T7 supplies the state) |
| `GATEWAY_UNREACHABLE` | T9 (UI-synthesized) |
