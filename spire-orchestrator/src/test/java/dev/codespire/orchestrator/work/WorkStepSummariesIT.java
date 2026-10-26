package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCharges;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A build's step summary, stored (M4 slice 8, §6.8): encrypted in {@code work_step_summary} when the ready result
 * carries one, recorded as {@code summary_missing} when it does not, once per run however often the ready is
 * redelivered, and read back by the steps view. Step index 0 until the step cursor exists (slice 9).
 */
@QuarkusTest
@TestSecurity(user = "TEST-prepared-admin", roles = "spire-admin")
class WorkStepSummariesIT extends WorkPreparedFixture {
    private static final StepSummary SUMMARY = new StepSummary(List.of("TEST-decision"), List.of("TEST-alternative"),
            List.of("TEST-deviation"));

    @Inject RunResultSaga saga;
    @Inject WorkItemRunBridge bridge;
    @Inject FactoryRunProjection runs;
    @Inject RunCharges charges;
    @Inject WorkStepSummaries summaries;

    private String build() throws Exception {
        String id = admit("autonomous", 61);
        register(id);
        dispatcher.drain();
        assertEquals(1, runCount(id));
        return id;
    }

    private RunResult.RunWorkReady ready() {
        return new RunResult.RunWorkReady(dispatched.getFirst().runId(), heldCommands.getFirst().work(), "b".repeat(40),
                List.of("TEST-task.txt"), Map.of("INPUT", 1L), 9);
    }

    private byte[] storedBytes(String id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT summary FROM work_step_summary WHERE work_item_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no summary row");
                return rs.getBytes(1);
            }
        }
    }

    @Test
    void theSummaryIsStoredEncrypted() throws Exception {
        String id = build();
        RunResult.RunWorkReady ready = ready().withStepSummary(SUMMARY);
        saga.on(ready);

        assertEquals(1, count("SELECT count(*) FROM work_step_summary WHERE work_item_id=? AND state='recorded' AND step_index=0", id));
        byte[] stored = storedBytes(id);
        assertNotNull(stored);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("TEST-decision"), "the summary is stored plain");
        long generation = store.load(id).generation();
        assertEquals(List.of(new WorkStepSummaries.Step(0, ready.runId(), "recorded", SUMMARY)), summaries.list(id, generation));
    }

    @Test
    void aMissingSummaryIsRecordedAsMissing() throws Exception {
        String id = build();
        saga.on(ready());

        assertEquals(1, count("SELECT count(*) FROM work_step_summary WHERE work_item_id=? AND state='summary_missing'", id));
        assertNull(storedBytes(id));
        assertEquals(List.of(new WorkStepSummaries.Step(0, ready().runId(), "summary_missing", null)),
                summaries.list(id, store.load(id).generation()));
    }

    @Test
    void aRedeliveredReadyRecordsOnce() throws Exception {
        String id = build();
        saga.on(ready().withStepSummary(SUMMARY));
        // The crash window after the aggregate committed but before the inbox acknowledgement: the ready is applied again.
        execute("UPDATE work_run_effect SET ready_processed=false WHERE work_item_id=?", id);
        bridge.recover();

        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE work_item_id=? AND ready_processed", id));
        assertEquals(1, count("SELECT count(*) FROM work_step_summary WHERE work_item_id=?", id));
    }

    @Test
    void theStoredReadyPayloadKeepsItsSummary() throws Exception {
        String id = build();
        RunResult.RunWorkReady ready = ready().withStepSummary(SUMMARY);
        runs.apply(ready);
        charges.record(ready);
        assertTrue(bridge.record(ready));
        assertEquals(0, count("SELECT count(*) FROM work_step_summary WHERE work_item_id=?", id));

        // The restart path: the ready is decoded from the stored, encrypted ready_payload, not from the message.
        bridge.recover();

        assertEquals(List.of(new WorkStepSummaries.Step(0, ready.runId(), "recorded", SUMMARY)),
                summaries.list(id, store.load(id).generation()));
    }

    @Test
    void theStepsViewShowsEachStepsSummary() throws Exception {
        String id = build();
        saga.on(ready().withStepSummary(SUMMARY));

        given().get("/api/work-items/" + id + "/steps").then().statusCode(200)
                .body("steps.size()", is(1), "steps[0].stepIndex", is(0), "steps[0].state", is("recorded"),
                        "steps[0].summary.decisions[0]", is("TEST-decision"),
                        "steps[0].summary.alternativesRejected[0]", is("TEST-alternative"),
                        "steps[0].summary.deviations[0]", is("TEST-deviation"));
    }

    @Test
    void theStepsViewSaysASummaryIsMissing() throws Exception {
        String id = build();
        saga.on(ready());

        given().get("/api/work-items/" + id + "/steps").then().statusCode(200)
                .body("steps[0].state", is("summary_missing"), "steps[0].summary", nullValue());
        given().get("/api/work-items/TEST-no-such-item/steps").then().statusCode(404);
    }
}
