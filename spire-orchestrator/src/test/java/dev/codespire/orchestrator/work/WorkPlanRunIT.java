package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.ResolveGate;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The plan run of a generated item, its revisions and its registration (M4 slice 3, §6.4, §6.5, A4, A6, A8, A14,
 * decisions 8 and 15): started by the draft sweep from the approved specification, validated with one automatic retry,
 * gated on its digest, revised only from the dashboard, and registered into build as a binding-6 preparation.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkPlanRunIT extends WorkSpecRunFixture {
    static final String SPEC = WorkSpecRunIT.SPEC;
    static final String SPEC_SHA = WorkPreparation.digest(SPEC);

    @Inject RunResultSaga saga;
    @Inject WorkSourceEffects effects;
    @Inject WorkGateResource gates;
    @Inject WorkPrepareReads reads;
    @Inject WorkDraftResource draftResource;

    private static RunResult.RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command, String text) {
        return new RunResult.RunWorkArtifact(command.runId(), command.draft(), text, false, Map.of("INPUT", 10L), 3);
    }

    /** A schema-version-2 plan of the given step titles, bound to the TEST specification. */
    private String plan(String digest, List<String> titles) {
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", 2).put("specificationSha256", digest);
        ArrayNode steps = root.putArray("steps");
        for (int n = 0; n < titles.size(); n++)
            steps.addObject().put("id", "TEST-s" + (n + 1)).put("title", titles.get(n)).put("instruction", "TEST-instruction-" + (n + 1))
                    .put("doneWhen", "TEST-done-" + (n + 1));
        return root.toString();
    }

    private String plan(int steps) {
        List<String> titles = new ArrayList<>();
        for (int n = 1; n <= steps; n++) titles.add("TEST-step-" + n);
        return plan(SPEC_SHA, titles);
    }

    private long milestones(String id, String milestone) {
        return store.history(id).stream().map(EventEnvelope::payload).map(WorkItemEvent.class::cast)
                .filter(event -> milestone.equals(event.milestone())).count();
    }

    private WorkItemTransitions.Outcome answer(String id, boolean approve, String note) {
        WorkGate gate = store.load(id).gate();
        return transitions.answer(gate.id(), gate.version(), "TEST-answer-" + UUID.randomUUID(), approve, note, "TEST-spec-admin");
    }

    private Response revise(String id, String note) {
        WorkGate gate = store.load(id).gate();
        return gates.revise(gate.id(), new WorkGateResource.Revision(gate.version(), "TEST-revise-" + UUID.randomUUID(), note));
    }

    /** The specification written and approved (by its gate under spec approve), then the plan run started by the sweep. */
    private String planRunning(String profile, int number) throws Exception {
        String id = started(profile, number);
        saga.on(artifact(dispatched(), SPEC));
        if ("spec-approve".equals(profile)) assertEquals(200, answer(id, true, null).status());
        assertState(store.load(id), "plan", "awaiting_input", "plan_to_write");
        draftSweep.sweep();
        assertState(store.load(id), "plan", "active", "phase_started");
        return id;
    }

    private UUID planAttempt(String id) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT attempt_id FROM work_prepare_effect WHERE work_item_id=? AND kind='PLAN' ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next(), "a plan effect row exists"); return rs.getObject(1, UUID.class); }
        }
    }

    private List<String> postedComments(int number) throws Exception {
        List<String> bodies = new ArrayList<>();
        for (LoggedRequest request : forge.findAll(postRequestedFor(urlEqualTo(issuePath(number) + "/comments"))))
            bodies.add(mapper.readTree(request.getBodyAsString()).path("body").asText());
        return bodies;
    }

    /** The posted plan comment; the specification comment of the same item may be posted beside it. */
    private String planComment(int number) throws Exception {
        return postedComments(number).stream().filter(body -> body.startsWith(WorkDraftComments.PLAN_HEAD)).findFirst()
                .orElseThrow(() -> new AssertionError("no plan comment was posted"));
    }

    @Test
    void theSweepStartsThePlanRun() throws Exception {
        String id = started("autonomous", 121);
        saga.on(artifact(dispatched(), SPEC));
        assertState(store.load(id), "plan", "awaiting_input", "plan_to_write");

        draftSweep.sweep();

        assertState(store.load(id), "plan", "active", "phase_started");
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE work_item_id=? AND kind='PLAN' AND state='pending'", id));
    }

    @Test
    void aValidPlanIsGatedOnItsDigest() throws Exception {
        String id = planRunning("plan-approve", 122);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        assertEquals(WorkDraftBinding.Kind.PLAN, command.draft().kind());
        assertEquals(HEAD, command.execution().baseCommit(), "the plan reads the commit the specification read");
        String text = plan(2);

        saga.on(artifact(command, text));

        WorkItemEvent item = store.load(id);
        assertState(item, "plan", "waiting_approval", "plan_written");
        assertEquals(WorkPreparation.digest(text), item.gate().artifact());
        assertEquals(WorkPreparation.digest(text), item.draft().plan().sha256());
        assertTrue(effects.dispatch(WorkSourceEffects.draftId(command.draft().attemptId())));
        String posted = planComment(122);
        assertTrue(posted.startsWith(WorkDraftComments.PLAN_HEAD), posted);
        assertTrue(posted.contains("1. TEST-step-1\n2. TEST-step-2"), posted);
        assertTrue(posted.contains(WorkPreparation.digest(text)), posted);
    }

    @Test
    void anInvalidPlanRetriesOnceThenWaits() throws Exception {
        String id = planRunning("plan-approve", 123);
        String foreign = plan("e".repeat(64), List.of("TEST-step-1"));

        saga.on(artifact(dispatched(), foreign));
        assertState(store.load(id), "plan", "active", "phase_started");
        RunCommand.ExecuteWorkDraftRun retry = dispatched();
        assertTrue(retry.execution().prompt().contains("specificationSha256"), retry.execution().prompt());
        saga.on(artifact(retry, foreign));

        assertState(store.load(id), "plan", "awaiting_input", "plan_invalid");
        assertEquals(3, draftRuns.size(), "the specification run and two plan runs");
        assertNull(store.load(id).draft().plan());
    }

    @Test
    void approvingTheOneStepPlanRegistersAndBuilds() throws Exception {
        String id = planRunning("plan-approve", 124);
        String text = plan(1);
        saga.on(artifact(dispatched(), text));

        WorkItemTransitions.Outcome outcome = answer(id, true, null);

        assertEquals(200, outcome.status(), outcome.reason());
        WorkItemEvent item = store.load(id);
        assertEquals(1, milestones(id, "DRAFT_REGISTERED"));
        assertState(item, "build", "active", "phase_started");
        WorkPreparation preparation = item.preparation();
        assertEquals(WorkPreparation.STEPS_BINDING, preparation.bindingVersion());
        assertEquals(WorkPreparation.Origin.STORED, preparation.specification().origin());
        assertEquals(WorkPreparation.Origin.STORED, preparation.plan().origin());
        assertEquals(SPEC_SHA, preparation.specification().sha256());
        assertEquals(WorkPreparation.digest(text), preparation.plan().sha256());
        assertEquals(item.draft().baseCommit(), preparation.baseCommit());
        assertTrue(preparation.registeredBy().startsWith("system:draft@"), preparation.registeredBy());
        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?", id));
    }

    /** Under plan auto the plan written is registered at once, past the spec and plan attempts of this generation (A4). */
    @Test
    void anAutomaticPlanRegistersToo() throws Exception {
        String id = planRunning("spec-approve", 125);

        saga.on(artifact(dispatched(), plan(1)));

        assertEquals(1, milestones(id, "DRAFT_REGISTERED"));
        assertState(store.load(id), "build", "active", "phase_started");
    }

    @Test
    void aMultiStepPlanWaitsUntilStepsExist() throws Exception {
        String id = planRunning("plan-approve", 126);
        saga.on(artifact(dispatched(), plan(3)));

        answer(id, true, null);

        WorkItemEvent item = store.load(id);
        assertState(item, "plan", "awaiting_input", "plan_steps_unavailable");
        assertNull(item.preparation());
        assertEquals(0, count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?", id));
    }

    @Test
    void onlyTheDashboardMayRevise() throws Exception {
        String id = started("spec-approve", 127);
        saga.on(artifact(dispatched(), SPEC));
        WorkGate gate = store.load(id).gate();

        WorkItemTransitions.Outcome outcome = transitions.answer(new ResolveGate(gate.id(), gate.version(), "TEST-tracker-revise", false,
                "TEST-note", "900123", ResolveGate.Channel.TRACKER, gate.generation(), gate.artifact()));

        assertEquals(200, outcome.status(), outcome.reason());
        assertState(store.load(id), "spec", "stopped", "gate_rejected");
        assertEquals(0, milestones(id, "DRAFT_REVISED"));
    }

    @Test
    void theFourthRevisionIsRefused() throws Exception {
        String id = started("spec-approve", 128);
        saga.on(artifact(dispatched(), SPEC));
        for (int revision = 1; revision <= 3; revision++) {
            assertEquals(200, revise(id, "TEST-note-" + revision).getStatus());
            draftSweep.sweep();
            saga.on(artifact(dispatched(), SPEC));
            assertState(store.load(id), "spec", "waiting_approval", "specification_written");
        }

        Response refused = revise(id, "TEST-note-4");

        assertEquals(409, refused.getStatus());
        assertEquals(Map.of("reason", "revision_limit_reached"), refused.getEntity());
        assertEquals("OPEN", store.load(id).gate().state());
        assertEquals(3, store.load(id).draft().specificationRevisions());
    }

    @Test
    void aReviseNeedsANote() throws Exception {
        String id = started("spec-approve", 129);
        saga.on(artifact(dispatched(), SPEC));

        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> revise(id, " "));
        assertEquals("OPEN", store.load(id).gate().state());
    }

    @Test
    void aTicketEditAfterApprovalChangesNothing() throws Exception {
        String id = started("spec-approve", 130);
        saga.on(artifact(dispatched(), SPEC));
        assertEquals(200, answer(id, true, null).status());
        stubTicket("spec-approve", 130, "TEST-edited body after the approval");

        draftSweep.sweep();
        RunCommand.ExecuteWorkDraftRun command = dispatched();

        String prompt = command.execution().prompt();
        assertTrue(prompt.contains("TEST-outcome for @TEST-user"), prompt);
        assertTrue(prompt.contains(SPEC_SHA), prompt);
        assertFalse(prompt.contains("TEST-edited body"), prompt);
    }

    @Test
    void aSetupChangeBeforeRegistrationRefuses() throws Exception {
        String id = planRunning("plan-approve", 131);
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        BuildDefaults.Defaults setup = defaults.get(repository);
        defaults.save(repository, new BuildDefaults.Input(setup.revision(), "main", "codex", model, null, null, List.of("TEST-check-2"), null,
                BuildDefaults.GENERATED), "TEST-spec-admin");
        saga.on(artifact(command, plan(1)));

        answer(id, true, null);

        WorkItemEvent item = store.load(id);
        assertState(item, "plan", "awaiting_input", "draft_setup_changed");
        assertNull(item.preparation());
        assertEquals(0, milestones(id, "DRAFT_REGISTERED"));
    }

    @Test
    void aTicketEditDoesNotRefuseRegistration() throws Exception {
        String id = planRunning("plan-approve", 132);
        saga.on(artifact(dispatched(), plan(1)));
        stubTicket("plan-approve", 132, "TEST-edited body before the approval");

        assertEquals(200, answer(id, true, null).status());

        assertEquals(1, milestones(id, "DRAFT_REGISTERED"));
    }

    @Test
    void aRevisedItemIsPickedUpByTheNextSweep() throws Exception {
        String id = started("spec-approve", 133);
        saga.on(artifact(dispatched(), SPEC));

        assertEquals(200, revise(id, "TEST-note").getStatus());
        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
        assertEquals(1, milestones(id, "DRAFT_REVISED"));
        draftSweep.sweep();

        UUID attempt = store.load(id).progress().attemptId();
        assertEquals(2, count("SELECT count(*) FROM work_prepare_effect WHERE work_item_id=? AND kind='SPEC'", id));
        assertEquals("TEST-note", reads.effect(attempt).note());
        byte[] stored;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT note FROM work_prepare_effect WHERE attempt_id=?")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); stored = rs.getBytes(1); }
        }
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("TEST-note"), "the note is encrypted at rest");
        assertTrue(dispatched().execution().prompt().contains("```\nTEST-note\n```"));
    }

    /** Only a revision's note reaches the next run; an approval's note is the approver's record, not an instruction. */
    @Test
    void anApprovalNoteIsNotARevisionNote() throws Exception {
        String id = started("spec-approve", 134);
        saga.on(artifact(dispatched(), SPEC));
        assertEquals(200, answer(id, true, "TEST-approval-note").status());

        draftSweep.sweep();

        assertNull(reads.effect(planAttempt(id)).note());
    }

    @Test
    void aRevisedPlanIsWrittenAgain() throws Exception {
        String id = planRunning("plan-approve", 135);
        saga.on(artifact(dispatched(), plan(1)));

        assertEquals(200, revise(id, "TEST-plan-note").getStatus());
        assertState(store.load(id), "plan", "awaiting_input", "plan_to_write");
        assertNull(store.load(id).draft().plan());
        assertNotNull(store.load(id).draft().specification(), "the approved specification stays");
        draftSweep.sweep();

        assertTrue(dispatched().execution().prompt().contains("```\nTEST-plan-note\n```"));
    }

    @Test
    void thePlanCommentIsNeutralised() throws Exception {
        String id = planRunning("plan-approve", 136);
        RunCommand.ExecuteWorkDraftRun command = dispatched();

        saga.on(artifact(command, plan(SPEC_SHA, List.of("@TEST-user <b>x</b>"))));

        assertTrue(effects.dispatch(WorkSourceEffects.draftId(command.draft().attemptId())));
        String posted = planComment(136);
        assertTrue(posted.contains("1. @​TEST-user &lt;b>x&lt;/b>"), posted);
        assertFalse(posted.contains("<b>"), posted);
        assertState(store.load(id), "plan", "waiting_approval", "plan_written");
    }

    @Test
    void theDraftResourceShowsThePlanAndItsSteps() throws Exception {
        String id = planRunning("plan-approve", 137);
        String text = plan(2);
        saga.on(artifact(dispatched(), text));

        WorkDraftResource.View view = draftResource.read(id);

        assertEquals(WorkPreparation.digest(text), view.plan().sha256());
        assertEquals(List.of("TEST-step-1", "TEST-step-2"), view.plan().steps().stream().map(WorkArtifacts.Step::title).toList());
        assertEquals("TEST-done-2", view.plan().steps().get(1).doneWhen());
        assertEquals(SPEC, view.specification().text());
    }
}
