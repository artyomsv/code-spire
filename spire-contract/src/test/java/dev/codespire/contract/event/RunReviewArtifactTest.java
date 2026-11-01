package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.review.ReviewRunBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A review run's answer: bounded, and an answer over the bound comes back without its text, never lost. */
class RunReviewArtifactTest {

    private static final String RUN_ID = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";

    private static final ReviewRunBinding REVIEW =
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.RECONCILE, 7, "a".repeat(40), "TEST-slot", 1);

    private static RunReviewArtifact artifact(String text, boolean overCap) {
        return new RunReviewArtifact(RUN_ID, REVIEW, text, overCap, Map.of("INPUT", 11L), 3, List.of());
    }

    @Test
    void aRunReviewArtifactRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunResult artifact = new RunReviewArtifact(RUN_ID, REVIEW, "TEST-findings", false, Map.of("INPUT", 11L), 3,
                List.of(new RunResult.EgressAttempt("api.openai.com", 443, true)));

        assertEquals(artifact, mapper.readValue(mapper.writeValueAsString(artifact), RunResult.class));
    }

    /** The binding is a nested wire type the schema snapshot does not recurse into, so its shape is proven here. */
    @Test
    void aReviewRunBindingSurvivesTheWireInsideItsResult() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunResult artifact = artifact(null, true);

        RunReviewArtifact decoded = (RunReviewArtifact) mapper.readValue(mapper.writeValueAsString(artifact), RunResult.class);

        assertEquals(REVIEW, decoded.review());
    }

    @Test
    void anOverCapArtifactCarriesNoText() {
        assertDoesNotThrow(() -> artifact(null, true));
        assertThrows(IllegalArgumentException.class, () -> artifact("TEST-findings", true),
                "an over-cap answer carries no text");
        assertThrows(IllegalArgumentException.class, () -> artifact(null, false),
                "an answer with no text is over the cap");
        assertThrows(IllegalArgumentException.class, () -> artifact(" \n", false), "a blank answer is not an answer");
    }

    @Test
    void aReviewArtifactUpToTheCapIsAccepted() {
        assertDoesNotThrow(() -> artifact("x".repeat(256 * 1024), false), "the cap itself is allowed");
        assertThrows(IllegalArgumentException.class, () -> artifact("x".repeat(256 * 1024 + 1), false));
        // The cap is in UTF-8 bytes, as the publisher measures it.
        assertThrows(IllegalArgumentException.class, () -> artifact("é".repeat(128 * 1024 + 1), false));
    }

    @Test
    void anArtifactNamesItsRunAndReviewAndAMeasuredUsage() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunReviewArtifact(" ", REVIEW, "TEST-findings", false, null, 3, List.of()));
        assertThrows(NullPointerException.class,
                () -> new RunReviewArtifact(RUN_ID, null, "TEST-findings", false, null, 3, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunReviewArtifact(RUN_ID, REVIEW, "TEST-findings", false, Map.of(), 3, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunReviewArtifact(RUN_ID, REVIEW, "TEST-findings", false, null, -1, List.of()));
    }
}
