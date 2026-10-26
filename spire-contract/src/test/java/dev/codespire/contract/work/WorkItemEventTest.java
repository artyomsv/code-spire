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
        Map<WorkPolicy.Phase, String> modes = Map.of(WorkPolicy.Phase.INTAKE, "auto", WorkPolicy.Phase.SPEC, "auto");
        WorkPolicy.Profile profile = new WorkPolicy.Profile(UUID.randomUUID(), "TEST-policy", 1, 1, modes,
                new WorkPolicyLimits(60, 5, 20, 7200, 2_000_000, 40, Set.of()));
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
