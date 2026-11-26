package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.llm.DiffBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which review timeouts are retried under the input limit (PR 456): a large call's is not, a small one's is. */
class ReviewWorkerInputLimitTimeoutTest extends ReviewWorkerInputLimitFixture {

    // --- timeouts and retries ---

    /** Each attempt would send the whole large prompt again and may be billed: the review fails instead. */
    @Test
    void aLargePromptsTimeoutIsNotRetried() throws Exception {
        diffText = largeDiff();
        worker.llm = llm(failingLlm(langChain4jTimeout()), LARGE_WINDOW);

        worker.generateReview(firstReview());

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertFalse(failed.retryable(), "a large call's timeout must not be re-sent");
        assertTrue(failed.error().contains("Review diff limit"), "the reason names the setting: " + failed.error());
    }

    @Test
    void aSmallPromptsTimeoutIsStillRetried() throws Exception {
        worker.llm = llm(failingLlm(langChain4jTimeout()), DiffBudget.Limits.UNKNOWN);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(), "today's budget, today's retry");
    }

    @Test
    void aSmallDiffOnALargeWindowIsNotALargePrompt() throws Exception {
        worker.llm = llm(failingLlm(langChain4jTimeout()), LARGE_WINDOW);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(),
                "what was sent decides, not what could have been");
    }

    @Test
    void aLargePromptsOtherFailureKeepsItsOwnClassification() throws Exception {
        diffText = largeDiff();
        RuntimeException rateLimit = (RuntimeException) Class
                .forName("dev.langchain4j.exception.RateLimitException")
                .getConstructor(String.class).newInstance("429 from the LLM provider");
        worker.llm = llm(failingLlm(rateLimit), LARGE_WINDOW);

        worker.generateReview(firstReview());

        assertTrue(assertInstanceOf(ReviewFailed.class, emitted.getLast()).retryable(),
                "only a timeout of a large call is made terminal");
    }

    @Test
    void aWindowWithNoRoomForADiffFailsWithoutACall() {
        worker.llm = llm(recordingLlm(), new DiffBudget.Limits(100, null));

        worker.generateReview(firstReview());

        ReviewFailed failed = assertInstanceOf(ReviewFailed.class, emitted.getLast());
        assertTrue(llmCalls.isEmpty(), "no paid call is made");
        assertFalse(failed.retryable(), "the same window refuses again");
        assertTrue(failed.error().contains("input limit of 100 tokens"), failed.error());
    }
}
