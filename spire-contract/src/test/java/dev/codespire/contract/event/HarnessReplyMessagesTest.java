package dev.codespire.contract.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Harness reviews, step 7: the prepare and post messages of a reply on a harness. */
class HarnessReplyMessagesTest {

    private static final String REVIEW_ID = "review::TEST-work/app#7";
    private static final RepoRef REPO = new RepoRef("TEST-work", "app");
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    private static final ReplyRetry RETRY = new ReplyRetry(3, 500, 2.0);

    private final ObjectMapper mapper = new ObjectMapper();

    private static ActionCommand.PrepareHarnessReply prepare(String triggeringCommentId) {
        return new ActionCommand.PrepareHarnessReply(REVIEW_ID, REPO, 7, THREAD, triggeringCommentId,
                "TEST-SECRET-CRED", true, 2,
                List.of(new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-OTHER-FINDING", "TEST-thread-2")),
                RETRY, Author.of("TEST-id-9", "TEST-alice", "TEST Alice"));
    }

    private static ActionCommand.PostHarnessReply post(String answer) {
        return new ActionCommand.PostHarnessReply(REVIEW_ID, REPO, 7, THREAD, "TEST-comment-5", answer,
                "TEST-SECRET-CRED", RETRY);
    }

    private static IntegrationEvent.HarnessReplyPrepared prepared() {
        return new IntegrationEvent.HarnessReplyPrepared(REVIEW_ID, 7, "a".repeat(40), THREAD, "TEST-comment-5", 2,
                new HarnessReviewInputs(List.of(
                        new HarnessReviewInputs.InputFile(HarnessReviewInputs.THREAD, "TEST-THREAD-TEXT"))));
    }

    @Test
    void aPrepareRoundTrips() throws Exception {
        ActionCommand.PrepareHarnessReply command = prepare("TEST-comment-5");

        ActionCommand decoded = mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class);

        assertEquals(command, decoded);
        assertEquals("TEST-SECRET-CRED", decoded.scmCredential(), "the worker unpacks the forge credential");
    }

    @Test
    void aPostRoundTrips() throws Exception {
        ActionCommand.PostHarnessReply command = post("TEST-ANSWER-TEXT");

        ActionCommand decoded = mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class);

        assertEquals(command, decoded);
        assertEquals("TEST-SECRET-CRED", decoded.scmCredential());
    }

    @Test
    void aPrepareNeedsItsTriggeringComment() {
        // Half of the claim key and of the charge identity: without it, turn 2 is turn 1's redelivery.
        assertThrows(IllegalArgumentException.class, () -> prepare(null));
        assertThrows(IllegalArgumentException.class, () -> prepare(" "));
    }

    @Test
    void aPostNeedsAnAnswer() {
        assertThrows(IllegalArgumentException.class, () -> post(null));
        assertThrows(IllegalArgumentException.class, () -> post("  "));
    }

    @Test
    void neitherCommandPrintsItsCredentialOrText() {
        String printedPrepare = prepare("TEST-comment-5").toString();
        String printedPost = post("TEST-ANSWER-TEXT").toString();

        assertFalse(printedPrepare.contains("TEST-SECRET-CRED"), printedPrepare);
        assertFalse(printedPrepare.contains("TEST-OTHER-FINDING"), printedPrepare);
        assertTrue(printedPrepare.contains("otherFindings=<1 findings>"), printedPrepare);
        assertFalse(printedPost.contains("TEST-SECRET-CRED"), printedPost);
        assertFalse(printedPost.contains("TEST-ANSWER-TEXT"), printedPost);
        assertTrue(printedPost.contains("answerText=<16 chars>"), printedPost);
    }

    @Test
    void aReplyRetryRoundTrips() throws Exception {
        assertEquals(RETRY, mapper.readValue(mapper.writeValueAsString(RETRY), ReplyRetry.class));
    }

    @Test
    void aReplyRetryNeedsOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> new ReplyRetry(0, 500, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new ReplyRetry(1, -1, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new ReplyRetry(1, 500, 0.5));
    }

    @Test
    void aPreparedReplyRoundTripsItsInputs() throws Exception {
        IntegrationEvent.HarnessReplyPrepared event = prepared();

        IntegrationEvent decoded = mapper.readValue(mapper.writeValueAsString(event), IntegrationEvent.class);

        assertEquals(event, decoded);
        assertEquals("TEST-THREAD-TEXT", ((IntegrationEvent.HarnessReplyPrepared) decoded).inputs().asMap()
                .get(HarnessReviewInputs.THREAD));
    }

    @Test
    void aPreparedReplyIsKeyedByItsReview() {
        assertEquals(REVIEW_ID, EventKeys.of(prepared()));
        assertEquals(REVIEW_ID, prepare("TEST-comment-5").reviewId());
        assertEquals(REVIEW_ID, post("TEST-ANSWER-TEXT").reviewId());
    }
}
