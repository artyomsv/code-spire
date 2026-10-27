package dev.codespire.contract.command;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkDraftBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A specification or plan run never starts from a checkpoint, never pushes, and clones onto its own local branch. */
class ExecuteWorkDraftRunTest {

    private static final String RUN_ID = "run::github:TEST-work/app:TEST-draft:1";

    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final WorkDraftBinding DRAFT =
            new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.SPEC, "d".repeat(64));

    private static final String BRANCH = "spire/draft-" + ATTEMPT;

    private static RunCommand.ExecuteRun execution(String branch) {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "main", "b".repeat(40), branch,
                "TEST-instruction", "TEST-harness", "TEST-model", "TEST-image", List.of(), 60, "TEST-scm", "TEST-key");
    }

    @Test
    void aDraftRunCannotStartFromACheckpoint() {
        RunCommand.ExecuteRun fromCheckpoint = execution(BRANCH).fromCheckpoint("run::TEST-previous", "a".repeat(40));
        assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, fromCheckpoint, DRAFT));
    }

    @Test
    void aDraftRunCannotPushToAnExistingBranch() {
        RunCommand.ExecuteRun existing = execution(BRANCH).onExistingBranch("main");
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, existing, DRAFT));
    }

    @Test
    void aDraftRunUsesItsOwnLocalBranchName() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, execution("spire/work-TEST-item"), DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, execution("spire/draft-" + UUID.randomUUID()), DRAFT),
                "another attempt's branch is not this attempt's");
        assertDoesNotThrow(() -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, execution(BRANCH), DRAFT));
    }

    @Test
    void aDraftRunNamesItsExecutionAndItsDraft() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.ExecuteWorkDraftRun("run::github:TEST-work/app:TEST-other:1", execution(BRANCH), DRAFT));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteWorkDraftRun(RUN_ID, execution(BRANCH), null));
    }

    @Test
    void aDraftBindingNeedsItsWholeIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.PLAN, "D".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.PLAN, "d".repeat(63)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraftBinding(" ", 1, ATTEMPT, WorkDraftBinding.Kind.PLAN, "d".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkDraftBinding("TEST-item", 0, ATTEMPT, WorkDraftBinding.Kind.PLAN, "d".repeat(64)));
        assertThrows(NullPointerException.class,
                () -> new WorkDraftBinding("TEST-item", 1, null, WorkDraftBinding.Kind.PLAN, "d".repeat(64)));
        assertThrows(NullPointerException.class,
                () -> new WorkDraftBinding("TEST-item", 1, ATTEMPT, null, "d".repeat(64)));
    }
}
