package dev.codespire.orchestrator.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Where a fix run is allowed to push, resolved from the review's own row — the ORCHESTRATOR's half
 * of ADR-040.
 *
 * <p>The publisher refuses trunk names and the pull request's destination branch, but it cannot tell
 * whether a branch really is an open pull request's source branch: it holds the only write
 * credential in the run unit and under ADR-039 makes no API call. So the identification lives here,
 * against {@code review_status}, and the publisher's checks are the floor that survives a bug in this
 * class rather than a substitute for it.
 *
 * <p><b>A resolved target is not a pushable one.</b> {@link #forReview} answers what the review row
 * says; {@link PushTarget#isPushable()} answers whether a fix may go there. Keeping them apart is
 * what lets the caller refuse with a reason the author can act on, instead of an empty Optional that
 * cannot distinguish "no such review" from "that pull request is merged".
 */
@ApplicationScoped
public class FixTargets {

    private static final String FIND = """
                SELECT provider_type, workspace, slug, pr_id, source_branch, dest_branch,
                       commit_sha, pr_state
                  FROM review_status
                 WHERE review_id = ?
                """;

    /** The one pull-request state a fix may be pushed to. */
    private static final String OPEN = "OPEN";

    @Inject
    DataSource dataSource;

    /**
     * What the review row says about where a fix would go, or empty when there is no such review.
     *
     * <p>Throws on a read fault rather than answering empty, for the reason
     * {@code FindingProjection.findByThread} does: empty reaches a human as "there is no such
     * review", a claim about their repository that they will act on. Unknown is not absent.
     */
    public Optional<PushTarget> forReview(String reviewId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(FIND)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PushTarget(rs.getString("provider_type"), rs.getString("workspace"),
                        rs.getString("slug"), rs.getLong("pr_id"), rs.getString("source_branch"),
                        rs.getString("dest_branch"), rs.getString("commit_sha"), rs.getString("pr_state")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the fix target for " + reviewId, e);
        }
    }

    /**
     * A pull request, as the review row recorded it.
     *
     * @param sourceBranch the branch a fix pushes to. Defaults to the empty string rather than null
     *     in {@code review_status}, which is why {@link #isPushable()} tests for blank: a null check
     *     alone passes a blank ref to the publisher, where it fails {@code isValidRefName} inside a
     *     container, after the agent has been paid
     */
    public record PushTarget(String providerType, String workspace, String slug, long prId,
                             String sourceBranch, String destBranch, String commit, String prState) {

        /**
         * Whether a fix run may push to this branch.
         *
         * <p><b>Fork pull requests are NOT excluded here, and cannot be.</b> ADR-040 puts them out of
         * scope for {@code existing} mode, but nothing in this deployment records whether a pull
         * request came from a fork — a fork's source branch name would resolve here and be pushed
         * against the BASE repository, creating a stray branch or landing on an unrelated branch of
         * the same name. That gap is tracked as blocking rather than hidden behind a field that is
         * always false, which would read as a check and be none. See
         * {@code techdebt/spire-orchestrator/2-3-a-fork-pull-request-is-indistinguishable-from-a-branch-one.md}.
         */
        public boolean isPushable() {
            return OPEN.equals(prState) && sourceBranch != null && !sourceBranch.isBlank();
        }
    }
}
