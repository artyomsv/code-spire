package dev.codespire.contract.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What a gate binds for a generated item's specification and plan (M4 slice 3, A6). */
class WorkGateTest {
    @Test
    void aDraftGateBindsTheArtifactDigest() {
        WorkPreparation.Artifact specification = WorkDraftTest.stored('a');
        WorkDraft draft = new WorkDraft(WorkDraft.GENERATED, specification, null, 0, 0, false, false, "b".repeat(40), 1);
        WorkItemEvent item = WorkItemEventTest.item().drafted(draft);

        assertEquals(specification.sha256(), WorkGate.artifactOf(item));
    }

    @Test
    void aPlanGateWithNoPlanWrittenBindsNothing() {
        WorkDraft draft = new WorkDraft(WorkDraft.GENERATED, WorkDraftTest.stored('a'), null, 0, 0, false, false, "b".repeat(40), 1);
        WorkItemEvent item = WorkItemEventTest.item().drafted(draft);
        WorkItemEvent atPlan = item.decision(1, item.authority(), item.policy(), "plan", "awaiting_input", "plan_to_write", "TEST", null, item.progress());

        assertNull(WorkGate.artifactOf(atPlan));
    }
}
