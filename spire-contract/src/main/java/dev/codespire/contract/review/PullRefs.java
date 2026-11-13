package dev.codespire.contract.review;

import dev.codespire.contract.port.ScmType;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The forge ref a fork pull request's head is read from (harness reviews, step 8). A fork's head lives in another
 * repository, so the run unit fetches it from the base repository's own pull ref instead: GitHub's
 * {@code refs/pull/<n>/head}, GitLab's {@code refs/merge-requests/<iid>/head}. Only those two shapes are valid, so a
 * ref that reaches a command or a git argument can name nothing else.
 */
public final class PullRefs {

    /** The two valid shapes. The number has no leading zero and is at least 1. */
    public static final String PATTERN = "refs/(pull|merge-requests)/[1-9][0-9]{0,17}/head";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    /** The local branch a pulled head is checked out on; it never reaches a remote. */
    private static final String LOCAL_BRANCH_PREFIX = "spire/review-";

    private PullRefs() {
    }

    /** The pull ref of a pull request or merge request; empty where the forge has none (Bitbucket). */
    public static Optional<String> forType(ScmType type, long prId) {
        if (prId < 1) throw new IllegalArgumentException("A pull ref names a pull request, not " + prId);
        return switch (type) {
            case GITHUB -> Optional.of("refs/pull/" + prId + "/head");
            case GITLAB -> Optional.of("refs/merge-requests/" + prId + "/head");
            case BITBUCKET_CLOUD, BITBUCKET_DC -> Optional.empty();
        };
    }

    /** True for exactly the two shapes; a trailing newline or suffix is not tolerated. */
    public static boolean isValid(String ref) {
        return ref != null && COMPILED.matcher(ref).matches();
    }

    /** The local branch name for a pull request's head. */
    public static String localBranch(long prId) {
        return LOCAL_BRANCH_PREFIX + prId;
    }
}
