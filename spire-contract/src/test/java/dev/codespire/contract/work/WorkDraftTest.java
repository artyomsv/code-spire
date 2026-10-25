package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The draft fact of a generated item (M4 slice 3, A4): stored references only, bounded revisions. */
class WorkDraftTest {
    static final WorkIssueLocation ISSUE = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB, "https://tracker.example.test",
            "TEST-project", "TEST-issue"), "TEST-42", URI.create("https://tracker.example.test/TEST-42"));

    static WorkPreparation.Artifact stored(char digit) {
        return new WorkPreparation.Artifact(ISSUE, String.valueOf(digit).repeat(64), WorkPreparation.Origin.STORED, UUID.randomUUID());
    }

    @Test
    void aStoredArtifactOnly() {
        WorkPreparation.Artifact tracker = new WorkPreparation.Artifact(ISSUE, "a".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraft(WorkDraft.GENERATED, tracker, null, 0, 0, false, false, "b".repeat(40), 1));
    }

    @Test
    void anAdmittedDraftHasWrittenNothing() {
        WorkDraft draft = WorkDraft.admitted();
        assertEquals(WorkDraft.GENERATED, draft.mode());
        assertNull(draft.artifactFor("spec"));
        assertNull(draft.artifactFor("plan"));
        assertEquals(0, draft.setupRevision());
    }

    @Test
    void aSpecificationClearsThePlanAndApprovals() {
        WorkDraft first = WorkDraft.admitted().withSpecification(new WorkDraft.Written(stored('a'), "b".repeat(40), 1))
                .approved("spec").withPlan(stored('c'), 1).approved("plan");
        assertTrue(first.planApproved());

        WorkDraft second = first.withSpecification(new WorkDraft.Written(stored('d'), "e".repeat(40), 2));

        assertNull(second.plan());
        assertFalse(second.specificationApproved());
        assertFalse(second.planApproved());
        assertEquals(2, second.setupRevision());
        assertEquals("d".repeat(64), second.artifactFor("spec").sha256());
    }

    @Test
    void revisionsStopAtThree() {
        WorkDraft draft = WorkDraft.admitted().revised("spec").revised("spec").revised("spec");
        assertEquals(3, draft.specificationRevisions());
        assertThrows(IllegalStateException.class, () -> draft.revised("spec"));
    }

    @Test
    void revisingTheSpecificationClearsThePlan() {
        WorkDraft draft = WorkDraft.admitted().withSpecification(new WorkDraft.Written(stored('a'), "b".repeat(40), 1))
                .withPlan(stored('c'), 1).revised("spec");
        assertNull(draft.specification());
        assertNull(draft.plan());
        assertEquals(1, draft.specificationRevisions());
    }

    @Test
    void aWrittenSpecificationNamesItsCommitAndSetup() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraft(WorkDraft.GENERATED, stored('a'), null, 0, 0, false, false, "TEST-commit", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraft(WorkDraft.GENERATED, stored('a'), null, 0, 0, false, false, "b".repeat(40), 0));
    }
}
