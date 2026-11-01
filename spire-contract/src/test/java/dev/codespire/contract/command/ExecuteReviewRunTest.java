package dev.codespire.contract.command;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot");

    private static RunCommand.ExecuteRun execution() {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "TEST-source", "a".repeat(40), "TEST-source",
                "TEST-instruction", "TEST-harness", "TEST-model", "TEST-image", List.of(), 60,
                "TEST-scm-ciphertext", "TEST-harness-ciphertext");
    }

    @Test
    void anExecuteReviewRunRoundTripsAndRedactsItsCredentials() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunCommand.ExecuteReviewRun command = new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW);

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
                "run::github:TEST-work/app:TEST-other:1", execution(), REVIEW));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, null, REVIEW));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, execution(), null));
        assertDoesNotThrow(() -> new RunCommand.ExecuteReviewRun(RUN_ID, execution(), REVIEW));
    }

    /** A review reads the head it was asked about: a checkpoint start or a push target is a command never to build. */
    @Test
    void aReviewRunStartsFromTheHeadAndPushesNothing() {
        RunCommand.ExecuteRun fromCheckpoint = execution().fromCheckpoint("run::TEST-previous", "b".repeat(40));
        RunCommand.ExecuteRun existing = execution().onExistingBranch("main");

        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, fromCheckpoint, REVIEW));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteReviewRun(RUN_ID, existing, REVIEW));
    }
}
