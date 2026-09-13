package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Queue time and agent time are distinct facts, including across late and redelivered results. */
@QuarkusTest
class RunAgentStartedTest {

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void queuedRunsHaveNoAgentStart() {
        String runId = queuedRun();

        assertNull(projection.find(runId).orElseThrow().agentStartedAt());
        assertNull(listed(runId).agentStartedAt());
    }

    @Test
    void theFirstStartRecordsAgentTimeWithoutMovingQueueTime() {
        String runId = queuedRun();
        exec("UPDATE factory_run SET started_at = TIMESTAMPTZ '2026-01-01 00:00:00Z' WHERE run_id = ?", runId);
        Instant before = databaseNow();

        projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));

        Instant at = projection.find(runId).orElseThrow().agentStartedAt();
        assertFalse(at.isBefore(before.minusMillis(1)), "the start is observed now, not copied from queue time");
        assertFalse(at.isAfter(databaseNow()));
        assertEquals(at, listed(runId).agentStartedAt());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), listed(runId).startedAt());
    }

    @Test
    void redeliveryPreservesTheFirstStartEvenWhenRecoveringADispatchState() {
        String runId = queuedRun();
        projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));
        // Pin the original time so this guard cannot pass because two writes share a clock tick.
        exec("UPDATE factory_run SET agent_started_at = TIMESTAMPTZ '2026-01-01 00:00:01Z' WHERE run_id = ?", runId);
        Instant first = projection.find(runId).orElseThrow().agentStartedAt();

        projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));
        assertEquals(first, projection.find(runId).orElseThrow().agentStartedAt());

        // Ordinary running-row redelivery is rejected by WHERE before COALESCE is evaluated.
        // Exercise an eligible recovery row too, otherwise deleting COALESCE survives the test.
        exec("UPDATE factory_run SET status = 'dispatch_uncertain' WHERE run_id = ?", runId);
        projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));
        assertEquals(FactoryRunProjection.RUNNING, projection.find(runId).orElseThrow().status());
        assertEquals(first, projection.find(runId).orElseThrow().agentStartedAt());
    }

    @Test
    void aLateStartDoesNotInventAgentTimeOnATerminalRun() {
        for (boolean started : List.of(false, true)) {
            String runId = queuedRun();
            if (started) projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));
            Instant first = projection.find(runId).orElseThrow().agentStartedAt();
            projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/test",
                    List.of(), List.of(), null, false));
            Instant ended = listed(runId).endedAt();

            projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));

            assertEquals(FactoryRunProjection.SUCCEEDED, projection.find(runId).orElseThrow().status());
            assertEquals(first, projection.find(runId).orElseThrow().agentStartedAt());
            assertEquals(ended, listed(runId).endedAt());
        }
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void bothEndpointsExposeTheAgentTimestampForARealShapedRunId() {
        String runId = queuedRun();
        given().get("/api/runs/{runId}", runId).then().statusCode(200)
                .body("agentStartedAt", nullValue());
        projection.apply(new RunResult.RunStarted(runId, "TEST-unit"));
        exec("UPDATE factory_run SET agent_started_at = TIMESTAMPTZ '2026-01-01 00:00:01Z' WHERE run_id = ?", runId);

        given().get("/api/runs/{runId}", runId).then().statusCode(200)
                .body("runId", equalTo(runId))
                .body("agentStartedAt", equalTo("2026-01-01T00:00:01Z"));
        given().queryParam("limit", 200).get("/api/runs").then().statusCode(200)
                .body("find { it.runId == '" + runId + "' }.agentStartedAt", equalTo("2026-01-01T00:00:01Z"));
    }

    private String queuedRun() {
        String runId = "run::github:TEST-agent-start/app:" + UUID.randomUUID() + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL",
                "main", "TEST-SHA", "spire/test", "TEST-bot", null), null, null));
        return runId;
    }

    private Instant databaseNow() {
        // The timestamp is generated in Postgres. Host and container clocks can differ under load.
        try (var c = dataSource.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(rows.next()); return rows.getTimestamp(1).toInstant();
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private FactoryRunProjection.RunListEntry listed(String runId) {
        // One test pins queue time in the past; unrelated suites may have more than 200 newer rows.
        return projection.list(new FactoryRunProjection.RunFilter(null, null, null, Integer.MAX_VALUE)).stream()
                .filter(row -> row.runId().equals(runId)).findFirst().orElseThrow();
    }

    private void exec(String sql, String runId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            assertEquals(1, ps.executeUpdate());
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
