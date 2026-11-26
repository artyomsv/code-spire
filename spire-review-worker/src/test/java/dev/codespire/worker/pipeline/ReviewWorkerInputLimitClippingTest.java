package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a first review sends under the input limit, what a clipped one names, and the posted note (PR 456). */
class ReviewWorkerInputLimitClippingTest extends ReviewWorkerInputLimitFixture {

    // --- what a review sends and names ---

    @Test
    void aReviewWithAKnownWindowSendsTheWholeDiff() {
        diffText = largeDiff();
        worker.llm = llm(recordingLlm(), LARGE_WINDOW);

        worker.generateReview(firstReview());

        ReviewResult result = lastReviewGenerated().result();
        assertFalse(result.truncated(), "about 38,000 tokens of diff fit a million-token window");
        assertTrue(llmCalls.getLast().user().contains("TEST-b.java"));
    }

    @Test
    void aClippedReviewNamesTheFilesItLeftOut() {
        diffText = largeDiff();

        worker.generateReview(firstReview());

        ReviewResult result = lastReviewGenerated().result();
        assertTrue(result.truncated());
        assertEquals(List.of("src/TEST-b.java"), result.omittedFiles());
    }

    // --- the posted note ---

    @Test
    void thePostedSummaryNamesTheOmittedFiles() {
        var review = new ReviewResult(List.of(finding("src/A.java", 2)), "ok", ModelUsage.of("m", 1, 1))
                .withTruncated(true).withOmittedFiles(List.of("src/TEST-big.lock", "src/TEST-other.java"));

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));

        assertTrue(sink.summaryBody.contains("review input limit"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("`src/TEST-big.lock`"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("`src/TEST-other.java`"), sink.summaryBody);
    }

    @Test
    void thePostedSummaryListsTwentyFilesThenCountsTheRest() {
        List<String> omitted = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            omitted.add("src/TEST-" + i + ".java");
        }
        var review = new ReviewResult(List.of(), "ok", ModelUsage.of("m", 1, 1))
                .withTruncated(true).withOmittedFiles(omitted);

        worker.postComments(new PostComments(REVIEW_ID, REPO, 9, COMMIT, review, null));

        assertTrue(sink.summaryBody.contains("`src/TEST-20.java`"), sink.summaryBody);
        assertFalse(sink.summaryBody.contains("`src/TEST-21.java`"), sink.summaryBody);
        assertTrue(sink.summaryBody.contains("and 3 more files."), sink.summaryBody);
    }
}
