package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepoRef;
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
 * what lets the caller distinguish "no such review" from "that pull request is merged", which an
 * empty Optional cannot.
 *
 * <p><b>An archived review is NOT filtered here</b>, unlike every read in {@code AttentionQueries}.
 * It is gated upstream — {@code IntegrationSaga.handle} stops an archived review before the command
 * switch, so no {@code /fix} reaches this class for one. Recorded rather than added, in the style
 * {@code SpendWindow} uses for its own deliberate omissions: a second filter here would read as the
 * guard and hide where the real one lives.
 *
 * <p><b>The row is the KEY, not the proof.</b> {@code pr_state} is set to OPEN by every pull-request
 * event, so a redelivery after a merge flips a closed pull request back to pushable here. Closing
 * that needs a dispatch-time re-read from the forge — which the orchestrator may do and the
 * publisher may not — and it is the same re-read that would close the fork gap. Until then this
 * class answers what the deployment last saw, which is not the same as what is true now.
 */
@ApplicationScoped
public class FixTargets {

    private static final String FIND = """
                SELECT provider_type, workspace, slug, pr_id, source_branch, dest_branch,
                       commit_sha, pr_state, from_fork
                  FROM review_status
                 WHERE review_id = ?
                """;

    /** The one pull-request state a fix may be pushed to. */
    private static final String OPEN = "OPEN";

    /**
     * Why a fix may not be pushed to a pull request.
     *
     * <p>An enum rather than a boolean plus prose elsewhere, so a caller that renders reasons must
     * handle every cause — exhaustively, at compile time. Adding a cause here without wording it
     * breaks the build, which is the guarantee a test over a fixed matrix cannot give.
     */
    public enum Unpushable {
        /** The source branch lives in the contributor's repository, not this one. */
        FORK,
        /** Merged or closed: no later round would reconcile the fix. */
        NOT_OPEN,
        /** No branch, head commit or destination recorded yet — all default to blank, not null. */
        NOT_RECORDED_YET,
        /**
         * Written before V55, so whether it is a fork was never recorded.
         *
         * <p>Distinct from {@link #FORK} on purpose: this row may well be a perfectly ordinary
         * branch pull request, and the author should be told to push once rather than told their
         * pull request is a fork. Distinct from {@link #NOT_RECORDED_YET} because that one is
         * about refs and this one is about provenance, and merging them would hide which.
         */
        PROVENANCE_UNKNOWN,
        /**
         * The row says the pull request is open from a branch onto itself.
         *
         * <p>No forge produces this, and that is exactly why it is guarded: the row is what the
         * deployment last SAW, and the failure mode of trusting it is not a wrong answer but an
         * exception. {@code ExecuteRun} refuses a run whose branch equals its protected branch --
         * correctly, since a fix pushes to a SOURCE branch and this run would name its own
         * destination -- and it refuses by throwing. On a Kafka consumer that is a redelivery.
         * Same shape as the blank destination one column along, so it gets the same treatment.
         */
        SOURCE_IS_DESTINATION
    }

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
                        rs.getString("dest_branch"), rs.getString("commit_sha"), rs.getString("pr_state"),
                        // getObject, NOT getBoolean: getBoolean maps SQL NULL to false, which would
                        // convert the one state V55 exists to preserve straight back into the guess
                        // it exists to avoid — silently, and with the right type.
                        rs.getObject("from_fork", Boolean.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the fix target for " + reviewId, e);
        }
    }

    /**
     * A pull request, as the review row recorded it.
     *
     * @param sourceBranch the branch a fix pushes to. Defaults to the empty string rather than null
     *     in {@code review_status}, which is why {@link #isPushable()} tests for blank rather than
     *     null: a blank ref reaches the publisher and fails {@code isValidRefName} inside a
     *     container, after the agent has been paid. {@code commit} carries the identical default
     *     and the identical failure, so it is guarded identically
     */
    public record PushTarget(String providerType, String workspace, String slug, long prId,
                             String sourceBranch, String destBranch, String commit, String prState,
                             Boolean fromFork) {

        /**
         * Why a fix may not be pushed here, or empty when it may.
         *
         * <p><b>ONE encoding of this rule, and the boolean derives from it.</b> An earlier shape had
         * this class answer a boolean and the dispatch answer a cause, which is two encodings of one
         * rule — the exact shape that produced two credential scrubbers here whose rules quietly
         * diverged. A 36-case test asserted they agreed, and that test could only ever check
         * WHETHER, never WHICH: swapping two causes passed it, and so did a fourth cause the
         * boolean did not model at all. Deriving makes the agreement structural, and makes a cause
         * added here without wording fail the BUILD rather than a loop.
         */
        public Optional<Unpushable> whyNotPushable() {
            if (fromFork == null) {
                return Optional.of(Unpushable.PROVENANCE_UNKNOWN);
            }
            if (fromFork) {
                // A fork's source branch lives in ANOTHER repository, while the clone URL is built
                // from this row's workspace and slug — so pushing the name resolves against the
                // wrong repository. ADR-040 puts forks out of scope for `existing` mode, and this
                // is the clause that makes that a rule rather than a sentence in a document.
                return Optional.of(Unpushable.FORK);
            }
            if (!OPEN.equals(prState)) {
                return Optional.of(Unpushable.NOT_OPEN);
            }
            // Both string columns, because both are NOT NULL DEFAULT '' and both fail the same way:
            // the publisher's Env.required refuses a blank INSIDE the container, after the agent has
            // been paid. isBlank rather than isEmpty, because a whitespace ref is not empty and
            // still reaches git — and no null check, since neither column can be null.
            // destBranch is the THIRD column with NOT NULL DEFAULT '', and it was the one left
            // unguarded — the same oversight this comment already records for commit, one column
            // along. It is not cosmetic: it becomes Planned.protectedBranch, and ExecuteRun's
            // compact constructor THROWS on a blank one in existing mode. So a row that never
            // recorded a destination would produce an exception on the /fix path where a refusal
            // with a reason belongs — and in a Kafka consumer an exception is a redelivery.
            if (sourceBranch.isBlank() || commit.isBlank() || destBranch.isBlank()) {
                return Optional.of(Unpushable.NOT_RECORDED_YET);
            }
            // strip() on both sides because that is what ExecuteRun compares after: a trailing
            // space would slip past an exact match here and be caught there, by a throw.
            if (sourceBranch.strip().equals(destBranch.strip())) {
                return Optional.of(Unpushable.SOURCE_IS_DESTINATION);
            }
            return Optional.empty();
        }

        /**
         * Whether a fix run may push to this branch — {@link #whyNotPushable()} answering nothing.
         *
         * <p><b>Fork pull requests are excluded, and were not when this class was written.</b> The
         * deployment could not tell one from a branch pull request until the three ingresses learned
         * to read it and V55 gave it a column. Until then this javadoc said so plainly rather than
         * carrying a field that was always false — which would have read as a check and been none.
         */
        public boolean isPushable() {
            return whyNotPushable().isEmpty();
        }

        /**
         * Whether this target names the repository the dispatch is for (ADR-040 §3).
         *
         * <p>Separate from {@link #isPushable()} because it needs what the CALLER is dispatching
         * for, which this row cannot know. The ADR asks for it in as many words, and the hazard is
         * one step less exotic than the fork gap this slice filed: a branch name resolved against
         * one repository and pushed against another.
         */
        public boolean belongsTo(RepoRef repo) {
            return workspace.equals(repo.workspace()) && slug.equals(repo.slug());
        }
    }
}
