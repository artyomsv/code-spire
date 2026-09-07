package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runs list — the read that finally joins a run to the review it came from.
 *
 * <p>Nothing did before M2's T8. {@code factory_run} carried {@code review_id} and {@code finding_ref}
 * from V54 and no query read them together with anything, so neither the caps' evidence nor "what did
 * this cost" could be shown to a person.
 */
@QuarkusTest
class FactoryRunListTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#900";

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM llm_charge WHERE subject_id LIKE 'run::github:TEST-WS%'");
        exec("DELETE FROM factory_run WHERE workspace = 'TEST-WS'");
    }

    private static FactoryRunProjection.RunFilter all() {
        return new FactoryRunProjection.RunFilter(null, null, null, 50);
    }

    private String buildRun(String subject) {
        String runId = "run::github:TEST-WS/TEST-REPO:" + subject + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL",
                "main", "TESTSHA0", "spire/" + subject, "machine-account", null)));
        return runId;
    }

    private String fixRun(String subject, String findingRef) {
        String runId = "run::github:TEST-WS/TEST-REPO:" + subject + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL",
                "main", "TESTSHA0", "feature/login", "machine-account", null)
                .asFixFor(REVIEW, findingRef, "TEST-comment-" + subject)));
        return runId;
    }

    /** <b>The join the whole task exists for:</b> a fix run says which review and which finding. */
    @Test
    void aFixRunNamesTheReviewAndTheFindingItCameFrom() {
        fixRun("thread-aaa", "thread-aaa");

        FactoryRunProjection.RunListEntry row = projection.list(all()).getFirst();

        assertEquals("FIX", row.kind());
        assertEquals(REVIEW, row.reviewId());
        assertEquals("thread-aaa", row.findingRef());
    }

    /**
     * And a build run names NEITHER, rather than carrying blanks that read as a broken join.
     *
     * <p>V54's CHECK already refuses a non-FIX row that names either; this asserts the READ does not
     * invent them back — a blank string here renders as a link to nothing.
     */
    @Test
    void aBuildRunNamesNoReviewAndNoFinding() {
        buildRun("manual-1");

        FactoryRunProjection.RunListEntry row = projection.list(all()).getFirst();

        assertEquals("BUILD", row.kind());
        assertNull(row.reviewId());
        assertNull(row.findingRef());
    }

    /** Newest first — a runs list is read to answer "what is happening now". */
    @Test
    void theNewestRunComesFirst() {
        String first = buildRun("older");
        String second = buildRun("newer");
        // started_at defaults to now() and both land in the same millisecond on a fast machine, so
        // the ordering is pinned by moving one explicitly. Without this the test would pass or fail
        // on timing rather than on the ORDER BY.
        exec("UPDATE factory_run SET started_at = now() - interval '1 hour' WHERE run_id = ?", first);

        List<String> ids = projection.list(all()).stream()
                .map(FactoryRunProjection.RunListEntry::runId).toList();

        assertEquals(List.of(second, first), ids);
    }

    /** The page size is honoured, or a list over a growing table gets slower forever. */
    @Test
    void thePageSizeBounds() {
        buildRun("a");
        buildRun("b");
        buildRun("c");

        assertEquals(2, projection.list(new FactoryRunProjection.RunFilter(null, null, null, 2)).size());
    }

    /** A page of nothing is a caller bug: it can only ever answer empty. */
    @Test
    void aPageOfNothingIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FactoryRunProjection.RunFilter(null, null, null, 0));
    }

    /** Each filter narrows, and the seeded rows prove it narrowed rather than the table being empty. */
    @Test
    void eachFilterNarrowsRatherThanAnsweringEverything() {
        String build = buildRun("manual-1");
        String fix = fixRun("thread-aaa", "thread-aaa");

        assertEquals(List.of(fix), ids(new FactoryRunProjection.RunFilter(null, "FIX", null, 50)));
        assertEquals(List.of(build), ids(new FactoryRunProjection.RunFilter(null, "BUILD", null, 50)));
        assertEquals(List.of(fix), ids(new FactoryRunProjection.RunFilter(null, null, REVIEW, 50)));
        assertEquals(2, projection.list(all()).size(), "and with no filter, both are there");
    }

    /** A review nobody ran for answers empty — seeded first, or an empty table would pass this. */
    @Test
    void aReviewWithNoRunsAnswersEmpty() {
        fixRun("thread-aaa", "thread-aaa");

        assertTrue(projection.list(
                new FactoryRunProjection.RunFilter(null, null, "review::TEST-WS/TEST-REPO#404", 50))
                .isEmpty());
    }

    /** The status filter reaches the column, driven through a status the row really holds. */
    @Test
    void theStatusFilterSelectsOnTheRowsOwnStatus() {
        String queued = buildRun("still-queued");
        String failed = buildRun("went-wrong");
        projection.dispatchFailed(failed, "the broker did not acknowledge the command");

        assertEquals(List.of(queued), ids(new FactoryRunProjection.RunFilter("queued", null, null, 50)));
        assertEquals(List.of(failed), ids(new FactoryRunProjection.RunFilter("failed", null, null, 50)));
    }

    // --- cost ------------------------------------------------------------------------------

    /**
     * <b>A run with no charge costs UNKNOWN, never zero.</b>
     *
     * <p>The charge lands when the model call completes, so every queued and running row is in this
     * state. A zero here would render as "free" beside runs that really were, and would aggregate.
     */
    @Test
    void aRunThatHasNotBeenChargedYetCostsUnknown() {
        buildRun("manual-1");

        assertFalse(projection.list(all()).getFirst().cost().isKnown());
    }

    /** A fully priced run reports what it cost, summed across its charge lines. */
    @Test
    void aPricedRunReportsTheSumOfItsLines() {
        String runId = buildRun("manual-1");
        charge(runId, "INPUT", 1200L);
        charge(runId, "OUTPUT", 3400L);

        assertEquals(4600L, projection.list(all()).getFirst().cost().millicents().longValue());
    }

    /**
     * <b>One unpriced line makes the whole run unknown, and this is the case a plain SUM hides.</b>
     *
     * <p>{@code SUM} skips NULL, so without the null-line count this run would report 1200 — a number
     * that looks like a total, is smaller than the truth, and carries no sign anything is missing.
     * That is the exact shape ADR-023 exists to forbid, arriving through a join rather than a rate.
     */
    @Test
    void aRunWithAnyUnpricedLineCostsUnknownRatherThanThePricedRemainder() {
        String runId = buildRun("manual-1");
        charge(runId, "INPUT", 1200L);
        charge(runId, "OUTPUT", null);

        assertFalse(projection.list(all()).getFirst().cost().isKnown(),
                "1200 would be the priced remainder, not the cost");
    }

    /** Another run's charges are not this run's — the join is on the subject, not on the table. */
    @Test
    void aRunsCostCountsOnlyItsOwnCharges() {
        String mine = buildRun("mine");
        String theirs = buildRun("theirs");
        charge(mine, "INPUT", 100L);
        charge(theirs, "INPUT", 900L);

        assertEquals(100L, projection.list(
                        new FactoryRunProjection.RunFilter(null, null, null, 50)).stream()
                .filter(r -> r.runId().equals(mine)).findFirst().orElseThrow()
                .cost().millicents().longValue());
    }

    // --- fixtures --------------------------------------------------------------------------

    private List<String> ids(FactoryRunProjection.RunFilter filter) {
        return projection.list(filter).stream()
                .map(FactoryRunProjection.RunListEntry::runId).toList();
    }

    /**
     * One charge line against a run.
     *
     * <p>Written with SQL rather than through {@code RunCharges}, deliberately: that writer needs a
     * finished {@code RunResult} and a pricer, and this is a test of the READ. A null cost is the
     * {@code pricing_mode = 'UNKNOWN'} row V30's own CHECK ties to a null — the state that cannot be
     * produced through a priced path at all.
     */
    private void charge(String runId, String tokenType, Long costMillicents) {
        String mode = costMillicents == null ? "UNKNOWN" : "METERED";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO llm_charge (id, subject_id, subject_kind, capability, call_ref, kind,
                                             model, pricing_mode, token_type, tokens,
                                             rate_millicents_per_million, cost_millicents)
                     VALUES (gen_random_uuid(), ?, 'RUN', 'BUILD', ?, 'BUILD', 'TEST-MODEL', ?, ?, 10,
                             ?, ?)
                     """)) {
            ps.setString(1, runId);
            ps.setString(2, "run:" + runId + ":agent:" + tokenType);
            ps.setString(3, mode);
            ps.setString(4, tokenType);
            if (costMillicents == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, 1L);
                ps.setLong(6, costMillicents);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a charge for " + runId, e);
        }
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String arg : args) {
                ps.setString(i++, arg);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
