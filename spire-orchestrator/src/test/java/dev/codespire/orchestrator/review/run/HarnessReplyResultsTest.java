package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 7: a reply run's answer is sent to the worker to post, and a run that ends with nothing to post
 * says so under the thread and clears "answering". A plain unit test: every collaborator is a fake overriding every
 * method the path reaches (CLAUDE.md, saga test fakes).
 */
class HarnessReplyResultsTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";
    private static final String THREAD = "review::4242";
    private static final ReviewRunBinding REPLY = new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.FOLLOWUP, 7,
            "0123456789abcdef0123456789abcdef01234567", CallRefs.followUpSlot(THREAD, "987654"), 1);

    private Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");
    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> timelineDetails = new ArrayList<>();
    private final List<Boolean> answering = new ArrayList<>();

    private static RunReviewArtifact answer(String text) {
        return new RunReviewArtifact(RUN_ID, REPLY, text, false, Map.of("INPUT", 1200L, "OUTPUT", 340L), 4, List.of());
    }

    private static RunResult.RunFailed failed(String cause) {
        return new RunResult.RunFailed(RUN_ID, cause, "TEST-detail", false, null, List.of());
    }

    @Test
    void anAnswerIsSentToTheWorkerToPost() {
        results().accept(answer("TEST-REPLY-TEXT"), REPLY);

        assertEquals(List.of(new ActionCommand.PostHarnessReply(REVIEW_ID, REPO, 7, new ThreadRef(THREAD), "987654",
                "TEST-REPLY-TEXT", "TEST-packed-scm", new ReplyRetry(3, 100, 2.0))), emitted);
        assertTrue(events.isEmpty(), events.toString());
        assertTrue(answering.isEmpty(), "the pill clears when the posted reply's FollowUpPosted arrives");
    }

    @Test
    void anOverCapAnswerFailsTheReplyOutLoud() {
        results().accept(new RunReviewArtifact(RUN_ID, REPLY, null, true, Map.of("INPUT", 1L), 4, List.of()), REPLY);

        assertFailed(HarnessReplyResults.OVER_CAP);
    }

    @Test
    void aMissingReviewerCredentialFailsTheReply() {
        reviewerCredential = Optional.empty();

        results().accept(answer("TEST-REPLY-TEXT"), REPLY);

        assertFailed(HarnessReplyResults.CREDENTIAL_UNAVAILABLE);
    }

    @Test
    void aCancelledRunFailsTheReply() {
        results().accept(failed("CANCELLED"), REPLY);

        assertFailed("Harness reply run cancelled");
    }

    @Test
    void aFailedRunFailsTheReplyWithItsCause() {
        results().accept(failed("AGENT_FAILED"), REPLY);

        assertFailed("Harness reply run failed: AGENT_FAILED");
    }

    /** Step 8: an unproxied fork says why, as a refusal under the thread; the pill clears. */
    @Test
    void anUnproxiedForkReplyIsFollowUpRefused() {
        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND", "IllegalStateException: fork_requires_credential_proxy",
                false, null, List.of()), REPLY);

        assertEquals(List.of("FollowUpRefused@" + THREAD + ": harness reply refused: TEST-harness: fork_requires_credential_proxy"), events);
        assertEquals(List.of(false), answering, "the responding pill clears");
        assertTrue(emitted.isEmpty(), emitted.toString());
    }

    @Test
    void aDetailWithAnotherPrefixBeforeTheProxyTextFailsTheReply() {
        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND",
                "X: IllegalStateException: fork_requires_credential_proxy", false, null, List.of()), REPLY);

        assertFailed("Harness reply run failed: BAD_COMMAND");
    }

    @Test
    void theProxyDetailOnAnotherCauseFailsTheReply() {
        results().accept(new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "IllegalStateException: fork_requires_credential_proxy",
                false, null, List.of()), REPLY);

        assertFailed("Harness reply run failed: AGENT_FAILED");
    }

    @Test
    void aDetailThatOnlyQuotesTheProxyTextFailsTheReply() {
        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND", "x fork_requires_credential_proxy y",
                false, null, List.of()), REPLY);

        assertFailed("Harness reply run failed: BAD_COMMAND");
    }

    @Test
    void anotherBadCommandFailsTheReply() {
        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND", "IllegalStateException: something_else",
                false, null, List.of()), REPLY);

        assertFailed("Harness reply run failed: BAD_COMMAND");
    }

    @Test
    void aFailureNeverLogsTheAnswer() {
        reviewerCredential = Optional.empty();

        results().accept(answer("TEST-SECRET-ANSWER"), REPLY);

        assertFalse(events.toString().contains("TEST-SECRET-ANSWER"), events.toString());
        assertFalse(timelineDetails.toString().contains("TEST-SECRET-ANSWER"), timelineDetails.toString());
    }

    private void assertFailed(String why) {
        assertEquals(List.of("FollowUpFailed@" + THREAD + ": " + why), events);
        assertEquals(List.of(false), answering, "the responding pill clears");
        assertTrue(emitted.isEmpty(), emitted.toString());
    }

    private HarnessReplyResults results() {
        HarnessReplyResults results = new HarnessReplyResults();
        results.credentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
        results.levels = new ConversationLevels() {
            @Override public int maxAttempts() { return 3; }
            @Override public long backoffBaseMs() { return 100L; }
            @Override public double backoffFactor() { return 2.0; }
        };
        results.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        results.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineDetails.add(type + ": " + detail);
            }
        };
        results.projection = projection();
        results.runRows = new FactoryRunProjection() {
            @Override public Optional<String> harnessOf(String runId) { return Optional.of("TEST-harness"); }
            @Override protected void push(String runId) { /* nothing: no live feed from a unit test */ }
        };
        return results;
    }

    /** The real one writes review_status and the history; this one records both. */
    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                events.add(type + "@" + threadRef + ": " + detail);
            }

            @Override
            public void setAnswering(String reviewId, boolean value) {
                answering.add(value);
            }
        };
    }
}
