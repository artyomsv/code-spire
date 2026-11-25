package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.REPO;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.labelled;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.labelledPostedAsComment;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.unlabelled;
import static dev.codespire.worker.pipeline.VerdictLabelWorkerFixture.unlabelledCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict label after a posted review (verdict labels, V120): only when the repository asked for it, never for a
 * review that was not posted, never at the review's expense, and always the desired label of the review's current run
 * (recorded by the orchestrator), to which a replay, a stale command or a redelivery after a crash converges.
 */
class ReviewWorkerVerdictLabelTest {

    private static final String APPROVED = VerdictLabel.APPROVED.name();
    private static final String CHANGES_REQUESTED = VerdictLabel.CHANGES_REQUESTED.name();

    private final VerdictLabelWorkerFixture fx = new VerdictLabelWorkerFixture();
    private final ReviewWorker worker = fx.worker();

    @BeforeEach
    void runOneWantsApproved() {
        fx.idempotency.desire(1, APPROVED);
    }

    private CommentsPosted posted() {
        return assertInstanceOf(CommentsPosted.class, fx.emitted.getLast());
    }

    @Test
    void theRunsDesiredLabelIsSetAfterTheReviewIsPosted() {
        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertEquals(List.of(APPROVED), fx.sink.labelCalls);
        assertEquals(List.of("review", "label"), fx.sink.calls, "the label follows the posted review");
        assertNull(posted().labelFailure());
    }

    /** "Reviewer may approve" off posts a Comment; the desired label came from the decided verdict. */
    @Test
    void anApprovalPostedAsACommentStillLabelsApproved() {
        worker.postComments(labelledPostedAsComment(ReviewVerdict.APPROVE));

        assertEquals(ReviewVerdict.COMMENT, fx.sink.reviewPosts.getFirst().verdict());
        assertEquals(List.of(APPROVED), fx.sink.labelCalls);
    }

    @Test
    void theSettingOffSetsNoLabel() {
        worker.postComments(unlabelled(ReviewVerdict.APPROVE));
        worker.postComments(unlabelledCommand().withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.APPROVE, true));

        assertTrue(fx.sink.labelCalls.isEmpty());
        assertTrue(fx.sink.calls.stream().noneMatch("unlabel"::equals));
    }

    /** A run that wants no verdict label (degraded, or held for CI) removes the bot's own labels and no other. */
    @Test
    void noDesiredLabelRemovesOnlyTheBotsLabels() {
        fx.idempotency.desire(2, null);
        fx.sink.labels.addAll(List.of(APPROVED, "review: requested", "TEST-unrelated"));

        worker.postComments(labelled(ReviewVerdict.COMMENT));

        assertEquals(Set.of("review: requested", "TEST-unrelated"), fx.sink.labels);
        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    @Test
    void aReviewThatFailedToPostSetsNoLabel() {
        fx.sink.reviewFailures.add(new GitHubApiException(500, "POST", "/TEST/reviews"));

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertInstanceOf(ReviewFailed.class, fx.emitted.getLast());
        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    /** A superseded run: the head moved, nothing is posted and nothing is labelled. */
    @Test
    void aSupersededRunSetsNoLabel() {
        fx.head = "TEST-newer-commit";

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertTrue(fx.sink.reviewPosts.isEmpty());
        assertTrue(fx.sink.labelCalls.isEmpty());
    }

    @Test
    void aLabelFailureLeavesTheReviewPostedAndReportsTheReason() {
        fx.sink.labelFailure = new GitHubApiException(403, "POST", "/TEST/labels", "TEST-forbidden");

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        CommentsPosted posted = posted();
        assertEquals("rv-100", posted.reviewRef(), "the review is reported as posted");
        assertTrue(posted.labelFailure().contains("HTTP 403"), posted.labelFailure());
        assertEquals(1, fx.sink.reviewPosts.size(), "never re-posted");
        assertTrue(fx.emitted.stream().noneMatch(ReviewFailed.class::isInstance), "the review does not fail");
    }

    /** The bot owns its label: a replay puts the current run's label back over one a person set since. */
    @Test
    void aReplayReappliesTheCurrentRunsLabel() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        worker.postComments(command);
        fx.sink.labels.clear();
        fx.sink.labels.add("review: requested");

        worker.postComments(command);

        assertEquals(Set.of(APPROVED), fx.sink.labels);
        assertEquals(1, fx.sink.reviewPosts.size());
    }

    /** A stale command of run 1, after run 2 decided changes-requested: it applies run 2's label, never its own. */
    @Test
    void aStaleCommandAppliesTheCurrentRunsLabel() {
        fx.idempotency.desire(2, CHANGES_REQUESTED);

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertEquals(Set.of(CHANGES_REQUESTED), fx.sink.labels);
    }

    /** A newer run decides while this label call runs: the desired label is read again and the newer one wins. */
    @Test
    void aNewerRunDecidingDuringTheCallWins() {
        fx.sink.duringLabel = () -> fx.idempotency.desire(2, CHANGES_REQUESTED);

        worker.postComments(labelled(ReviewVerdict.APPROVE));

        assertEquals(Set.of(CHANGES_REQUESTED), fx.sink.labels);
        assertEquals(List.of(APPROVED, CHANGES_REQUESTED), fx.sink.labelCalls);
    }

    /** The worker dies after its GitHub call (here: the re-read fails); the redelivery converges. */
    @Test
    void aRedeliveryAfterACrashConverges() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        fx.sink.duringLabel = () -> {
            fx.idempotency.desire(2, CHANGES_REQUESTED);
            fx.idempotency.failingReads = 1;
        };
        worker.postComments(command);
        assertTrue(posted().labelFailure().contains("review_label_desired"), posted().labelFailure());

        worker.postComments(command);

        assertEquals(Set.of(CHANGES_REQUESTED), fx.sink.labels);
        assertNull(posted().labelFailure());
    }

    /** No label is ever recovered as a posted inline comment. */
    @Test
    void theLabelClaimNeverBecomesAnInlineComment() {
        PostComments command = labelled(ReviewVerdict.APPROVE);
        worker.postComments(command);

        worker.postComments(command);

        assertTrue(posted().inline().isEmpty(), posted().inline().toString());
        assertFalse(InlineRecovery.isInline(VerdictLabels.CLAIM_KEY));
    }

    /** GitLab and Bitbucket keep the port's default. */
    @Test
    void aForgeWithoutLabelsAnswersUnsupported() {
        CommentSink plain = new VerdictLabelWorkerFixture.PlainSink();

        assertEquals(CommentSink.LabelOutcome.UNSUPPORTED, plain.setVerdictLabel(REPO, 9, VerdictLabel.APPROVED));
        assertEquals(CommentSink.LabelOutcome.UNSUPPORTED, plain.removeVerdictLabel(REPO, 9, VerdictLabel.APPROVED));
    }
}
