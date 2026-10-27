package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.WorkDraft;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkPolicyLimits;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.contract.work.WorkProgress;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Readmit and Regenerate of a generated item (M4 slice 3, §6.11, A7): readmit reuses the approved specification and plan
 * and spends nothing on them; Regenerate clears them and writes a new draft, and is refused wherever readmit is refused.
 * Only the buses are TEST boundaries.
 */
@QuarkusTest
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkDraftReadmitIT extends WorkSpecRunFixture {
    static final PullRequestRef PR = new PullRequestRef(903, "https://forge.example.test/TEST-pull/903", false);

    @Inject RunResultSaga saga;

    @BeforeEach
    void readmitSetup() {
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                CHECKS, null, BuildDefaults.GENERATED), "TEST-spec-admin");
    }

    private String planSteps(int count) {
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", 2).put("specificationSha256", WorkPreparation.digest(WorkSpecRunIT.SPEC));
        com.fasterxml.jackson.databind.node.ArrayNode steps = root.putArray("steps");
        for (int step = 1; step <= count; step++)
            steps.addObject().put("id", "TEST-s" + step).put("title", "TEST-step-" + step).put("instruction", "TEST-instruction-" + step)
                    .put("doneWhen", "TEST-done-" + step);
        return root.toString();
    }

    private static RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command, String text) {
        return new RunWorkArtifact(command.runId(), command.draft(), text, false, Map.of("INPUT", 10L), 3);
    }

    /**
     * A generated item whose specification and plan were written, approved and registered, and whose first build failed
     * before the agent ran: a failure that bought nothing leaves the usage known, so a readmission may enter build again.
     */
    private String failedAfterItsDraft(int number) throws Exception {
        return failedAfterItsDraft(number, 1);
    }

    private String failedAfterItsDraft(int number, int steps) throws Exception {
        String id = started("autonomous", number);
        saga.on(artifact(dispatched(), WorkSpecRunIT.SPEC));
        draftSweep.sweep();
        saga.on(artifact(dispatched(), planSteps(steps)));
        assertState(store.load(id), "build", "active", "phase_started");
        dispatcher.drain();
        saga.on(new RunResult.RunFailed(heldCommands.getLast().runId(), "BAD_COMMAND", "TEST BAD_COMMAND", false, null));
        assertState(store.load(id), "build", "failed", "phase_failed");
        return id;
    }

    private ValidatableResponse readmit(String id, boolean regenerate) {
        Map<String, Object> body = Map.of("expectedRevision", store.history(id).size(), "readmit", true, "regenerate", regenerate);
        return given().contentType("application/json").body(body).post("/api/work-items/" + id + "/resume").then();
    }

    @Test
    void readmitReusesTheApprovedDraft() throws Exception {
        String id = failedAfterItsDraft(401);
        WorkItemEvent before = store.load(id);
        int drafts = draftRuns.size(), builds = heldCommands.size();

        readmit(id, false).statusCode(200);
        draftSweep.sweep();
        prepare.drain();
        dispatcher.drain();

        WorkItemEvent item = store.load(id);
        assertEquals(before.generation() + 1, item.generation());
        assertState(item, "build", "active", "phase_started");
        assertEquals(before.draft(), item.draft());
        assertEquals(before.preparation(), item.preparation());
        assertEquals(0, item.steps().index(), "the plan builds again from step 1");
        assertEquals(drafts, draftRuns.size(), "no specification or plan run is bought again");
        assertEquals(2, count("SELECT count(*) FROM factory_run WHERE work_item_id=? AND kind IN ('SPEC','PLAN')", id));
        assertEquals(builds + 1, heldCommands.size());
        assertEquals(item.generation(), heldCommands.getLast().work().generation());
    }

    /**
     * A8 on readmission: the approved plan has more steps than the step limit of the policy selected now, so reusing it
     * would build part of it and stop at the cap. The next generation writes its specification and plan again.
     */
    @Test
    void readmitUnderALowerStepLimitWritesThePlanAgain() throws Exception {
        String id = failedAfterItsDraft(405, 2);
        assertEquals(2, store.load(id).steps().total());
        long generation = store.load(id).generation();
        int drafts = draftRuns.size();
        lowerTheStepLimit(1);

        readmit(id, false).statusCode(200);

        WorkItemEvent item = store.load(id);
        assertEquals(generation + 1, item.generation());
        assertEquals(WorkDraft.admitted(), item.draft());
        assertNull(item.preparation());
        assertNull(item.steps(), "no cursor of the discarded plan is kept");
        draftSweep.sweep();
        assertState(store.load(id), "spec", "active", "phase_started");
        assertEquals(WorkDraftBinding.Kind.SPEC, dispatched().draft().kind());
        assertEquals(drafts + 1, draftRuns.size());
    }

    /** The factory writes a generated item's specification and plan: a hand-registered or copied task is refused. */
    @Test
    void aGeneratedItemCannotBePreparedByHand() throws Exception {
        String id = admit("autonomous", 406);
        assertNotNull(store.load(id).draft());
        long revision = store.history(id).size();

        WorkItemTransitions.Outcome refused = transitions.prepare(id, revision, preparation("TEST-human"));

        assertEquals(409, refused.status());
        assertEquals("preparation_generated", refused.reason());
        assertEquals(revision, store.history(id).size());
        assertNull(store.load(id).preparation());
    }

    @Test
    void regenerateWritesANewDraft() throws Exception {
        String id = failedAfterItsDraft(402);
        long generation = store.load(id).generation();
        int drafts = draftRuns.size();

        readmit(id, true).statusCode(200).body("reason", is("specification_to_write"));

        WorkItemEvent item = store.load(id);
        assertState(item, "spec", "awaiting_input", "specification_to_write");
        assertEquals(generation + 1, item.generation());
        assertEquals(WorkDraft.admitted(), item.draft());
        assertNull(item.preparation());
        draftSweep.sweep();
        RunCommand.ExecuteWorkDraftRun spec = dispatched();
        assertEquals(drafts + 1, draftRuns.size());
        assertEquals(WorkDraftBinding.Kind.SPEC, spec.draft().kind());
        assertEquals(generation + 1, spec.draft().generation());
        assertState(store.load(id), "spec", "active", "phase_started");
    }

    /** Waiting at land, the delivered pull request is still open: Regenerate is refused there with readmit's own reason. */
    @Test
    void regenerateIsRefusedWhereReadmitIs() throws Exception {
        String id = admit("autonomous", 403);
        assertNotNull(store.load(id).draft());
        WorkItemEvent land = atLand(id);
        append(id, land);
        append(id, WorkItemLifecycle.landHanded(land, "land_blocked"));
        long revision = store.history(id).size();

        readmit(id, true).statusCode(409).body("reason", is("readmission_land_pending"));

        assertEquals(revision, store.history(id).size());
    }

    @Test
    void aComposedItemCannotRegenerate() throws Exception {
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                CHECKS, null, BuildDefaults.COMPOSED), "TEST-spec-admin");
        String id = admit("autonomous", 404);
        assertNull(store.load(id).draft());
        long revision = store.history(id).size(), generation = store.load(id).generation();

        readmit(id, true).statusCode(409).body("reason", is("regenerate_unavailable"));

        assertEquals(revision, store.history(id).size());
        assertEquals(generation, store.load(id).generation());
    }

    /** A second version of the autonomous profile with a lower step limit, pinned as both its label and the ceiling. */
    private void lowerTheStepLimit(long steps) {
        WorkPolicy.Profile autonomous = profiles.get("autonomous");
        WorkPolicyLimits was = autonomous.limits();
        WorkPolicy.Profile lowered = policies.createVersion(new WorkPolicy.Profile(autonomous.id(), autonomous.name(), 2, autonomous.precedence(),
                autonomous.modes(), new WorkPolicyLimits(was.gateTtlSeconds(), was.maxRunsPerItem(), steps, was.maxWallClockSeconds(),
                was.maxCostMillicents(), was.maxCallsPerItem(), was.protectedPaths())));
        WorkPolicyRegistry.Policy policy = policies.get(repository);
        Map<String, WorkPolicyRegistry.Pin> mappings = new HashMap<>();
        policy.mappings().forEach((label, profile) -> mappings.put(label, new WorkPolicyRegistry.Pin(profile.id(), profile.version())));
        WorkPolicyRegistry.Pin pin = new WorkPolicyRegistry.Pin(lowered.id(), lowered.version());
        mappings.put("TEST-autonomous", pin);
        policies.save(repository, new WorkPolicyRegistry.Input(policy.revision(), pin, mappings));
    }

    private WorkItemEvent atLand(String id) {
        WorkItemEvent item = store.load(id);
        WorkExecution execution = new WorkExecution("TEST-land-run", new WorkRunBinding(id, item.generation(), UUID.randomUUID(), "a".repeat(64)),
                "b".repeat(40), null, null, PR, "TEST-review");
        WorkProgress progress = item.progress().start(UUID.randomUUID(), "land", Instant.EPOCH).withExecution(execution);
        return item.decision(item.policyRevision(), item.authority(), item.policy(), "land", "active", "phase_started", "PHASE_STARTED", null, progress);
    }

    private void append(String id, WorkItemEvent next) {
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c, id);
                store.appendDecision(c, store.history(id), next, "TEST-readmit-fixture:" + UUID.randomUUID());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
}
