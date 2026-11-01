package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.HarnessCredentialPool;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A close or a supersede that lands while a review run is being funded, before its row exists (review of PR #367,
 * code-quality/M-2): the cancellation finds no row to cancel, so the dispatch itself must refuse, or the obsolete run
 * starts with no cancel claim. Through the real dispatch, the real cancellation, the real row writer and the database;
 * only funding, the pull request read, the reviewer account and the broker are faked.
 */
@QuarkusTest
class ReviewRunDispatchRaceTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private static final String OTHER_HEAD = "fedcba9876543210fedcba9876543210fedcba98";

    @Inject
    ReviewRunDispatch dispatch;

    @Inject
    ReviewRunCancellation cancellation;

    @Inject
    DataSource dataSource;

    private final List<RunCommand> reviewLane = new ArrayList<>();
    private final List<RunCommand> controlSent = new ArrayList<>();
    private Runnable duringFunding = () -> { };
    private String reviewId;

    @BeforeEach
    void setUp() {
        long prId = 1 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        reviewId = "review::TEST-V108/TEST-REPO#" + prId;
        exec("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, commit_sha, pr_state)"
                + " VALUES (?, 'TEST-V108', 'TEST-REPO', " + prId + ", 'reviewing', '" + HEAD + "', 'OPEN')", reviewId);
        installFakes(prId);
    }

    @Test
    void aReviewWithNoCancellationIsAdmittedAndSent() {
        ReviewRunDispatch.Outcome outcome = dispatch.dispatch(request());

        assertInstanceOf(ReviewRunDispatch.Dispatched.class, outcome);
        assertEquals(1, rows(), "the control: the fixture can admit a run");
        assertEquals(1, reviewLane.size());
    }

    @Test
    void aCloseDuringFundingAdmitsNoRun() {
        duringFunding = () -> {
            // What IntegrationSaga does on PullRequestClosed: the state first, then the cancellation.
            exec("UPDATE review_status SET pr_state = 'CLOSED' WHERE review_id = ?", reviewId);
            cancellation.close(reviewId);
        };

        ReviewRunDispatch.Outcome outcome = dispatch.dispatch(request());

        assertEquals(new ReviewRunDispatch.Refused("review_run_cancelled"), outcome);
        assertEquals(0, rows(), "no row the cancellation could not see");
        assertTrue(reviewLane.isEmpty(), "and nothing sent for a closed pull request");
    }

    @Test
    void aSupersedeDuringFundingAdmitsNoRun() {
        duringFunding = () -> cancellation.supersede(reviewId, 1);

        ReviewRunDispatch.Outcome outcome = dispatch.dispatch(request(HEAD, 1));

        assertEquals(new ReviewRunDispatch.Refused("review_run_cancelled"), outcome);
        assertEquals(0, rows());
        assertTrue(reviewLane.isEmpty(), "nothing sent for a superseded round");
    }

    /** Only the superseded round is refused: the next round, dispatched while the supersede lands, is admitted. */
    @Test
    void aSupersedeOfAnEarlierRoundStillAdmits() {
        duringFunding = () -> cancellation.supersede(reviewId, 1);

        assertInstanceOf(ReviewRunDispatch.Dispatched.class, dispatch.dispatch(request(OTHER_HEAD, 2)));
        assertEquals(1, reviewLane.size());
    }

    /**
     * A forced re-run supersedes round 1 and requests round 2 on the SAME head (review of PR #367, code-quality/M-3).
     * Round 1's call, in flight when the supersede landed, stays refused; round 2's call on that head is admitted.
     */
    @Test
    void aForcedRerunAdmitsTheNewRoundAndStillRefusesTheOld() {
        List<ReviewRunDispatch.Outcome> newRound = new ArrayList<>();
        duringFunding = () -> {
            duringFunding = () -> { };
            cancellation.supersede(reviewId, 1);
            newRound.add(dispatch.dispatch(request(HEAD, 2)));
        };

        ReviewRunDispatch.Outcome oldRound = dispatch.dispatch(request(HEAD, 1));

        assertInstanceOf(ReviewRunDispatch.Dispatched.class, newRound.getFirst(), "the re-run's own call is admitted");
        assertEquals(new ReviewRunDispatch.Refused("review_run_cancelled"), oldRound, "the ended round stays refused");
        assertEquals(1, rows());
        assertEquals(1, reviewLane.size());
    }

    /** H, then J, then H again: rounds 1 and 2 ended, and round 3 on the first head is a new round, admitted. */
    @Test
    void aReturnToAnEarlierHeadIsANewRound() {
        cancellation.supersede(reviewId, 1);
        cancellation.supersede(reviewId, 2);

        assertInstanceOf(ReviewRunDispatch.Dispatched.class, dispatch.dispatch(request(HEAD, 3)));
        assertEquals(new ReviewRunDispatch.Refused("review_run_cancelled"), dispatch.dispatch(request(HEAD, 1)));
        assertEquals(1, reviewLane.size());
    }

    private ReviewRunRequest request() {
        return request(HEAD, 1);
    }

    /** A review call on {@code head} in {@code round}; its slot is the one CallRefs.reviewSlot gives that round. */
    private ReviewRunRequest request(String head, int round) {
        long prId = Long.parseLong(reviewId.substring(reviewId.indexOf('#') + 1));
        String slot = round == 1 ? head : head + "#run" + round;
        return new ReviewRunRequest(null, new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.REVIEW, prId, head,
                slot, round), new RunFunding.Choice("TEST-harness", "TEST-model", null, false), "TEST-prompt");
    }

    private void installFakes(long prId) {
        QuarkusMock.installMockForType(new RunFunding() {
            @Override
            public Funded fund(Choice choice) {
                duringFunding.run();
                return new Funded(new HarnessCredentialPool.PoolMember(null, "TEST-pool", "TEST-type",
                        "https://example.invalid", "TEST-key"), "TEST-key", "TEST-image");
            }
        }, RunFunding.class);
        QuarkusMock.installMockForType(new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String id) {
                return Optional.of(new PushTarget("github", "TEST-V108", "TEST-REPO", prId, "TEST-source", "main", HEAD,
                        "OPEN", false));
            }
        }, FixTargets.class);
        QuarkusMock.installMockForType(new RepositoryAccounts() {
            @Override
            public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-reviewer", "github", "https://forge.example.test",
                        "bearer", null, "TEST-reviewer-secret", "TEST-reviewer-id", true, List.of(), "TEST-reviewer", null, role));
            }
        }, RepositoryAccounts.class);
        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override public void dispatchReview(RunCommand command) { reviewLane.add(command); }
            @Override public void control(RunCommand command) { controlSent.add(command); }
            @Override public void dispatch(RunCommand command) { throw new AssertionError("a review never rides the build lane"); }
        }, RunCommandEmitter.class);
    }

    private int rows() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM factory_run WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the review's runs", e);
        }
    }

    private void exec(String sql, String arg) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
