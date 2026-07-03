package dev.codespire.orchestrator;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 exit criterion: a fake PR event flows end-to-end through the bus —
 * integration -> RequestReview -> FetchDiff -> GatherContext -> GenerateReview
 * -> PostComments -> ReviewCompleted.
 */
@QuarkusTest
class PipelineSmokeTest {

    @Test
    void simulatedPrFlowsToReviewCompleted() throws InterruptedException {
        var response = post("/dev/simulate-pr");
        assertEquals(200, response.statusCode());
        String reviewId = response.jsonPath().getString("reviewId");
        assertTrue(reviewId.startsWith("review::sandbox/demo-repo#"));

        long deadline = System.currentTimeMillis() + 15_000;
        String timeline = "";
        while (System.currentTimeMillis() < deadline) {
            timeline = get("/api/timeline").asString();
            if (timeline.contains("\"ReviewCompleted\"")) {
                break;
            }
            Thread.sleep(250);
        }

        assertTrue(timeline.contains("\"ReviewCompleted\""),
                "expected ReviewCompleted in timeline within 15s, got: " + timeline);
        // the whole choreography left its trace
        assertTrue(timeline.contains("\"DiffFetched\""));
        assertTrue(timeline.contains("\"ContextAssembled\""));
        assertTrue(timeline.contains("\"ReviewGenerated\""));
        assertTrue(timeline.contains("\"CommentsPosted\""));
    }
}
