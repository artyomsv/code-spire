package dev.codespire.contract.port;

import dev.codespire.contract.scm.RepoRef;

import java.util.List;
import java.util.Set;

/**
 * Lands a delivered pull request: reads its land state, reads the merge methods the repository
 * allows, merges it at an exact head and deletes its branch after reading it at an exact sha. A
 * forge without an implementation is unavailable, which is what makes land
 * {@code capability_unavailable} there.
 *
 * <p>A client-error answer from {@link #merge} is definite; a server error and an I/O failure are
 * {@link MergeOutcome.Uncertain}, and an uncertain merge is recovered by {@link #read}, never by
 * sending the merge again.
 *
 * <p>{@link #deleteBranch} is not a compare-and-swap where the forge offers none: a push landing
 * between its read and its delete can be deleted with the branch. Callers must not rely on it to
 * stop a late push.
 */
public interface PullRequestLander {
    enum MergeMethod { MERGE, SQUASH, REBASE }

    /** NOT_CODE outranks FAILED outranks PENDING outranks PASSED; an empty set of checks is PASSED. */
    enum Checks { PASSED, PENDING, FAILED, NOT_CODE }

    /** One failed or not-code check. {@code annotations} are read for failed check runs only. */
    record FailedCheck(String name, String conclusion, String title, String summary, List<String> annotations) {
        public FailedCheck {
            annotations = List.copyOf(annotations);
        }
    }

    /** {@code failed} is capped in count and size; {@code done} counts entries that are not pending. */
    record CheckSummary(Checks state, int done, int total, List<FailedCheck> failed) {
        public CheckSummary {
            failed = List.copyOf(failed);
        }
    }

    /**
     * {@code mergedBy} is the stable provider id of the account the forge names as the merger, never
     * the webhook sender. {@code mergeable} is null while the forge computes it, and
     * {@code mergeableState} is passed through verbatim ({@code unknown} included).
     */
    record LandState(String state, boolean merged, String mergeCommitSha, String mergedBy, String headSha,
                     String headBranch, String baseBranch, String baseSha, Boolean mergeable, String mergeableState,
                     CheckSummary checks) {}

    /** {@code checked} is false when the repository did not report its merge settings. */
    record AllowedMethods(boolean checked, Set<MergeMethod> allowed) {
        public AllowedMethods {
            allowed = Set.copyOf(allowed);
        }
    }

    sealed interface MergeOutcome {
        record Merged(String sha) implements MergeOutcome {}
        /** 409: the head or the base moved; the caller re-reads. */
        record HeadConflict(String message) implements MergeOutcome {}
        /** Any other status below 500, a refused redirect included: definite, the forge merged nothing. */
        record Refused(int status, String message) implements MergeOutcome {}
        /** A 5xx or an I/O failure: the merge may or may not have happened. */
        record Uncertain(String cause) implements MergeOutcome {}
    }

    enum BranchDeletion { DELETED, ABSENT, STILL_PRESENT, MOVED }

    default boolean available() { return false; }

    default LandState read(RepoRef repo, long pullRequest) {
        throw new UnsupportedOperationException("land unavailable");
    }

    default AllowedMethods allowedMethods(RepoRef repo) {
        throw new UnsupportedOperationException("land unavailable");
    }

    default MergeOutcome merge(RepoRef repo, long pullRequest, String expectedHead, MergeMethod method) {
        throw new UnsupportedOperationException("land unavailable");
    }

    default BranchDeletion deleteBranch(RepoRef repo, String branch, String expectedSha) {
        throw new UnsupportedOperationException("land unavailable");
    }
}
