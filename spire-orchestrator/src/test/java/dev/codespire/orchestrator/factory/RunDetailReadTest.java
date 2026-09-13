package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

/** Reads real stored definitions and ledger rows, including values that SQL SUM alone conceals. */
@QuarkusTest
@TestSecurity(user = "op", roles = "spire-admin")
class RunDetailReadTest {

    @Inject
    FactoryRunProjection runs;

    @Inject
    ReviewProjection ledger;

    @Inject
    DataSource dataSource;

    @Test
    void existenceDistinguishesAnEmptyRunFromAnUnknownId() {
        String runId = queue();
        assertTrue(runs.exists(runId));
        assertFalse(runs.exists(runId + "-missing"));
    }

    @AfterEach
    void removeOwnedRows() throws Exception {
        sql("DELETE FROM llm_charge WHERE subject_id LIKE 'run::github:TEST-spend/%'");
        sql("DELETE FROM factory_run WHERE workspace = 'TEST-spend/group'");
    }

    @Test
    void theDetailEndpointReturnsTheWholeDefinitionForASlashAndColonId() throws Exception {
        String runId = queue();
        String subject = runId.substring(runId.lastIndexOf("app:") + 4, runId.lastIndexOf(':'));
        runs.apply(new RunResult.RunStarted(runId, "TEST-unit"));
        runs.apply(new RunResult.RunFinished(runId, "refs/heads/feature/test", List.of(), List.of(), null, false));
        sql("UPDATE factory_run SET started_at = TIMESTAMPTZ '2026-01-01 00:00:00Z', "
                + "agent_started_at = TIMESTAMPTZ '2026-01-01 00:01:00Z', "
                + "ended_at = TIMESTAMPTZ '2026-01-01 00:05:00Z' WHERE run_id = ?", runId);
        charge(runId, ChargeLine.metered(TokenType.INPUT, 200, 1_000_000));

        given().get("/api/runs/{runId}", runId).then().statusCode(200)
                .body("runId", equalTo(runId)).body("status", equalTo("succeeded"))
                .body("kind", equalTo("FIX")).body("harness", equalTo("codex"))
                .body("model", equalTo("TEST-detail-model")).body("providerType", equalTo("github"))
                .body("workspace", equalTo("TEST-spend/group")).body("slug", equalTo("app"))
                .body("subject", equalTo(subject)).body("attempt", equalTo(2))
                .body("baseBranch", equalTo("main")).body("baseCommit", equalTo("TEST-base-sha"))
                .body("branch", equalTo("feature/test")).body("pushedAs", equalTo("TEST-bot"))
                .body("pushedRef", equalTo("refs/heads/feature/test"))
                .body("reviewId", equalTo("review::TEST-spend/group/app#42"))
                .body("findingRef", equalTo("TEST-finding")).body("taskSummary", equalTo("TEST-task summary"))
                .body("unitId", equalTo("TEST-unit")).body("blocked", equalTo(List.of()))
                .body("failureCause", nullValue()).body("failureDetail", nullValue())
                .body("prUrl", nullValue()).body("prError", nullValue())
                .body("startedAt", equalTo("2026-01-01T00:00:00Z"))
                .body("agentStartedAt", equalTo("2026-01-01T00:01:00Z"))
                .body("endedAt", equalTo("2026-01-01T00:05:00Z"))
                .body("cost.millicents", equalTo(200)).body("spend.priced", equalTo(200))
                .body("spend.unpricedLines", equalTo(0)).body("spend.tokensByType", equalTo(Map.of("INPUT", 200)));
    }

    @Test
    void noChargesMeanUnknownCostNotFree() {
        String runId = queue();
        var view = runs.find(runId).orElseThrow();
        assertNull(view.cost().millicents());
        assertEquals(new RunSpend(0, 0, Map.of()), view.spend());
        given().get("/api/runs/{runId}", runId).then().statusCode(200).body("cost.millicents", nullValue());
    }

    @Test
    void oneUnpricedLineMakesTotalUnknownButKeepsThePricedSubtotal() {
        String runId = queue();
        charge(runId, ChargeLine.metered(TokenType.INPUT, 100, 1_000_000));
        charge(runId, ChargeLine.unknown(TokenType.OUTPUT, 40));
        assertNull(runs.find(runId).orElseThrow().cost().millicents());
        assertEquals(new RunSpend(100, 1, Map.of("INPUT", 100L, "OUTPUT", 40L)), spend(runId));
        assertEquals(runs.listOne(runId).orElseThrow().cost(), runs.find(runId).orElseThrow().cost());
    }

    @Test
    void entirelyUnpricedUsageIsStillUnknownAndItsTokensRemainReadable() {
        String runId = queue();
        charge(runId, ChargeLine.unknown(TokenType.TOTAL, 300));
        assertEquals(new RunSpend(0, 1, Map.of("TOTAL", 300L)), spend(runId));
        assertFalse(runs.find(runId).orElseThrow().cost().isKnown());
    }

    @Test
    void aKnownZeroRemainsKnown() {
        String runId = queue();
        charge(runId, ChargeLine.unmetered(TokenType.TOTAL, 300));
        assertEquals(RunCost.zero(), runs.find(runId).orElseThrow().cost());
        assertEquals(new RunSpend(0, 0, Map.of("TOTAL", 300L)), spend(runId));
    }

    @Test
    void tokenTypesAndCallsAreSummedIndependently() {
        String runId = queue();
        Map<String, Long> expected = Map.of("INPUT", 12L, "CACHED_INPUT", 20L,
                "CACHE_WRITE", 30L, "OUTPUT", 40L, "REASONING", 50L);
        charge(runId, ChargeLine.metered(TokenType.INPUT, 5, 1_000_000));
        charge(runId, ChargeLine.metered(TokenType.INPUT, 7, 1_000_000));
        for (TokenType type : List.of(TokenType.CACHED_INPUT, TokenType.CACHE_WRITE, TokenType.OUTPUT, TokenType.REASONING)) {
            charge(runId, ChargeLine.metered(type, expected.get(type.name()).intValue(), 1_000_000));
        }
        assertEquals(new RunSpend(152, 0, expected), spend(runId));
        assertEquals(RunCost.of(152), runs.find(runId).orElseThrow().cost());
        assertEquals(runs.listOne(runId).orElseThrow().cost(), runs.find(runId).orElseThrow().cost());
    }

    @Test
    void archivedPricedAndUnpricedLinesAreExcluded() throws Exception {
        String runId = queue();
        charge(runId, ChargeLine.metered(TokenType.OUTPUT, 900, 1_000_000));
        charge(runId, ChargeLine.unknown(TokenType.CACHED_INPUT, 600));
        sql("UPDATE llm_charge SET archived_at = now() WHERE subject_id = ?", runId);
        assertEquals(new RunSpend(0, 0, Map.of()), spend(runId));
        charge(runId, ChargeLine.metered(TokenType.INPUT, 100, 1_000_000));
        assertEquals(new RunSpend(100, 0, Map.of("INPUT", 100L)), spend(runId));
        assertEquals(RunCost.of(100), runs.find(runId).orElseThrow().cost());
        assertEquals(runs.listOne(runId).orElseThrow().cost(), runs.find(runId).orElseThrow().cost());
    }

    @Test
    void anotherRunsChargeDoesNotBelongToThisRun() {
        String mine = queue();
        String theirs = queue();
        charge(mine, ChargeLine.metered(TokenType.INPUT, 100, 1_000_000));
        charge(theirs, ChargeLine.metered(TokenType.INPUT, 900, 1_000_000));
        assertEquals(new RunSpend(100, 0, Map.of("INPUT", 100L)), spend(mine));
    }

    @Test
    void aReviewChargeWithTheSameTextualIdIsNotRunSpend() throws Exception {
        String runId = queue();
        charge(runId, ChargeLine.metered(TokenType.OUTPUT, 500, 1_000_000));
        sql("UPDATE llm_charge SET subject_kind = 'REVIEW' WHERE subject_id = ?", runId);
        charge(runId, ChargeLine.metered(TokenType.INPUT, 100, 1_000_000));
        assertEquals(new RunSpend(100, 0, Map.of("INPUT", 100L)), spend(runId));
    }

    private RunSpend spend(String runId) {
        return runs.find(runId).orElseThrow().spend();
    }

    private String queue() {
        String runId = "run::github:TEST-spend/group/app:detail-" + UUID.randomUUID() + ":2";
        var row = new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-detail-model", "main",
                "TEST-base-sha", "feature/test", "TEST-bot", null)
                .asFixFor("review::TEST-spend/group/app#42", "TEST-finding", "comment-" + UUID.randomUUID());
        assertTrue(runs.queued(row, "TEST-task summary", null));
        return runId;
    }

    private void charge(String runId, ChargeLine line) {
        ledger.recordCharges(ChargeCall.forRun(runId, "TEST-detail-" + UUID.randomUUID(),
                "TEST-detail-model", List.of(line), null));
    }

    private void sql(String query, String... args) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            statement.executeUpdate();
        }
    }
}
