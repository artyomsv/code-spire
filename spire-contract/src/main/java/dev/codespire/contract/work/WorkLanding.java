package dev.codespire.contract.work;

import dev.codespire.contract.scm.PullRequestRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A merge the forge reported for the item's pull request (M4 land, spec §7.3, §7.8). It is the fact that
 * completes the item and refuses readmission (§7.9); the ticket and the branch delete come after it.
 *
 * @param mergeCommit the full commit the forge merged as
 * @param method the method the factory sent with its merge: merge, squash or rebase; {@link #UNKNOWN_METHOD} for a merge
 *        it did not make, because the forge's pull request read does not report which method a person used
 * @param mergedBy {@link #FACTORY} when the land effect merged, else the person's stable provider id, never a login
 */
public record WorkLanding(PullRequestRef pullRequest, String mergeCommit, String method, String mergedBy, Instant landedAt) {
    public static final String FACTORY = "factory";
    public static final String UNKNOWN_METHOD = "unknown";
    public static final Set<String> METHODS = Set.of("merge", "squash", "rebase", UNKNOWN_METHOD);

    public WorkLanding {
        Objects.requireNonNull(pullRequest, "A landing names its pull request");
        Objects.requireNonNull(landedAt, "A landing names when it merged");
        if (mergeCommit == null || !mergeCommit.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("A landing names its full merge commit");
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unknown merge method " + method);
        if (mergedBy == null || mergedBy.isBlank()) throw new IllegalArgumentException("A landing names who merged");
    }
}
