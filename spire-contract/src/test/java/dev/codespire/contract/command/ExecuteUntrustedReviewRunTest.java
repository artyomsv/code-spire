package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fork's review run travels under its own discriminator, so a worker that does not know the type refuses it. */
class ExecuteUntrustedReviewRunTest {

    private static final String RUN_ID = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";
    private static final String REF = "refs/pull/7/head";

    private static final ReviewRunBinding REVIEW =
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1);

    private static RunCommand.ExecuteRun execution(String branch) {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "TEST-source", "a".repeat(40), branch,
                "TEST-instruction", "TEST-harness", "TEST-model", "TEST-image", List.of(), 60,
                "TEST-scm-ciphertext", "TEST-harness-ciphertext");
    }

    private static RunCommand.ExecuteUntrustedReviewRun command() {
        return new RunCommand.ExecuteUntrustedReviewRun(RUN_ID, execution("spire/review-7"), REVIEW,
                HarnessReviewInputs.NONE, REF);
    }

    /** The subtype list of a worker built before this type existed. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RunCommand.ExecuteRun.class, name = "ExecuteRun"),
            @JsonSubTypes.Type(value = RunCommand.ExecuteReviewRun.class, name = "ExecuteReviewRun")
    })
    private interface OlderWorkerRunCommand {
    }

    @Test
    void theCommandRoundTripsUnderItsOwnDiscriminator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunCommand.ExecuteUntrustedReviewRun command = command();

        String json = mapper.writeValueAsString(command);
        RunCommand decoded = mapper.readValue(json, RunCommand.class);

        assertEquals("ExecuteUntrustedReviewRun", mapper.readTree(json).get("type").asText());
        assertEquals(command, decoded);
        assertEquals("TEST-scm-ciphertext", decoded.scmCredential());
        assertEquals("TEST-harness-ciphertext", decoded.harnessCredential());
        assertFalse(command.toString().contains("TEST-scm-ciphertext"), command.toString());
        assertFalse(command.toString().contains("TEST-harness-ciphertext"), command.toString());
    }

    @Test
    void aWorkerWithoutTheTypeRefusesIt() throws Exception {
        ObjectMapper newer = new ObjectMapper();
        ObjectMapper older = new ObjectMapper();
        older.addMixIn(RunCommand.class, OlderWorkerRunCommand.class);
        String untrusted = newer.writeValueAsString(command());
        String trusted = newer.writeValueAsString(
                new RunCommand.ExecuteReviewRun(RUN_ID, execution("spire/review-7"), REVIEW, HarnessReviewInputs.NONE));

        assertThrows(JsonMappingException.class, () -> older.readValue(untrusted, RunCommand.class));
        // The control: the same older mapper still reads the trusted command, so the refusal is the type's.
        assertEquals(RunCommand.ExecuteReviewRun.class, older.readValue(trusted, RunCommand.class).getClass());
    }

    @Test
    void theCommandRefusesAnInvalidRef() {
        for (String ref : new String[] {null, "", "refs/heads/main", "refs/pull/0/head", "refs/pull/7/head\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                    RUN_ID, execution("spire/review-7"), REVIEW, HarnessReviewInputs.NONE, ref), String.valueOf(ref));
        }
    }

    @Test
    void theCommandRefusesTheForkBranchName() {
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("feature/fork-branch"), REVIEW, HarnessReviewInputs.NONE, REF));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-8"), REVIEW, HarnessReviewInputs.NONE, REF));
        assertDoesNotThrow(() -> command());
    }

    @Test
    void theCommandRefusesAPullRefOfAnotherPullRequest() {
        // Pull request 7 is bound, so a ref of pull request 8 (or a merge request 8) is refused, naming both numbers.
        IllegalArgumentException github = assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.ExecuteUntrustedReviewRun(RUN_ID, execution("spire/review-7"), REVIEW,
                        HarnessReviewInputs.NONE, "refs/pull/8/head"));
        assertTrue(github.getMessage().contains("7") && github.getMessage().contains("refs/pull/8/head"),
                github.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-7"), REVIEW, HarnessReviewInputs.NONE, "refs/merge-requests/8/head"));
        assertDoesNotThrow(() -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-7"), REVIEW, HarnessReviewInputs.NONE, "refs/merge-requests/7/head"));
    }

    @Test
    void theCommandKeepsTheReviewRunChecks() {
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                "run::github:TEST-work/app:TEST-other:1", execution("spire/review-7"), REVIEW, HarnessReviewInputs.NONE, REF));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, null, REVIEW, HarnessReviewInputs.NONE, REF));
        assertThrows(NullPointerException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-7"), null, HarnessReviewInputs.NONE, REF));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-7").onExistingBranch("main"), REVIEW, HarnessReviewInputs.NONE, REF));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteUntrustedReviewRun(
                RUN_ID, execution("spire/review-7").fromCheckpoint("run::TEST-previous", "b".repeat(40)), REVIEW,
                HarnessReviewInputs.NONE, REF));
    }

    @Test
    void asReviewIsTheSharedUnitBuildInput() {
        RunCommand.ExecuteUntrustedReviewRun command = command();

        assertEquals(new RunCommand.ExecuteReviewRun(RUN_ID, command.execution(), REVIEW, HarnessReviewInputs.NONE),
                command.asReview());
    }
}
