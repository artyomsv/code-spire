package dev.codespire.orchestrator.work;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The specification run of a generated item (M4 slice 3, §6.3, A4, A6, A13): dispatched once, read-only, its artifact
 * validated with one automatic retry, stored encrypted, posted to the ticket neutralised, and then gated (spec approve)
 * or approved (spec auto). Only the run transport and the pull request proposal are TEST boundaries.
 */
@QuarkusTest
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkSpecRunIT extends WorkSpecRunFixture {
    static final String SPEC = """
            ## Outcome
            TEST-outcome for @TEST-user

            ## Context
            TEST-context: a List<String> of TEST-widgets

            ## Acceptance criteria
            - TEST-criterion

            ## Out of scope
            TEST-nothing

            ## Open questions
            None
            """;
    static final String WITHOUT_CONTEXT = SPEC.replace("## Context\n", "");

    @Inject WorkDraftResults draftResults;
    @Inject RunResultSaga saga;
    @Inject WorkSourceEffects effects;
    @Inject WorkItemControl control;
    @Inject WorkDraftResource draftResource;

    private static RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command, String text) {
        return new RunWorkArtifact(command.runId(), command.draft(), text, false, Map.of("INPUT", 10L), 3);
    }

    private byte[] bytes(String sql, Object value) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next(), sql); return rs.getBytes(1); }
        }
    }

    private UUID draftComment(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT effect_id FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='draft'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next(), "a draft comment is queued"); return rs.getObject(1, UUID.class); }
        }
    }

    private long milestones(String id, String milestone) {
        return store.history(id).stream().map(EventEnvelope::payload).map(WorkItemEvent.class::cast)
                .filter(event -> milestone.equals(event.milestone())).count();
    }

    private List<String> postedComments(int number) throws Exception {
        List<String> bodies = new ArrayList<>();
        for (LoggedRequest request : forge.findAll(postRequestedFor(urlEqualTo(issuePath(number) + "/comments"))))
            bodies.add(mapper.readTree(request.getBodyAsString()).path("body").asText());
        return bodies;
    }

    @Test
    void theSpecRunIsDispatchedReadOnly() throws Exception {
        String id = started("spec-approve", 81);
        UUID attempt = store.load(id).progress().attemptId();

        RunCommand.ExecuteWorkDraftRun command = dispatched();

        assertEquals(WorkDraftBinding.Kind.SPEC, command.draft().kind());
        assertEquals(attempt, command.draft().attemptId());
        assertEquals(HEAD, command.execution().baseCommit());
        assertNull(command.execution().startFromRunId());
        assertTrue(command.execution().prompt().contains(UntrustedText.DATA_HEADING));
        assertTrue(command.execution().prompt().contains("TEST-identical task"));
        assertEquals(1, count("SELECT count(*) FROM factory_run WHERE run_id=? AND kind='SPEC' AND work_item_id=? AND work_phase_attempt_id=?",
                command.runId(), id, attempt));
        assertEquals("sent", effectState(attempt));
    }

    @Test
    void anUncertainDraftDispatchIsNeverResent() throws Exception {
        String id = started("spec-approve", 82);
        UUID attempt = store.load(id).progress().attemptId();
        transportFailure = new IllegalStateException("TEST-broker-unknown");

        dispatched();
        assertEquals("uncertain", effectState(attempt));
        transportFailure = null;
        prepare.drain();

        assertEquals(1, draftRuns.size());
        assertEquals("uncertain", effectState(attempt));
    }

    @Test
    void aValidArtifactIsStoredEncryptedAndGated() throws Exception {
        String id = started("spec-approve", 83);

        saga.on(artifact(dispatched(), SPEC));

        WorkItemEvent item = store.load(id);
        assertState(item, "spec", "waiting_approval", "specification_written");
        assertEquals(WorkPreparation.digest(SPEC), item.gate().artifact());
        assertEquals(WorkPreparation.digest(SPEC), item.draft().specification().sha256());
        assertEquals(HEAD, item.draft().baseCommit());
        assertEquals(1, count("SELECT count(*) FROM work_item_artifact WHERE work_item_id=? AND kind='SPEC'", id));
        String stored = new String(bytes("SELECT body FROM work_item_artifact WHERE work_item_id=? AND kind='SPEC'", id), StandardCharsets.UTF_8);
        assertFalse(stored.contains("TEST-outcome"), "the specification is encrypted at rest");
    }

    @Test
    void aMissingHeadingRetriesOnceThenWaits() throws Exception {
        String id = started("spec-approve", 84);

        saga.on(artifact(dispatched(), WITHOUT_CONTEXT));
        assertState(store.load(id), "spec", "active", "phase_started");
        assertEquals(1, milestones(id, "DRAFT_RETRY_REQUESTED"));
        saga.on(artifact(dispatched(), WITHOUT_CONTEXT));

        assertState(store.load(id), "spec", "awaiting_input", "spec_invalid");
        assertEquals(2, draftRuns.size());
        prepare.drain();
        assertEquals(2, draftRuns.size(), "no third run");
    }

    @Test
    void theRetryPromptNamesTheError() throws Exception {
        String id = started("spec-approve", 85);
        RunCommand.ExecuteWorkDraftRun first = dispatched();

        saga.on(artifact(first, WITHOUT_CONTEXT));
        RunCommand.ExecuteWorkDraftRun retry = dispatched();

        assertTrue(retry.execution().prompt().contains("missing heading: Context"), retry.execution().prompt());
        assertNotEquals(first.draft().attemptId(), retry.draft().attemptId());
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE attempt_id=? AND retry_of=?", retry.draft().attemptId(),
                first.draft().attemptId()));
        String error = new String(bytes("SELECT validation_error FROM work_prepare_effect WHERE attempt_id=?", retry.draft().attemptId()),
                StandardCharsets.UTF_8);
        assertFalse(error.contains("missing heading"), "the validation error is encrypted at rest");
        assertState(store.load(id), "spec", "active", "phase_started");
    }

    @Test
    void aFailedSpecRunWaitsNotFails() throws Exception {
        String id = started("spec-approve", 86);
        RunCommand.ExecuteWorkDraftRun command = dispatched();

        saga.on(new RunResult.RunFailed(command.runId(), "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 1L), List.of()));

        assertState(store.load(id), "spec", "awaiting_input", "draft_run_failed");
        assertEquals("done", effectState(command.draft().attemptId()));
    }

    @Test
    void aForeignArtifactIsRefused() throws Exception {
        String id = started("spec-approve", 87);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        WorkDraftBinding bound = command.draft();
        WorkDraftBinding foreign = new WorkDraftBinding(bound.workItemId(), bound.generation(), bound.attemptId(), bound.kind(), "e".repeat(64));
        long history = store.history(id).size();

        saga.on(new RunWorkArtifact(command.runId(), foreign, SPEC, false, Map.of("INPUT", 10L), 3));

        assertEquals(history, store.history(id).size());
        assertState(store.load(id), "spec", "active", "phase_started");
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE attempt_id=? AND reason='draft_binding_mismatch'", bound.attemptId()));
        assertEquals(0, count("SELECT count(*) FROM work_item_artifact WHERE work_item_id=?", id));
    }

    @Test
    void theSpecificationIsPostedNeutralised() throws Exception {
        String id = started("spec-approve", 88);
        saga.on(artifact(dispatched(), SPEC));

        assertTrue(effects.dispatch(draftComment(id)));

        List<String> posted = postedComments(88);
        assertEquals(1, posted.size());
        String body = posted.getFirst();
        assertTrue(body.startsWith(WorkDraftComments.SPECIFICATION_HEAD), body);
        assertTrue(body.contains(WorkPreparation.digest(SPEC)), body);
        assertTrue(body.contains("\n> ## Outcome\n> TEST-outcome for @​TEST-user"), body);
        assertTrue(body.contains("a List<​String> of TEST-widgets"), body);
        assertFalse(body.contains("&lt;"), body);
        assertEquals("sent", effects.status(draftComment(id)).state());
    }

    @Test
    void approvingTheResultGateMovesToPlan() throws Exception {
        String id = started("spec-approve", 89);
        saga.on(artifact(dispatched(), SPEC));
        WorkGate gate = store.load(id).gate();

        var outcome = transitions.answer(gate.id(), gate.version(), "TEST-spec-approval", true, null, "TEST-spec-admin");

        assertEquals(200, outcome.status(), outcome.reason());
        assertState(store.load(id), "plan", "awaiting_input", "plan_to_write");
        assertTrue(store.load(id).draft().specificationApproved());
        assertEquals(1, draftRuns.size(), "approving runs nothing again");
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE work_item_id=?", id));
    }

    @Test
    void rejectingStops() throws Exception {
        String id = started("spec-approve", 90);
        saga.on(artifact(dispatched(), SPEC));
        WorkGate gate = store.load(id).gate();

        var outcome = transitions.answer(gate.id(), gate.version(), "TEST-spec-rejection", false, null, "TEST-spec-admin");

        assertEquals(200, outcome.status(), outcome.reason());
        assertState(store.load(id), "spec", "stopped", "gate_rejected");
    }

    @Test
    void theSpecCommentSurvivesTheNextDecision() throws Exception {
        String id = started("autonomous", 91);

        saga.on(artifact(dispatched(), SPEC));
        assertState(store.load(id), "plan", "awaiting_input", "plan_to_write");
        assertEquals(1, milestones(id, "DRAFT_APPROVED"));

        assertTrue(effects.dispatch(draftComment(id)));
        assertEquals("sent", effects.status(draftComment(id)).state());
    }

    @Test
    void aRedeliveredArtifactPostsOneComment() throws Exception {
        String id = started("spec-approve", 92);
        RunWorkArtifact result = artifact(dispatched(), SPEC);

        saga.on(result);
        saga.on(result);

        assertEquals(1, count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='draft'", id));
        assertEquals(1, count("SELECT count(*) FROM work_item_artifact WHERE work_item_id=?", id));
        assertEquals(1, milestones(id, "DRAFT_ARTIFACT_WRITTEN"));
    }

    @Test
    void aStoredDraftResultIsAppliedAfterARestart() throws Exception {
        String id = started("spec-approve", 93);
        RunWorkArtifact result = artifact(dispatched(), SPEC);

        assertTrue(draftResults.store(result));
        assertState(store.load(id), "spec", "active", "phase_started");
        draftResults.recover();
        draftResults.recover();

        assertState(store.load(id), "spec", "waiting_approval", "specification_written");
        assertEquals(1, milestones(id, "DRAFT_ARTIFACT_WRITTEN"));
    }

    @Test
    void aLateArtifactAfterRevisionIsIgnored() throws Exception {
        String id = started("spec-approve", 94);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        WorkItemEvent running = store.load(id);
        WorkItemEvent revised = WorkItemLifecycle.state(running, "awaiting_input", "specification_to_write", "DRAFT_REVISED", null,
                running.progress().finish(0, 0, 0)).drafted(running.draft().revised("spec"));
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c, id);
                store.appendDecision(c, store.history(id), revised, "TEST-revised:" + UUID.randomUUID());
            } catch (SQLException | java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        long history = store.history(id).size();

        saga.on(artifact(command, SPEC));

        assertEquals(history, store.history(id).size());
        assertNull(store.load(id).draft().specification());
        assertEquals(0, count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=?", id));
        assertEquals(0, count("SELECT count(*) FROM work_item_artifact WHERE work_item_id=?", id));
        assertEquals("refused", effectState(command.draft().attemptId()));
    }

    @Test
    void anArtifactAfterTakeoverChangesNothing() throws Exception {
        String id = started("spec-approve", 95);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        control.suspend(id, "tracker", "TEST-takeover-" + UUID.randomUUID(), "900123", null);
        long history = store.history(id).size();

        saga.on(artifact(command, SPEC));

        // Only what the run bought is recorded (review of PR 287, H-2; WorkDraftLateUsageIT): one late-usage decision.
        assertEquals(history + 1, store.history(id).size());
        assertEquals("LATE_DRAFT_RESULT", store.load(id).milestone());
        assertNull(store.load(id).draft().specification());
        assertEquals("suspended", store.load(id).workflowStatus());
        assertEquals(0, count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='draft' AND state<>'refused'", id));
    }

    @Test
    void aTakeoverRefusesAPendingSpecComment() throws Exception {
        String id = started("spec-approve", 96);
        saga.on(artifact(dispatched(), SPEC));
        assertEquals("pending", effects.status(draftComment(id)).state());

        control.suspend(id, "tracker", "TEST-takeover-" + UUID.randomUUID(), "900123", null);

        assertEquals("refused", effects.status(draftComment(id)).state());
    }

    @Test
    void aDraftFailureIsNeverProposed() throws Exception {
        started("spec-approve", 97);
        RunCommand.ExecuteWorkDraftRun command = dispatched();

        saga.on(new RunResult.RunFailed(command.runId(), "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 1L), List.of()));

        assertTrue(proposed.isEmpty(), "a draft run is never proposed");
    }

    @Test
    void theSpecRunIsChargedAsSpec() throws Exception {
        String id = started("spec-approve", 98);
        RunCommand.ExecuteWorkDraftRun command = dispatched();

        saga.on(artifact(command, SPEC));

        assertTrue(count("SELECT count(*) FROM llm_charge WHERE subject_kind='RUN' AND subject_id=? AND kind='SPEC'", command.runId()) > 0);
        WorkItemEvent item = store.load(id);
        assertEquals(0, item.progress().runs(), "a specification run is not a build run");
        assertEquals(1, item.progress().calls());
        assertEquals(3, item.progress().wallSeconds());
        assertFalse(item.progress().usageUnknown());
    }

    @Test
    void theDraftResourceShowsTheSpecification() throws Exception {
        String id = started("spec-approve", 99);
        saga.on(artifact(dispatched(), SPEC));
        effects.dispatch(draftComment(id));

        WorkDraftResource.View view = draftResource.read(id);

        assertEquals(SPEC, view.specification().text());
        assertEquals(WorkPreparation.digest(SPEC), view.specification().sha256());
        assertEquals("sent", view.specification().comment().state());
        assertNull(view.plan());
        assertEquals(store.load(id).issue().link().toString(), view.ticketLink());
    }
}
