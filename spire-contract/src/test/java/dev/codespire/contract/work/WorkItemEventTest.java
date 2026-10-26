package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A component on the work event survives every wither (CLAUDE.md: a wire record's rebuild sites drop it silently). */
class WorkItemEventTest {
    static WorkItemEvent item() {
        return item(new WorkPolicyLimits(60, 5, 20, 7200, 2_000_000, 40, Set.of()));
    }

    static WorkItemEvent item(WorkPolicyLimits limits) {
        Map<WorkPolicy.Phase, String> modes = Map.of(WorkPolicy.Phase.INTAKE, "auto", WorkPolicy.Phase.SPEC, "auto");
        WorkPolicy.Profile profile = new WorkPolicy.Profile(UUID.randomUUID(), "TEST-policy", 1, 1, modes, limits);
        WorkPolicy.Selection selection = new WorkPolicy.Selection(profile, profile.modes(), List.of(), "policy_selected", List.of(), profile, profile.limits());
        WorkIssueLocation issue = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB, "https://tracker.example.test", "TEST-project",
                "TEST-issue"), "TEST-42", URI.create("https://tracker.example.test/TEST-42"));
        return new WorkItemEvent("TEST-item", UUID.randomUUID(), UUID.randomUUID(), issue, 1, 1, profile, profile.modes(),
                new WorkItemEvent.Authority(UUID.randomUUID(), 1, 1, 1), selection, "spec", "awaiting_input", "specification_to_write");
    }

    @Test
    void everyWitherCarriesTheDraft() {
        WorkDraft draft = WorkDraft.admitted();
        WorkItemEvent drafted = item().drafted(draft);

        assertEquals(draft, drafted.withMilestone("TEST-MILESTONE").draft(), "withMilestone");
        assertEquals(draft, drafted.decision(2, drafted.authority(), drafted.policy(), "spec", "active", "phase_started",
                "PHASE_STARTED", null, drafted.progress()).draft(), "decision");
        assertEquals(draft, drafted.readmit().draft(), "readmit");
        assertEquals(draft, drafted.controlled(null).draft(), "controlled");
        assertEquals(draft, drafted.prepared(null).draft(), "prepared");
    }

    @Test
    void everyWitherCarriesTheSteps() {
        WorkSteps steps = WorkSteps.of(3);
        WorkItemEvent stepped = item().stepped(steps);

        assertEquals(steps, stepped.withMilestone("TEST-MILESTONE").steps(), "withMilestone");
        assertEquals(steps, stepped.decision(2, stepped.authority(), stepped.policy(), "build", "active", "phase_started",
                "PHASE_STARTED", null, stepped.progress()).steps(), "decision");
        assertEquals(steps, stepped.controlled(null).steps(), "controlled");
        assertEquals(steps, stepped.prepared(null).steps(), "prepared");
        assertEquals(steps, stepped.drafted(WorkDraft.admitted()).steps(), "drafted");
    }

    /** Decision 12: the next generation builds the plan again from step 1, with its step count back at 0. */
    @Test
    void readmitResetsTheCursorAndTheStepCount() {
        WorkSteps two = WorkSteps.of(3).completedWith(WorkStepsTest.step(0, "TEST-run-1", "b".repeat(40)))
                .completedWith(WorkStepsTest.step(1, "TEST-run-2", "c".repeat(40)));
        WorkItemEvent built = item();
        WorkProgress used = new WorkProgress(null, null, null, false, null, 4, 2, 30, 500, 6);
        built = built.decision(1, built.authority(), built.policy(), "build", "stopped", "TEST-stopped", "TEST", null, used).stepped(two);

        WorkItemEvent readmitted = built.readmit();

        assertEquals(new WorkSteps(3, 0, List.of(), Set.of()), readmitted.steps());
        assertEquals(0, readmitted.progress().steps());
        assertEquals(4, readmitted.progress().runs(), "runs survive a generation change");
        assertEquals(500, readmitted.progress().costMillicents(), "spend survives a generation change");
    }

    static WorkDraft approvedDraft() {
        return WorkDraft.admitted().withSpecification(new WorkDraft.Written(WorkDraftTest.stored('a'), "b".repeat(40), 1))
                .approved("spec").withPlan(WorkDraftTest.stored('c'), 1).approved("plan");
    }

    /** A7: an approved draft and the preparation registered from it travel on, so readmit spends nothing on them. */
    @Test
    void readmitKeepsAnApprovedDraft() {
        WorkDraft approved = approvedDraft();
        WorkPreparation preparation = WorkDraftLifecycleTest.preparation(approved);

        WorkItemEvent readmitted = item().drafted(approved).prepared(preparation).readmit();

        assertEquals(approved, readmitted.draft());
        assertEquals(preparation, readmitted.preparation());
    }

    /** Nothing approved is registered yet, so the next generation writes its specification again from an empty draft. */
    @Test
    void readmitDropsAnUnapprovedDraft() {
        WorkDraft specified = WorkDraft.admitted().withSpecification(new WorkDraft.Written(WorkDraftTest.stored('a'), "b".repeat(40), 1));

        assertEquals(WorkDraft.admitted(), item().drafted(specified).readmit().draft());
        assertEquals(WorkDraft.admitted(), item().drafted(approvedDraft()).readmit().draft(), "approved, but never registered");
    }

    /**
     * A hand-composed preparation beside an unwritten draft was never registered from it: readmit drops both, so the
     * next generation writes its specification instead of parking with a preparation no draft run can reach.
     */
    @Test
    void readmitDropsAPreparationNotRegisteredFromTheDraft() {
        WorkDraft approved = approvedDraft();
        WorkPreparation composed = new WorkPreparation(approved.specification(), approved.plan(), "main", "b".repeat(40), "codex",
                "TEST-model", "TEST-operator", WorkPreparation.VERIFY_BINDING, null, null, List.of("TEST-check"), 60);

        WorkItemEvent readmitted = item().drafted(WorkDraft.admitted()).prepared(composed).stepped(WorkSteps.of(2)).readmit();

        assertEquals(WorkDraft.admitted(), readmitted.draft());
        assertNull(readmitted.preparation());
        assertNull(readmitted.steps(), "no cursor outlives the plan it counted");
    }

    /**
     * A8: a reused plan must fit the step limit of the policy readmission selects now, or the item builds part of it and
     * stops at the cap; a plan longer than that limit is written again instead.
     */
    @Test
    void readmitWritesAgainAPlanLongerThanTheStepLimit() {
        WorkDraft approved = approvedDraft();
        // Runs enough for every step, so only the step limit decides.
        WorkItemEvent registered = item(new WorkPolicyLimits(60, 40, 20, 7200, 2_000_000, 40, Set.of())).drafted(approved)
                .prepared(WorkDraftLifecycleTest.preparation(approved));

        WorkItemEvent tooLong = registered.stepped(WorkSteps.of(21)).readmit();
        WorkItemEvent fits = registered.stepped(WorkSteps.of(20)).readmit();

        assertEquals(WorkDraft.admitted(), tooLong.draft());
        assertNull(tooLong.preparation());
        assertNull(tooLong.steps());
        assertEquals(approved, fits.draft(), "a plan of exactly the limit is reused");
        assertEquals(WorkSteps.of(20), fits.steps());
    }

    /**
     * A8, A11: every step builds in at least one run and runs survive readmission, so a reused plan must fit the runs the
     * item has left as well as the step limit; a 3-step plan with 2 of 5 runs left is written again.
     */
    @Test
    void readmitWritesAgainAPlanLongerThanTheRunsLeft() {
        WorkDraft approved = approvedDraft();
        WorkItemEvent registered = item().drafted(approved).prepared(WorkDraftLifecycleTest.preparation(approved));
        WorkProgress used = new WorkProgress(null, null, null, false, null, 3, 0, 30, 500, 6);
        registered = registered.decision(1, registered.authority(), registered.policy(), "build", "stopped", "TEST-stopped", "TEST", null, used);

        WorkItemEvent tooLong = registered.stepped(WorkSteps.of(3)).readmit();
        WorkItemEvent fits = registered.stepped(WorkSteps.of(2)).readmit();

        assertEquals(WorkDraft.admitted(), tooLong.draft());
        assertNull(tooLong.preparation());
        assertNull(tooLong.steps());
        assertEquals(approved, fits.draft(), "a plan of exactly the runs left is reused");
        assertEquals(WorkSteps.of(2), fits.steps());
    }

    @Test
    void stepsSurviveTheStoredEventRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkItemEvent stepped = item().stepped(WorkSteps.of(3).completedWith(WorkStepsTest.step(0, "TEST-run-1", "b".repeat(40))).lostCheckpoint("TEST-run-0"));
        assertEquals(stepped, mapper.readValue(mapper.writeValueAsBytes(stepped), WorkItemEvent.class));
    }

    @Test
    void aDraftSurvivesTheStoredEventRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkItemEvent drafted = item().drafted(WorkDraft.admitted());
        assertEquals(drafted, mapper.readValue(mapper.writeValueAsBytes(drafted), WorkItemEvent.class));
    }

    @Test
    void anOlderEventWithoutADraftStillReplays() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkItemEvent item = item();
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(item);
        json.remove("draft");
        json.remove("steps");
        assertEquals(item, mapper.treeToValue(json, WorkItemEvent.class));
    }
}
