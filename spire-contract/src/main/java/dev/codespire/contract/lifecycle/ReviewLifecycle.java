package dev.codespire.contract.lifecycle;

import dev.codespire.contract.core.Decider;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.DomainEvent.FollowUpRecorded;
import dev.codespire.contract.event.DomainEvent.ReviewCancelled;
import dev.codespire.contract.event.DomainEvent.ReviewCompleted;
import dev.codespire.contract.event.DomainEvent.ReviewFailedTerminally;
import dev.codespire.contract.event.DomainEvent.ReviewOutcomeRecorded;
import dev.codespire.contract.event.DomainEvent.ReviewRequested;
import dev.codespire.contract.event.DomainEvent.ReviewSuperseded;
import dev.codespire.contract.event.DomainEvent.ThreadOpened;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.command.RecordCommand.CancelReview;
import dev.codespire.contract.command.RecordCommand.OpenThread;
import dev.codespire.contract.command.RecordCommand.RecordCommentsPosted;
import dev.codespire.contract.command.RecordCommand.RecordFailure;
import dev.codespire.contract.command.RecordCommand.RecordFollowUp;
import dev.codespire.contract.command.RecordCommand.RecordReviewOutcome;
import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.lifecycle.ReviewState.Status;
import dev.codespire.contract.lifecycle.ReviewState.ThreadState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The write model: pure implementation of the CONTRACT §6 decide table.
 *
 * Invariants encoded here:
 * - one active run per PR; latest-commit-wins supersession;
 * - a commit is reviewed at most once unless force=true (FR-12);
 * - stale results AND stale failures (commit != currentCommit) never flip the
 *   current run's state;
 * - a closed PR cancels the in-flight run; a reopened PR starts fresh;
 * - conversational threads never block or change the main status.
 */
public final class ReviewLifecycle implements Decider<RecordCommand, ReviewState, DomainEvent> {

    @Override
    public ReviewState initialState() {
        return ReviewState.initial();
    }

    @Override
    public List<DomainEvent> decide(RecordCommand command, ReviewState state) {
        return switch (command) {
            case RequestReview c -> decideRequestReview(c, state);
            case CancelReview c -> state.isReviewing()
                    ? List.of(new ReviewCancelled(c.reason()))
                    : List.of(); // nothing in flight — no-op
            case RecordReviewOutcome c -> currentRun(state, c.commit())
                    ? List.of(new ReviewOutcomeRecorded(c.commit(), c.findingsCount(), c.summaryDigest()))
                    : List.of();
            case RecordCommentsPosted c -> currentRun(state, c.commit())
                    ? List.of(new ReviewCompleted(c.commit(), c.summaryCommentId()))
                    : List.of();
            // Guarded by commit==currentCommit: a stale failure from a
            // superseded run must never fail the current run (CONTRACT §6).
            case RecordFailure c -> currentRun(state, c.commit()) && !c.retryable()
                    ? List.of(new ReviewFailedTerminally(c.commit(), c.phase()))
                    : List.of();
            case OpenThread c -> List.of(new ThreadOpened(c.threadRef(), c.parentCommentId()));
            case RecordFollowUp c -> List.of(new FollowUpRecorded(c.threadRef(), c.commentId()));
        };
    }

    private List<DomainEvent> decideRequestReview(RequestReview c, ReviewState state) {
        boolean alreadyHandled = state.reviewedCommits().contains(c.commit())
                || Objects.equals(c.commit(), state.currentCommit());

        if (!c.force() && alreadyHandled) {
            return List.of(); // idempotent no-op
        }

        List<DomainEvent> events = new ArrayList<>(2);
        if (state.isReviewing()) {
            // force on the active commit deliberately yields Superseded{c} +
            // Requested{c} — a commit superseding itself = restart the run.
            events.add(new ReviewSuperseded(state.currentCommit()));
        }
        events.add(new ReviewRequested(c.commit(), c.trigger()));
        return events;
    }

    private boolean currentRun(ReviewState state, String commit) {
        return state.isReviewing() && Objects.equals(commit, state.currentCommit());
    }

    @Override
    public ReviewState evolve(ReviewState state, DomainEvent event) {
        return switch (event) {
            case ReviewRequested e -> with(state, Status.REVIEWING, e.commit(),
                    state.reviewedCommits(), state.summaryCommentId(), state.threads());
            case ReviewSuperseded e -> state; // the paired ReviewRequested carries the new commit
            case ReviewOutcomeRecorded e -> state;
            case ReviewCompleted e -> {
                Set<String> reviewed = new HashSet<>(state.reviewedCommits());
                reviewed.add(e.commit());
                yield with(state, Status.COMPLETED, state.currentCommit(),
                        Set.copyOf(reviewed), e.summaryCommentId(), state.threads());
            }
            case ReviewFailedTerminally e -> with(state, Status.FAILED, state.currentCommit(),
                    state.reviewedCommits(), state.summaryCommentId(), state.threads());
            case ReviewCancelled e -> with(state, Status.CANCELLED, state.currentCommit(),
                    state.reviewedCommits(), state.summaryCommentId(), state.threads());
            case ThreadOpened e -> withThread(state, e.threadRef().value(), new ThreadState("OPEN", e.parentCommentId()));
            case FollowUpRecorded e -> withThread(state, e.threadRef().value(), new ThreadState("OPEN", e.commentId()));
        };
    }

    private static ReviewState with(ReviewState s, Status status, String currentCommit,
                                    Set<String> reviewed, String summaryCommentId,
                                    Map<String, ThreadState> threads) {
        return new ReviewState(s.reviewId(), s.repo(), s.prId(), status, currentCommit,
                reviewed, summaryCommentId, threads);
    }

    private static ReviewState withThread(ReviewState s, String key, ThreadState thread) {
        Map<String, ThreadState> threads = new HashMap<>(s.threads());
        threads.put(key, thread);
        return with(s, s.status(), s.currentCommit(), s.reviewedCommits(), s.summaryCommentId(),
                Map.copyOf(threads));
    }
}
