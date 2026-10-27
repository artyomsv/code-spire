package dev.codespire.contract.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkRunBinding;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A plan step's build (M4 slice 3, decisions 11 and 14): a required start and an allowed empty finish travel with it. */
class ExecuteWorkRunTest {
    final WorkRunBinding binding = new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "a".repeat(64));

    RunCommand.ExecuteRun run() {
        return new RunCommand.ExecuteRun("TEST-run", new RepoRef("TEST-ws", "TEST-repo"), "https://forge.example.test/TEST.git",
                "main", "a".repeat(40), "spire/work-TEST", "TEST prompt", "codex", "TEST-model", "TEST-image", List.of(), 60, "TEST-scm", "TEST-harness");
    }

    @Test
    void requiringACheckpointNeedsAStart() {
        RunCommand.ExecuteWorkRun fromBase = new RunCommand.ExecuteWorkRun(run(), binding);

        assertThrows(IllegalArgumentException.class, fromBase::requiringCheckpoint);
    }

    @Test
    void aRequiredStartIsCarried() {
        RunCommand.ExecuteWorkRun step = new RunCommand.ExecuteWorkRun(run().fromCheckpoint("TEST-previous", "b".repeat(40)), binding)
                .requiringCheckpoint();

        assertTrue(step.checkpointRequired());
        assertFalse(new RunCommand.ExecuteWorkRun(run(), binding).checkpointRequired(), "a build starts from its base unless told otherwise");
    }

    @Test
    void allowingEmptyIsKeptByEveryWither() {
        RunCommand.ExecuteWorkRun step = new RunCommand.ExecuteWorkRun(run().fromCheckpoint("TEST-previous", "b".repeat(40)), binding)
                .allowingEmpty().requiringCheckpoint();

        assertTrue(step.emptyAllowed(), "requiringCheckpoint");
        assertTrue(step.checkpointRequired());
        assertTrue(step.withExecution(step.execution().atEffort("high")).emptyAllowed(), "withExecution");
        assertTrue(step.withExecution(step.execution().atEffort("high")).checkpointRequired(), "withExecution keeps the required start");
        assertFalse(new RunCommand.ExecuteWorkRun(run(), binding).emptyAllowed(), "only a plan step may finish empty");
    }

    @Test
    void bothFlagsSurviveTheWire() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunCommand step = new RunCommand.ExecuteWorkRun(run().fromCheckpoint("TEST-previous", "b".repeat(40)), binding)
                .allowingEmpty().requiringCheckpoint();

        RunCommand.ExecuteWorkRun decoded = (RunCommand.ExecuteWorkRun) mapper.readValue(mapper.writeValueAsString(step), RunCommand.class);

        assertTrue(decoded.checkpointRequired());
        assertTrue(decoded.emptyAllowed());
    }
}
