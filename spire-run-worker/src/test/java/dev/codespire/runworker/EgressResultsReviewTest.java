package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A review run's answer reports what its agent asked the egress proxy for, like every other result. */
class EgressResultsReviewTest {

    @Test
    void aReviewAnswerCarriesItsEgress() {
        RunReviewArtifact answer = new RunReviewArtifact("run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1",
                new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1),
                "TEST-findings", false, Map.of("INPUT", 1L), 1, List.of());
        List<RunResult.EgressAttempt> egress = List.of(new RunResult.EgressAttempt("api.openai.com", 443, true));

        RunResult attached = EgressResults.attach(answer, egress);

        assertEquals(answer.withEgress(egress), attached);
    }
}
