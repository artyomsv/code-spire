package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.attention.LabelFailures;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.review.checks.ChecksWaitStore;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared hand-written fakes for saga tests whose path posts a review: each real one opens a database. Every method the
 * ReviewGenerated and CommentsPosted paths reach is overridden on purpose.
 */
final class SagaRepositoryFakes {

    private SagaRepositoryFakes() {
    }

    /** A known repository where the reviewer may approve, blocking at BLOCKER, with verdict labels off. */
    static RepositoryRegistry approvingRepository() {
        return new RepositoryRegistry() {
            @Override
            public Optional<Boolean> reviewerMayApprove(UUID id) {
                return Optional.of(true);
            }

            @Override
            public Optional<BlockApprovalAt> blockApprovalAt(UUID id) {
                return Optional.of(BlockApprovalAt.BLOCKER);
            }

            @Override
            public Optional<Boolean> verdictLabels(UUID id) {
                return Optional.of(false);
            }
        };
    }

    /** No review started on the check timeout (V121): the decision reads this for every generated review. */
    static ChecksWaitStore noUnfinishedChecks() {
        return heldOn("TEST-no-such-commit", new ChecksWaitStore.Hold(1, null));
    }

    /** {@code hold}'s run of {@code commit} started before its checks finished, so it must not approve. */
    static ChecksWaitStore heldOn(String commit, ChecksWaitStore.Hold hold) {
        return new ChecksWaitStore() {
            @Override
            public Optional<Hold> holdOn(String reviewId, String head) {
                return commit.equals(head) ? Optional.of(hold) : Optional.empty();
            }
        };
    }

    /** Every CommentsPosted and ci-failed label report writes its label outcome; this one writes none. */
    static LabelFailures silentLabelFailures() {
        return new LabelFailures() {
            @Override
            public void record(String reviewId, String commit, String reason) {
            }

            @Override
            public void recordForRun(Run run, String reason) {
            }
        };
    }
}
