package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness review run's answer (harness reviews, step 4): scrubbed like a draft's, and an answer over the review's
 * cap comes back as a paid result without its text, never as a lost run.
 */
class ReadOnlyResultsTest {

    private static final Map<String, Long> USAGE = Map.of("INPUT", 900L, "OUTPUT", 40L);

    private final ReadOnlyResults results = new ReadOnlyResults(RunLauncherTest.failuresWith(new Credentials() {
        @Override
        public Scm scm(String runId, String packed) {
            return new Scm(RunLauncherTest.SCM_USERNAME, RunLauncherTest.READ_SECRET,
                    RunLauncherTest.SCM_USERNAME, RunLauncherTest.WRITE_SECRET);
        }

        @Override
        public Map<String, String> harnessEnv(String runId, String packed) {
            return Map.of("OPENAI_API_KEY", RunLauncherTest.MODEL_KEY);
        }
    }), ReviewRunDispatcherTest.REVIEW);

    private static RunResult finished() {
        return new RunResult.RunFinished(ReviewRunDispatcherTest.REVIEW.runId(), null, List.of(), List.of(), USAGE,
                false, List.of());
    }

    private static PublisherOutcome outcome(String... lines) {
        PublisherOutcome outcome = PublisherOutcome.withArtifactCap(256 * 1024);
        for (String line : lines) {
            outcome.accept(line);
        }
        return outcome;
    }

    /** Review Focus 4: the tokens an over-long answer bought are charged, and the review is marked over its cap. */
    @Test
    void anOverCapReviewArtifactComesBackChargeableNotLost() {
        PublisherOutcome over = outcome("{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"TOO_LARGE\"}");

        RunReviewArtifact answer = assertInstanceOf(RunReviewArtifact.class, results.of(finished(), over, 5));

        assertTrue(answer.overCap());
        assertNull(answer.text());
        assertEquals(USAGE, answer.tokenUsage());
        assertEquals(ReviewRunDispatcherTest.REVIEW.review(), answer.review());
    }

    @Test
    void aReviewAnswerComesBackScrubbed() {
        PublisherOutcome handed = outcome("{\"event\":\"artifact\",\"text\":\"TEST-finding " + RunLauncherTest.MODEL_KEY + "\"}");

        RunReviewArtifact answer = assertInstanceOf(RunReviewArtifact.class, results.of(finished(), handed, 5));

        assertFalse(answer.overCap());
        assertTrue(answer.text().startsWith("TEST-finding"), answer.text());
        assertFalse(answer.text().contains(RunLauncherTest.MODEL_KEY), "the model key the agent quoted is redacted");
    }

    /** No answer at all is not an over-cap answer: it is ARTIFACT_MISSING, still carrying what the agent bought. */
    @Test
    void aReviewRunThatHandedOverNothingIsArtifactMissing() {
        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, results.of(finished(), outcome(), 5));

        assertEquals(RunFailureCause.ARTIFACT_MISSING.name(), failed.cause());
        assertEquals(USAGE, failed.tokenUsage());
    }
}
