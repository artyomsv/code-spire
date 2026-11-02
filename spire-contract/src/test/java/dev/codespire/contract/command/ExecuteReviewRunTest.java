package dev.codespire.contract.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A review run reads the pull request's head, pushes nothing, and its credentials never reach a log line. */
class ExecuteReviewRunTest {

    private static final String RUN_ID = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";

    private static final ReviewRunBinding REVIEW =
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1);

    private static RunCommand.ExecuteRun execution() {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "TEST-source", "a".repeat(40), "TEST-source",
                "TEST-instruction", "TEST-harness", "TEST-model", "TEST-image", List.of(), 60,
                "TEST-scm-ciphertext", "TEST-harness-ciphertext");
    }

    @Test
    void anExecuteReviewRunRoundTripsAndRedactsItsCredentials() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunCommand.ExecuteReviewRun command = new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW, HarnessReviewInputs.NONE);

        RunCommand decoded = mapper.readValue(mapper.writeValueAsString(command), RunCommand.class);

        assertEquals(command, decoded);
        assertEquals("TEST-scm-ciphertext", decoded.scmCredential(), "the worker unpacks the clone credential");
        assertEquals("TEST-harness-ciphertext", decoded.harnessCredential(), "and the harness credential");
        assertFalse(command.toString().contains("TEST-scm-ciphertext"), command.toString());
        assertFalse(command.toString().contains("TEST-harness-ciphertext"), command.toString());
    }

    @Test
    void aReviewRunNamesItsExecutionAndItsReview() {
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteReviewRun(
                "run::github:TEST-work/app:TEST-other:1", execution(), REVIEW, HarnessReviewInputs.NONE));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, null, REVIEW, HarnessReviewInputs.NONE));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, execution(), null, HarnessReviewInputs.NONE));
        assertDoesNotThrow(() -> new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW, HarnessReviewInputs.NONE));
    }

    @Test
    void anExecuteReviewRunRoundTripsItsInputs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessReviewInputs inputs = new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT")));
        RunCommand.ExecuteReviewRun command = new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW, inputs);

        RunCommand.ExecuteReviewRun decoded = (RunCommand.ExecuteReviewRun) mapper.readValue(
                mapper.writeValueAsString(command), RunCommand.class);

        assertEquals(inputs, decoded.inputs());
        assertFalse(command.toString().contains("TEST-CONTEXT"), "the inputs print sizes, never text: " + command);
    }

    /** A step 5 command still in flight carries no inputs, and runs with none. */
    @Test
    void aCommandFromBeforeInputsReadsAsNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode json = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.valueToTree(new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW, HarnessReviewInputs.NONE));
        json.remove("inputs");

        RunCommand.ExecuteReviewRun decoded = (RunCommand.ExecuteReviewRun) mapper.treeToValue(json, RunCommand.class);

        assertEquals(HarnessReviewInputs.NONE, decoded.inputs());
    }

    /** A review reads the head it was asked about: a checkpoint start or a push target is a command never to build. */
    @Test
    void aReviewRunStartsFromTheHeadAndPushesNothing() {
        RunCommand.ExecuteRun fromCheckpoint = execution().fromCheckpoint("run::TEST-previous", "b".repeat(40));
        RunCommand.ExecuteRun existing = execution().onExistingBranch("main");

        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, fromCheckpoint, REVIEW, HarnessReviewInputs.NONE));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, existing, REVIEW, HarnessReviewInputs.NONE));
    }
}
