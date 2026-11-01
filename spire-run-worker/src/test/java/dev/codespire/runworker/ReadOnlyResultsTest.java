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

    /** A key shorter than the redaction marker, so redacting it lengthens the text. */
    private static final String SHORT_KEY = "TEST-k";

    /**
     * An answer exactly at the cap arrives whole, and redacting a key shorter than the marker pushes it past the cap:
     * that is still a paid answer, marked over cap, never a lost run and never a draft's ARTIFACT_MISSING.
     */
    @Test
    void anAnswerPushedOverTheCapByRedactionIsOverCap() {
        ReadOnlyResults shortKey = new ReadOnlyResults(RunLauncherTest.failuresWith(new Credentials() {
            @Override
            public Scm scm(String runId, String packed) {
                return new Scm(RunLauncherTest.SCM_USERNAME, RunLauncherTest.READ_SECRET,
                        RunLauncherTest.SCM_USERNAME, RunLauncherTest.WRITE_SECRET);
            }

            @Override
            public Map<String, String> harnessEnv(String runId, String packed) {
                return Map.of("OPENAI_API_KEY", SHORT_KEY);
            }
        }), ReviewRunDispatcherTest.REVIEW);
        String atTheCap = SHORT_KEY + "x".repeat(RunReviewArtifact.MAX_TEXT_BYTES - SHORT_KEY.length());

        RunReviewArtifact answer = assertInstanceOf(RunReviewArtifact.class,
                shortKey.of(finished(), outcome(parts(atTheCap)), 5));

        assertTrue(answer.overCap(), "the redacted text is four bytes over the cap");
        assertNull(answer.text());
        assertEquals(USAGE, answer.tokenUsage());
    }

    /** ASCII text in 8 KiB parts, as the publisher sends an answer longer than one line. */
    private static String[] parts(String text) {
        int size = 8 * 1024;
        int count = (text.length() + size - 1) / size;
        String[] lines = new String[count];
        for (int index = 0; index < count; index++) {
            String piece = text.substring(index * size, Math.min(text.length(), (index + 1) * size));
            lines[index] = "{\"event\":\"artifact_part\",\"index\":" + index + ",\"count\":" + count
                    + ",\"text\":\"" + piece + "\"}";
        }
        return lines;
    }
}
